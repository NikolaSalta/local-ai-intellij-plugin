package com.example.localai.tools

import com.example.localai.model.ToolCall
import com.example.localai.model.ToolResult
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Executes read-only tools safely within project boundaries.
 * Plain class — instantiated by AgentLoopService with the project base path.
 */
class ToolExecutor(private val projectBasePath: String) {

    private val logger = Logger.getInstance(ToolExecutor::class.java)

    companion object {
        private const val MAX_FILE_LINES = 500
        private const val MAX_LIST_ENTRIES = 200
        private const val MAX_GREP_MATCHES = 50
    }

    /**
     * Executes a tool call and returns the result.
     */
    fun execute(toolCall: ToolCall): ToolResult {
        return try {
            when (toolCall.tool) {
                "read_file" -> executeReadFile(toolCall.args)
                "list_files" -> executeListFiles(toolCall.args)
                "grep_text" -> executeGrepText(toolCall.args)
                "search_code" -> executeSearchCode(toolCall.args)
                else -> ToolResult(
                    tool = toolCall.tool,
                    success = false,
                    output = "Unknown tool: ${toolCall.tool}"
                )
            }
        } catch (e: Exception) {
            logger.warn("Tool execution failed: ${toolCall.tool}", e)
            ToolResult(
                tool = toolCall.tool,
                success = false,
                output = "Error: ${e.message}"
            )
        }
    }

    private fun executeReadFile(args: Map<String, String>): ToolResult {
        val relativePath = args["path"] ?: return ToolResult("read_file", false, "Missing 'path' argument")
        val resolvedPath = resolveSafePath(relativePath)
            ?: return ToolResult("read_file", false, "Path is outside project boundary")

        val file = resolvedPath.toFile()
        if (!file.exists()) return ToolResult("read_file", false, "File not found: $relativePath")
        if (!file.isFile) return ToolResult("read_file", false, "Not a file: $relativePath")

        val lines = file.readLines(Charsets.UTF_8)
        val content = if (lines.size > MAX_FILE_LINES) {
            lines.take(MAX_FILE_LINES).joinToString("\n") + "\n\n[truncated — showing first $MAX_FILE_LINES of ${lines.size} lines]"
        } else {
            lines.joinToString("\n")
        }

        return ToolResult("read_file", true, content)
    }

    private fun executeListFiles(args: Map<String, String>): ToolResult {
        val relativePath = args["path"] ?: ""
        val recursive = args["recursive"]?.toBooleanStrictOrNull() ?: false
        val resolvedPath = resolveSafePath(relativePath)
            ?: return ToolResult("list_files", false, "Path is outside project boundary")

        val dir = resolvedPath.toFile()
        if (!dir.exists()) return ToolResult("list_files", false, "Directory not found: $relativePath")
        if (!dir.isDirectory) return ToolResult("list_files", false, "Not a directory: $relativePath")

        val entries = mutableListOf<String>()
        collectFiles(dir, dir, recursive, entries)

        val listing = if (entries.size > MAX_LIST_ENTRIES) {
            entries.take(MAX_LIST_ENTRIES).joinToString("\n") + "\n\n[truncated — showing first $MAX_LIST_ENTRIES of ${entries.size} entries]"
        } else {
            entries.joinToString("\n")
        }

        return ToolResult("list_files", true, listing)
    }

    private fun collectFiles(root: File, dir: File, recursive: Boolean, entries: MutableList<String>) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (entries.size >= MAX_LIST_ENTRIES) return
            val relative = root.toPath().relativize(child.toPath()).toString()
            val prefix = if (child.isDirectory) "[dir]  " else "[file] "
            entries.add("$prefix$relative")
            if (recursive && child.isDirectory && !child.name.startsWith(".")) {
                collectFiles(root, child, true, entries)
            }
        }
    }

    private fun executeGrepText(args: Map<String, String>): ToolResult {
        val pattern = args["pattern"] ?: return ToolResult("grep_text", false, "Missing 'pattern' argument")
        val relativePath = args["path"] ?: ""
        val resolvedPath = resolveSafePath(relativePath)
            ?: return ToolResult("grep_text", false, "Path is outside project boundary")

        val dir = resolvedPath.toFile()
        if (!dir.exists()) return ToolResult("grep_text", false, "Path not found: $relativePath")

        val matches = mutableListOf<String>()
        searchInFiles(dir, pattern, matches)

        val output = if (matches.isEmpty()) {
            "No matches found for: $pattern"
        } else {
            matches.joinToString("\n")
        }

        return ToolResult("grep_text", true, output)
    }

    private fun executeSearchCode(args: Map<String, String>): ToolResult {
        val query = args["query"] ?: return ToolResult("search_code", false, "Missing 'query' argument")
        val baseDir = Paths.get(projectBasePath).toFile()
        val matches = mutableListOf<String>()
        searchInFiles(baseDir, query, matches)

        val output = if (matches.isEmpty()) {
            "No matches found for: $query"
        } else {
            matches.joinToString("\n")
        }

        return ToolResult("search_code", true, output)
    }

    private fun searchInFiles(dir: File, pattern: String, matches: MutableList<String>) {
        val files = dir.walkTopDown()
            .filter { it.isFile && !it.path.contains("/.") && !it.path.contains("\\.") }
            .filter { isTextFile(it) }

        for (file in files) {
            if (matches.size >= MAX_GREP_MATCHES) break
            try {
                val lines = file.readLines(Charsets.UTF_8)
                for ((index, line) in lines.withIndex()) {
                    if (matches.size >= MAX_GREP_MATCHES) break
                    if (line.contains(pattern, ignoreCase = true)) {
                        val relPath = Paths.get(projectBasePath).relativize(file.toPath())
                        matches.add("$relPath:${index + 1}: ${line.trim()}")
                    }
                }
            } catch (_: Exception) {
                // Skip files that can't be read as text
            }
        }
    }

    private fun isTextFile(file: File): Boolean {
        val textExtensions = setOf(
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "css", "xml",
            "json", "yaml", "yml", "md", "txt", "gradle", "kts", "properties",
            "sh", "bat", "sql", "rs", "go", "c", "cpp", "h", "hpp", "rb",
            "swift", "toml", "cfg", "ini", "conf"
        )
        val ext = file.extension.lowercase()
        return ext in textExtensions || file.length() < 100_000
    }

    /**
     * Resolves a relative path safely within the project boundary.
     * Returns null if the path would escape the project root.
     */
    private fun resolveSafePath(relativePath: String): Path? {
        if (relativePath.contains("..")) return null

        val basePath = Paths.get(projectBasePath).normalize()
        val resolved = if (relativePath.isBlank()) {
            basePath
        } else {
            basePath.resolve(relativePath).normalize()
        }

        return if (resolved.startsWith(basePath)) resolved else null
    }
}
