package com.example.localai.services

import com.example.localai.model.ChatMessage
import com.example.localai.model.ChatRole
import com.example.localai.model.TimelineEntry
import com.example.localai.model.TimelineEventType
import com.example.localai.tools.ToolCatalog
import com.example.localai.tools.ToolExecutor
import com.example.localai.model.ToolCall
import com.example.localai.tools.ToolResultFormatter
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Project-level service implementing the bounded agent loop.
 * Constructor takes Project only.
 *
 * KEY DESIGN: For small local models (7B-14B), the agent pre-gathers project
 * evidence automatically (file listing, README, source files) and injects it
 * into the context. This way the model only needs to ANALYZE and FORMAT,
 * not orchestrate tool calls — which small models can't do reliably.
 */
@Service(Service.Level.PROJECT)
class AgentLoopService(private val project: Project) {

    private val logger = Logger.getInstance(AgentLoopService::class.java)

    companion object {
        private const val MAX_ITERATIONS = 10
        private const val MAX_AUTO_READ_FILES = 10
        private const val MAX_FILE_LINES = 200
        private const val MAX_EVIDENCE_CHARS = 30000
    }

    /**
     * Runs the agent loop using the given chat session.
     * Delegates to ProjectAnalysisRunner for progressive map-reduce analysis.
     * The runner reads files one-by-one, summarizes each via LLM,
     * then synthesizes all summaries into a structured report.
     */
    fun runAgentLoop(session: ChatSessionService) {
        val projectBasePath = project.basePath ?: return

        // Use the map-reduce runner for detailed analysis
        val runner = project.getService(ProjectAnalysisRunner::class.java)

        // Get the user's latest message as the prompt
        val userPrompt = session.messages
            .lastOrNull { it.role == ChatRole.USER }
            ?.content ?: return

        runner.runAnalysis(session, userPrompt)
    }

    // === Evidence gathering ===

    data class ProjectEvidence(
        val fileTree: String,
        val readmeContent: String?,
        val sourceFiles: List<FileContent>,
        val filesFound: Int,
        val filesRead: Int
    )

    data class FileContent(
        val relativePath: String,
        val content: String,
        val totalLines: Int
    )

    /**
     * Automatically gathers project evidence by:
     * 1. Listing all files recursively
     * 2. Reading README.md
     * 3. Reading key source files (.java, .kt, .py, etc.)
     */
    private fun gatherProjectEvidence(basePath: String): ProjectEvidence {
        val baseDir = File(basePath)

        // 1. File tree (recursive, max 200 entries)
        val allFiles = mutableListOf<String>()
        collectFileTree(baseDir, baseDir, allFiles, 0)
        val fileTree = allFiles.joinToString("\n")

        // 2. Read README.md
        val readmeFile = File(basePath, "README.md")
        val readmeContent = if (readmeFile.exists() && readmeFile.isFile) {
            try {
                readmeFile.readText(Charsets.UTF_8).take(5000)
            } catch (e: Exception) {
                null
            }
        } else null

        // 3. Read key source files
        val sourceExtensions = setOf("java", "kt", "py", "js", "ts", "go", "rs")
        val configFiles = setOf("pom.xml", "build.gradle", "build.gradle.kts", "package.json",
            "Cargo.toml", "go.mod", "settings.gradle.kts", "compile.bat", ".gitignore")

        val sourceFiles = mutableListOf<FileContent>()
        var totalChars = 0

        // Read config files first
        for (configName in configFiles) {
            if (sourceFiles.size >= MAX_AUTO_READ_FILES || totalChars >= MAX_EVIDENCE_CHARS) break
            val configFile = File(basePath, configName)
            if (configFile.exists() && configFile.isFile) {
                val content = tryReadFile(configFile)
                if (content != null) {
                    sourceFiles.add(FileContent(configName, content, content.lines().size))
                    totalChars += content.length
                }
            }
        }

        // Read source files
        val foundSources = baseDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in sourceExtensions }
            .filter { !it.path.contains("/.") && !it.path.contains("\\.") }
            .sortedByDescending { it.length() }  // Read largest files first — usually most important
            .toList()

        for (file in foundSources) {
            if (sourceFiles.size >= MAX_AUTO_READ_FILES || totalChars >= MAX_EVIDENCE_CHARS) break
            val content = tryReadFile(file)
            if (content != null) {
                val relativePath = baseDir.toPath().relativize(file.toPath()).toString()
                sourceFiles.add(FileContent(relativePath, content, content.lines().size))
                totalChars += content.length
            }
        }

        return ProjectEvidence(
            fileTree = fileTree,
            readmeContent = readmeContent,
            sourceFiles = sourceFiles,
            filesFound = allFiles.size,
            filesRead = sourceFiles.size + (if (readmeContent != null) 1 else 0)
        )
    }

    private fun collectFileTree(root: File, dir: File, entries: MutableList<String>, depth: Int) {
        if (entries.size >= 200 || depth > 5) return
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (entries.size >= 200) return
            if (child.name.startsWith(".") && depth == 0 && child.isDirectory) continue
            val relative = root.toPath().relativize(child.toPath()).toString()
            val prefix = if (child.isDirectory) "[dir]  " else "[file] "
            val sizeInfo = if (child.isFile) " (${child.length()} bytes)" else ""
            entries.add("$prefix$relative$sizeInfo")
            if (child.isDirectory && !child.name.startsWith(".")) {
                collectFileTree(root, child, entries, depth + 1)
            }
        }
    }

    private fun tryReadFile(file: File): String? {
        return try {
            if (file.length() > 200_000) return null  // Skip very large files
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.size > MAX_FILE_LINES) {
                lines.take(MAX_FILE_LINES).joinToString("\n") +
                    "\n\n[... truncated, showing first $MAX_FILE_LINES of ${lines.size} lines]"
            } else {
                lines.joinToString("\n")
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Formats gathered evidence into a text block for the system prompt.
     */
    private fun formatEvidence(evidence: ProjectEvidence, basePath: String): String {
        val sb = StringBuilder()
        sb.appendLine("=== PRE-GATHERED PROJECT EVIDENCE ===")
        sb.appendLine("The following evidence was gathered automatically from the project at: $basePath")
        sb.appendLine("Use this evidence to produce your report. Do NOT hallucinate or invent facts.")
        sb.appendLine()

        sb.appendLine("--- FILE TREE ---")
        sb.appendLine(evidence.fileTree)
        sb.appendLine()

        if (evidence.readmeContent != null) {
            sb.appendLine("--- README.md ---")
            sb.appendLine(evidence.readmeContent)
            sb.appendLine()
        }

        for (file in evidence.sourceFiles) {
            sb.appendLine("--- FILE: ${file.relativePath} (${file.totalLines} lines) ---")
            sb.appendLine(file.content)
            sb.appendLine()
        }

        sb.appendLine("=== END OF EVIDENCE ===")
        sb.appendLine("Based on the above evidence, produce your structured report. Only cite facts from the evidence above.")
        return sb.toString()
    }

    // === Tool call parsing (kept for models that CAN use tools) ===

    private fun parseToolCall(response: String): ToolCall? {
        return try {
            val jsonStr = extractJson(response) ?: return null
            val json = JsonParser.parseString(jsonStr).asJsonObject

            if (json.has("tool") && json.has("args")) {
                val toolName = json.get("tool").asString
                val argsObj = json.getAsJsonObject("args")
                val args = mutableMapOf<String, String>()
                for (entry in argsObj.entrySet()) {
                    args[entry.key] = entry.value.asString
                }
                ToolCall(toolName, args)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("Response is not a tool call: ${e.message}")
            null
        }
    }

    private fun extractJson(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val codeBlockMatch = codeBlockPattern.find(text)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1]
        }

        val braceStart = text.indexOf('{')
        val braceEnd = text.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1)
        }

        return null
    }
}
