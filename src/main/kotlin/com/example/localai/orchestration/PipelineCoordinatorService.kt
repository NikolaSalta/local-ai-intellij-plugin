package com.example.localai.orchestration

import com.example.localai.model.*
import com.example.localai.state.ExecutionPhase
import com.example.localai.state.PipelineStage
import com.example.localai.state.SessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Executes the analysis pipeline stage by stage.
 * Checks stop flags between stages. Reports progress via timeline.
 *
 * Pipeline stages for STRUCTURE_FIRST strategy:
 * 1. Structural scan (file tree, source roots, build files)
 * 2. Repo type classification
 * 3. Representative file selection
 * 4. Evidence gate check
 * 5. Final synthesis (LLM call)
 */
@Service(Service.Level.PROJECT)
class PipelineCoordinatorService(private val project: Project) {

    private val logger = Logger.getInstance(PipelineCoordinatorService::class.java)

    /**
     * Execute the full pipeline for the given interpretation.
     * Updates session state as it progresses.
     */
    fun executePipeline(session: SessionState, interpretation: RequestInterpretation): String {
        val basePath = interpretation.primaryTarget

        // Stage 1: Structural Scan
        if (session.shouldStop()) return "[Stopped]"
        session.executionPhase = ExecutionPhase.RUNNING_STRUCTURAL_SCAN
        trace(session, TimelineEventType.STRUCTURAL_SCAN_START, "Scanning project: $basePath")

        val skeleton = runStructuralScan(basePath, session)
        session.pipelineStage = PipelineStage.STRUCTURAL_SCAN_DONE
        trace(session, TimelineEventType.STRUCTURAL_SCAN_FINISH,
            "${skeleton.totalFiles} files, ${skeleton.sourceFiles.size} source files found")

        // Stage 2: Repo Type Detection
        if (session.shouldStop()) return "[Stopped]"
        val repoType = classifyRepoType(skeleton)
        session.repoType = repoType
        session.pipelineStage = PipelineStage.REPO_TYPE_DETECTED
        trace(session, TimelineEventType.REPO_TYPE_DETECTED, "Repo type: $repoType")

        // Stage 3: Evidence Gate
        if (session.shouldStop()) return "[Stopped]"
        session.executionPhase = ExecutionPhase.CHECKING_EVIDENCE
        trace(session, TimelineEventType.EVIDENCE_GATE_CHECK, "Checking evidence sufficiency...")

        val gateResult = checkEvidenceGate(skeleton, session)
        if (gateResult.passed) {
            session.pipelineStage = PipelineStage.EVIDENCE_GATE_PASSED
            trace(session, TimelineEventType.EVIDENCE_GATE_PASSED,
                "Evidence sufficient: ${gateResult.foundCount}/${gateResult.totalChecks} checks passed")
        } else {
            trace(session, TimelineEventType.EVIDENCE_GATE_FAILED,
                "Evidence gaps: ${gateResult.gaps.joinToString(", ")}")
        }

        // Stage 4: Build Deterministic Report (used as LLM context)
        if (session.shouldStop()) return "[Stopped]"
        session.executionPhase = ExecutionPhase.BUILDING_ARTIFACTS
        session.pipelineStage = PipelineStage.ARTIFACTS_BUILT
        trace(session, TimelineEventType.ARTIFACT_CREATED, "Building evidence context...")

        val deterministicReport = buildDeterministicReport(skeleton, repoType, basePath)

        // Stage 5: Decide pipeline path based on task type
        val isAnalysisOnly = interpretation.taskType in setOf(
            TaskType.PROJECT_OVERVIEW,
            TaskType.REPO_ANALYSIS,
            TaskType.BUILD_AND_RUN_ANALYSIS
        )

        // Stage 6: Task-Aware LLM Synthesis
        if (session.shouldStop()) return "[Stopped]"
        session.executionPhase = ExecutionPhase.WAITING_FOR_LLM
        trace(session, TimelineEventType.SYNTHESIS_START,
            if (isAnalysisOnly) "LLM: generating project description..."
            else "LLM: processing user request with ${skeleton.sourceFiles.size} source files as context...")

        val llmResponse: String
        if (isAnalysisOnly) {
            // For analysis tasks: just generate a brief project description
            llmResponse = generateProjectDescription(skeleton)
        } else {
            // For ALL other tasks: send user's ACTUAL prompt + evidence context to Ollama
            llmResponse = executeTaskAwareSynthesis(interpretation, skeleton, deterministicReport, session)
        }

        session.pipelineStage = PipelineStage.FINAL_SYNTHESIS_DONE
        trace(session, TimelineEventType.SYNTHESIS_FINISH, "LLM synthesis complete (${llmResponse.length} chars)")

        // Stage 7: Assemble final output
        session.executionPhase = ExecutionPhase.READY
        val sb = StringBuilder()

        // === Orchestration Metadata ===
        sb.appendLine("═══ ORCHESTRATION METADATA ═══")
        sb.appendLine()
        sb.appendLine("A. Primary Target: ${interpretation.primaryTarget}")
        sb.appendLine("B. Host Project: ${interpretation.hostProject}")
        sb.appendLine("C. Task Type: ${interpretation.taskType}")
        sb.appendLine("D. Execution Strategy: ${interpretation.executionStrategy}")
        sb.appendLine()

        // === Evidence Summary ===
        sb.appendLine("═══ EVIDENCE ═══")
        sb.appendLine()
        val found = session.evidence.filter { it.status == EvidenceStatus.FOUND }
        val notFound = session.evidence.filter { it.status == EvidenceStatus.NOT_FOUND }
        sb.appendLine("I. Evidence Collected (${found.size}):")
        for (ev in found) {
            sb.appendLine("  ✅ ${ev.type}: ${ev.path}")
        }
        sb.appendLine()
        if (notFound.isNotEmpty()) {
            sb.appendLine("J. Missing Evidence (${notFound.size}):")
            for (ev in notFound) {
                sb.appendLine("  ❌ ${ev.type}: ${ev.path}")
            }
        } else {
            sb.appendLine("J. Missing Evidence: none")
        }
        sb.appendLine()

        // Pipeline info
        sb.appendLine("K. Pipeline: ${session.pipelineStage}")
        sb.appendLine("L. Repo Type: $repoType")
        sb.appendLine("M. Evidence Gate: ${if (gateResult.passed) "PASSED (${gateResult.foundCount}/${gateResult.totalChecks})" else "FAILED — gaps: ${gateResult.gaps.joinToString(", ")}"}")
        sb.appendLine()

        if (isAnalysisOnly) {
            // For analysis tasks: show deterministic report
            sb.appendLine("═══ DETERMINISTIC REPORT ═══")
            sb.appendLine()
            sb.appendLine("N. Что это за проект")
            sb.appendLine(llmResponse)
            sb.appendLine()
            sb.append(deterministicReport)
        } else {
            // For generation/review tasks: show LLM response
            sb.appendLine("═══ LLM RESPONSE (task-aware synthesis) ═══")
            sb.appendLine()
            sb.appendLine(llmResponse)
            sb.appendLine()
            sb.appendLine("═══ SUPPORTING EVIDENCE (deterministic scan) ═══")
            sb.appendLine()
            sb.append(deterministicReport)
        }

        val finalReport = sb.toString()

        trace(session, TimelineEventType.FINAL_TEXT, "Report ready (${finalReport.length} chars)")
        return finalReport
    }

    // ==================== TASK-AWARE LLM SYNTHESIS ====================

    /**
     * Send the user's actual prompt to Ollama with the gathered evidence as grounding context.
     * This is the core differentiator from a simple chat wrapper:
     * the LLM receives VERIFIED project facts, not hallucinated assumptions.
     */
    private fun executeTaskAwareSynthesis(
        interpretation: RequestInterpretation,
        skeleton: ProjectSkeleton,
        deterministicReport: String,
        session: SessionState
    ): String {
        val client = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.example.localai.services.OllamaClientService::class.java)
        val settings = com.example.localai.settings.LocalAiSettingsState.instance

        // Build context from evidence
        val contextBlock = buildLlmContext(skeleton, deterministicReport)

        // Build system prompt based on task type
        val systemPrompt = buildSystemPrompt(interpretation, skeleton)

        // Build user message: context + original prompt
        val userMessage = """
=== VERIFIED PROJECT CONTEXT (from deterministic analysis) ===

$contextBlock

=== USER REQUEST ===

${interpretation.userIntent}
""".trimIndent()

        trace(session, TimelineEventType.MODEL_CALL_START,
            "Sending to Ollama: ${systemPrompt.length + userMessage.length} chars context")

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userMessage)
        )

        return try {
            val response = client.chat(settings.defaultModel, messages, formatJson = false)
            trace(session, TimelineEventType.MODEL_CALL_FINISH,
                "Ollama responded: ${response.length} chars")
            response
        } catch (e: Exception) {
            trace(session, TimelineEventType.ERROR, "LLM error: ${e.message}")
            "LLM Error: ${e.javaClass.simpleName}: ${e.message}\n\nThe deterministic report below contains all verified evidence."
        }
    }

    /**
     * Build a compact context block from the evidence for the LLM.
     */
    private fun buildLlmContext(skeleton: ProjectSkeleton, deterministicReport: String): String {
        val sb = StringBuilder()

        sb.appendLine("Project: ${skeleton.projectName}")
        sb.appendLine("Languages: ${skeleton.languages.joinToString(", ")}")
        sb.appendLine("Build: ${skeleton.buildSystem}")
        if (skeleton.uiFramework != null) sb.appendLine("UI: ${skeleton.uiFramework}")
        sb.appendLine("Target OS: ${skeleton.targetOS ?: "Unknown"}")
        sb.appendLine("Source files: ${skeleton.sourceFiles.size}")
        sb.appendLine("Entry points: ${skeleton.entryPoints.size}")
        sb.appendLine()

        // Include class/method map
        sb.appendLine("=== Class & Method Map ===")
        for (sf in skeleton.sourceFiles) {
            sb.appendLine("${sf.path} (${sf.lang}, ${sf.lines} lines)")
            if (sf.classes.isNotEmpty()) sb.appendLine("  Classes: ${sf.classes.joinToString(", ")}")
            if (sf.methods.isNotEmpty()) sb.appendLine("  Methods: ${sf.methods.joinToString(", ")}")
            sb.appendLine("  main(): ${sf.hasMain}")
        }
        sb.appendLine()

        // Include README if available
        if (skeleton.readme != null) {
            sb.appendLine("=== README (first 3000 chars) ===")
            sb.appendLine(skeleton.readme.take(3000))
            sb.appendLine()
        }

        // Include representative source code snippets
        sb.appendLine("=== Source Code Snippets ===")
        val basePath = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.example.localai.settings.LocalAiSettingsState::class.java)
        // Read key source files for context (limit to keep token count manageable)
        for (sf in skeleton.sourceFiles.sortedByDescending { it.lines }.take(3)) {
            val file = java.io.File(skeleton.projectName).parentFile?.let { java.io.File(it, sf.path) }
                ?: continue
            // Try to read from the actual basePath
            val content = skeleton.entryPoints.find { it.file == sf.path }?.codeSnippet
            if (content != null && content.isNotEmpty()) {
                sb.appendLine("--- ${sf.path} (entry point) ---")
                sb.appendLine(content)
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    /**
     * Build a task-specific system prompt for Ollama.
     */
    private fun buildSystemPrompt(interpretation: RequestInterpretation, skeleton: ProjectSkeleton): String {
        val base = "You are a senior software engineer. You have been given VERIFIED, DETERMINISTIC facts about a project. " +
            "These facts come from automated source code analysis, NOT from guessing. " +
            "Use ONLY these facts as your foundation. Do NOT invent files, classes, or APIs that are not in the evidence. " +
            "Write your response in Russian unless the user's prompt is in English."

        val taskSpecific = when (interpretation.taskType) {
            TaskType.ARCHITECTURE_REVIEW -> """
                |You are conducting an architecture review. Based on the verified evidence:
                |1. Analyze the project structure and patterns
                |2. Identify strengths and weaknesses
                |3. Provide specific, actionable recommendations
                |4. Reference actual files, classes, and methods from the evidence
            """.trimMargin()

            TaskType.SECURITY_REVIEW -> """
                |You are conducting a security review. Based on the verified evidence:
                |1. Check for hardcoded credentials, IPs, URLs
                |2. Analyze authentication and authorization patterns
                |3. Review input validation and error handling
                |4. Reference actual code locations
            """.trimMargin()

            TaskType.CODE_QUALITY_REVIEW -> """
                |You are reviewing code quality. Based on the verified evidence:
                |1. Identify code smells and duplication
                |2. Check naming conventions and consistency
                |3. Evaluate error handling patterns
                |4. Suggest specific refactoring targets with file/class references
            """.trimMargin()

            TaskType.IMPLEMENT_FEATURE, TaskType.FULLSTACK_FEATURE,
            TaskType.API_DESIGN, TaskType.FRONTEND_DESIGN, TaskType.BACKEND_DESIGN -> """
                |You are implementing a feature. Based on the verified project evidence:
                |1. Understand the existing codebase structure
                |2. Design your solution to fit the existing patterns
                |3. Produce complete, working code
                |4. Explain where each file should be placed
                |5. If tests are requested, create real test code with actual test methods
            """.trimMargin()

            TaskType.PLAN_ONLY -> """
                |You are creating a technical implementation plan. Based on the verified evidence:
                |1. Break down the work into concrete steps
                |2. Reference actual files and classes that need to be modified
                |3. Identify risks and dependencies
                |4. Estimate complexity per step
            """.trimMargin()

            TaskType.BUG_ANALYSIS -> """
                |You are analyzing a bug. Based on the verified evidence:
                |1. Trace the likely execution path
                |2. Identify where the bug could originate
                |3. Check error handling in the relevant code
                |4. Suggest specific fixes with code
            """.trimMargin()

            TaskType.VERIFY_FEATURE, TaskType.SYSTEM_VALIDATION -> """
                |You are verifying feature implementation. Based on the verified evidence:
                |1. Check if the feature exists in the codebase
                |2. Verify completeness against requirements
                |3. Identify gaps or missing parts
                |4. Report with evidence references
            """.trimMargin()

            TaskType.SEARCH_ONLY -> """
                |You are searching the codebase. Based on the verified evidence:
                |1. Find the relevant code locations
                |2. Show exact file paths and code snippets
                |3. Explain what you found
            """.trimMargin()

            TaskType.DOCS_ALIGNMENT -> """
                |You are checking documentation alignment. Based on the verified evidence:
                |1. Compare docs against actual codebase
                |2. Identify mismatches
                |3. Suggest corrections
            """.trimMargin()

            else -> "Fulfill the user's request using the verified project evidence."
        }

        return "$base\n\n$taskSpecific"
    }

    // ==================== STRUCTURAL SCAN ====================

    data class ProjectSkeleton(
        val projectName: String,
        val totalFiles: Int,
        val totalDirs: Int,
        val tree: String,
        val readme: String?,
        val sourceFiles: List<SourceFileInfo>,
        val scripts: List<ScriptInfo>,
        val configs: List<ConfigInfo>,
        val entryPoints: List<EntryPointInfo>,
        val languages: Set<String>,
        val buildSystem: String,
        val buildCommands: List<String>,
        val uiFramework: String?,
        val externalDeps: String,
        val hardcodedIPs: List<String>,
        val hardcodedURLs: List<String>,
        val licenseType: String?,
        val testFiles: List<String>,
        val targetOS: String?
    )

    data class SourceFileInfo(val path: String, val lang: String, val lines: Int, val bytes: Long,
                              val hasMain: Boolean, val classes: List<String>, val methods: List<String>)
    data class ScriptInfo(val name: String, val lines: Int, val bytes: Long, val commands: List<String>)
    data class ConfigInfo(val name: String, val lines: Int, val content: String)
    data class EntryPointInfo(val className: String, val file: String, val codeSnippet: String)

    private fun runStructuralScan(basePath: String, session: SessionState): ProjectSkeleton {
        val base = File(basePath)
        if (!base.exists() || !base.isDirectory) {
            return emptyProjectSkeleton(base.name)
        }

        val name = base.name
        val treeLines = mutableListOf<String>()
        var fCount = 0; var dCount = 0
        buildTree(base, base, treeLines, 0) { d -> if (d) dCount++ else fCount++ }

        // Evidence: README
        val readme = listOf("README.md","readme.md","README.txt","README")
            .map { File(basePath, it) }.firstOrNull { it.exists() }
            ?.let { safe(it)?.take(8000) }
        addEvidence(session, EvidenceType.README, "README",
            if (readme != null) EvidenceStatus.FOUND else EvidenceStatus.NOT_FOUND)

        // Source files
        val exts = mapOf("java" to "Java","kt" to "Kotlin","py" to "Python",
            "js" to "JavaScript","ts" to "TypeScript","go" to "Go","rs" to "Rust",
            "swift" to "Swift","cpp" to "C++","c" to "C")
        val srcs = mutableListOf<SourceFileInfo>()
        val langs = mutableSetOf<String>()
        val allIPs = mutableListOf<String>()
        val allURLs = mutableListOf<String>()
        val allImports = mutableListOf<String>()
        var uiFramework: String? = null

        val javaKeywords = setOf("if","for","while","switch","catch","new","return","throw",
            "static","final","abstract","void","int","long","boolean","double","float",
            "char","byte","short","String","Object","null","true","false","this","super")
        val swingTypes = setOf("JPanel","JLabel","JButton","JFrame","JTextField","JTextArea",
            "JScrollPane","JProgressBar","JOptionPane","JPopupMenu","JMenuItem","JComboBox",
            "JTable","JTree","JDialog","JFileChooser","JCheckBox","JRadioButton",
            "ImageIcon","BufferedImage","Graphics2D","Color","Font","Insets","Dimension",
            "BorderLayout","FlowLayout","GridBagLayout","GridBagConstraints","GridLayout",
            "Polygon","Rectangle","Point","Timer","Scanner","Socket","ProcessBuilder",
            "StringBuilder","ArrayList","HashMap","HashSet","Exception","Runtime","Thread")

        base.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in exts }
            .filter { !it.path.contains("/.") }
            .sortedByDescending { it.length() }
            .take(25)
            .forEach { f ->
                val lang = exts[f.extension.lowercase()] ?: return@forEach
                langs.add(lang)
                val content = safe(f) ?: return@forEach
                val lines = content.lines()
                val rel = base.toPath().relativize(f.toPath()).toString()

                val hasMain = when(lang) {
                    "Java" -> content.contains("public static void main(")
                    "Kotlin" -> content.contains("fun main(")
                    "Python" -> content.contains("if __name__") && content.contains("__main__")
                    else -> content.contains("func main(") || content.contains("fn main(")
                }

                val classes = Regex("""(?:public\s+)?class\s+([A-Z]\w+)""").findAll(content)
                    .map { it.groupValues[1] }
                    .filter { it !in javaKeywords && it !in swingTypes }.toList()

                val methods = when(lang) {
                    "Java" -> Regex("""(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\]]+\s+(\w+)\s*\(""")
                        .findAll(content).map { it.groupValues[1] }
                        .filter { it !in javaKeywords && it !in swingTypes && it[0].isLowerCase() }
                        .distinct().toList().take(20)
                    "Kotlin" -> Regex("""fun\s+(\w+)\s*\(""").findAll(content)
                        .map { it.groupValues[1] }.distinct().toList().take(20)
                    else -> emptyList()
                }

                val fileImports = lines.filter { it.trimStart().startsWith("import ") }.map { it.trim() }
                allImports.addAll(fileImports)
                if (fileImports.any { it.contains("javax.swing") }) uiFramework = "Swing (javax.swing)"
                if (fileImports.any { it.contains("javafx") }) uiFramework = "JavaFX"

                val ips = Regex("""\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b""").findAll(content)
                    .map { it.value }.filter { it != "0.0.0.0" && it != "255.255.255.255" && it != "127.0.0.1" }
                    .distinct().toList()
                allIPs.addAll(ips)

                val urls = Regex("""https?://[^\s"'<>)]+""").findAll(content)
                    .map { it.value }.distinct().toList().take(5)
                allURLs.addAll(urls)

                srcs.add(SourceFileInfo(rel, lang, lines.size, f.length(), hasMain, classes, methods))
                addEvidence(session, EvidenceType.REPRESENTATIVE_SOURCE, rel, EvidenceStatus.FOUND)
            }

        addEvidence(session, EvidenceType.SOURCE_ROOT, "source files",
            if (srcs.isNotEmpty()) EvidenceStatus.FOUND else EvidenceStatus.NOT_FOUND)

        // Entry points
        val entries = srcs.filter { it.hasMain }.map { sf ->
            val content = safe(File(basePath, sf.path)) ?: ""
            val mainPattern = when(sf.lang) {
                "Java" -> "public static void main("
                "Kotlin" -> "fun main("
                else -> "func main("
            }
            val mainIdx = content.indexOf(mainPattern)
            val snippet = if (mainIdx >= 0) {
                val before = content.substring(0, mainIdx).count { it == '\n' }
                val allLines = content.lines()
                allLines.subList(maxOf(0, before), minOf(allLines.size, before + 12)).joinToString("\n")
            } else ""
            val fileName = sf.path.substringAfterLast("/").substringBefore(".")
            val className = sf.classes.firstOrNull() ?: fileName
            EntryPointInfo(className, sf.path, snippet)
        }
        entries.forEach { addEvidence(session, EvidenceType.ENTRY_POINT, it.file, EvidenceStatus.FOUND) }

        // Config files
        val cfgNames = listOf("compile.bat","build.gradle","build.gradle.kts","pom.xml",
            "package.json","Cargo.toml","go.mod","Makefile","settings.gradle.kts",".gitignore","MANIFEST.MF")
        val cfgs = cfgNames.mapNotNull { n ->
            val f = File(basePath, n)
            if (f.exists()) {
                val c = safe(f) ?: return@mapNotNull null
                addEvidence(session, EvidenceType.CONFIG_FILE, n, EvidenceStatus.FOUND)
                ConfigInfo(n, c.lines().size, c.take(3000))
            } else null
        }

        // Scripts
        val scriptExts = setOf("bat","ps1","sh","cmd")
        val scripts = base.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in scriptExts }
            ?.sortedByDescending { it.length() }?.take(8)
            ?.mapNotNull { f ->
                val c = safe(f) ?: return@mapNotNull null
                val cmds = c.lines().filter { l ->
                    val t = l.trim().lowercase()
                    t.startsWith("javac") || t.startsWith("java ") || t.startsWith("jar ") ||
                    t.startsWith("npm ") || t.startsWith("python") || t.startsWith("winget") ||
                    t.startsWith("choco") || t.startsWith("gradle") || t.startsWith("mvn ")
                }.map { it.trim() }
                addEvidence(session, EvidenceType.SCRIPT, f.name, EvidenceStatus.FOUND)
                ScriptInfo(f.name, c.lines().size, f.length(), cmds)
            } ?: emptyList()

        // Build system
        val buildSystem = when {
            cfgs.any { it.name == "pom.xml" } -> "Maven"
            cfgs.any { it.name.startsWith("build.gradle") } -> "Gradle"
            cfgs.any { it.name == "package.json" } -> "npm/Node.js"
            cfgs.any { it.name == "Cargo.toml" } -> "Cargo"
            scripts.any { it.name.equals("compile.bat", true) } ->
                "Нет стандартной build-системы (Maven/Gradle/Ant). Сборка через compile.bat"
            else -> "Не обнаружена"
        }
        addEvidence(session, EvidenceType.BUILD_FILE, buildSystem,
            if (buildSystem != "Не обнаружена") EvidenceStatus.FOUND else EvidenceStatus.NOT_FOUND)

        val buildCmds = scripts.flatMap { it.commands }.distinct()

        // External deps
        val hasExternalDeps = allImports.any { !it.startsWith("import java.") && !it.startsWith("import javax.") &&
                !it.startsWith("import kotlin") && !it.startsWith("import static") }
        val externalDeps = if (hasExternalDeps) "Есть внешние зависимости" else "Нет внешних библиотек — только стандартная библиотека"

        // License
        val licFile = File(basePath, "LICENSE")
        val licenseType = if (licFile.exists()) {
            val c = safe(licFile) ?: ""
            addEvidence(session, EvidenceType.LICENSE, "LICENSE", EvidenceStatus.FOUND)
            when { c.contains("MIT", true) -> "MIT"; c.contains("Apache", true) -> "Apache 2.0"
                c.contains("GPL", true) -> "GPL"; else -> "Custom" }
        } else null

        // Tests
        val testFiles = srcs.filter { it.path.lowercase().let { p -> p.contains("test") || p.contains("demo") } }
            .map { it.path }
        testFiles.forEach { addEvidence(session, EvidenceType.TEST_FILE, it, EvidenceStatus.FOUND) }

        // Target OS
        val targetOS = when {
            scripts.any { it.name.endsWith(".bat") || it.name.endsWith(".ps1") } -> "Windows"
            scripts.any { it.name.endsWith(".sh") } -> "Linux/macOS"
            else -> null
        }

        return ProjectSkeleton(name, fCount, dCount, treeLines.joinToString("\n"),
            readme, srcs, scripts, cfgs, entries, langs, buildSystem, buildCmds,
            uiFramework, externalDeps, allIPs.distinct(), allURLs.distinct(),
            licenseType, testFiles, targetOS)
    }

    // ==================== REPO TYPE CLASSIFICATION ====================

    private fun classifyRepoType(skeleton: ProjectSkeleton): RepoType {
        return when {
            skeleton.entryPoints.isNotEmpty() && skeleton.uiFramework != null -> RepoType.APPLICATION
            skeleton.entryPoints.isNotEmpty() -> RepoType.APPLICATION
            skeleton.buildSystem.contains("Gradle") && skeleton.configs.any { it.name == "settings.gradle.kts" } -> RepoType.PLUGIN
            skeleton.sourceFiles.isEmpty() && skeleton.configs.isNotEmpty() -> RepoType.DOCS_CONFIG_REPO
            skeleton.sourceFiles.size > 20 -> RepoType.MONOREPO
            else -> RepoType.UNKNOWN
        }
    }

    // ==================== EVIDENCE GATE ====================

    data class GateResult(val passed: Boolean, val foundCount: Int, val totalChecks: Int, val gaps: List<String>)

    private fun checkEvidenceGate(skeleton: ProjectSkeleton, session: SessionState): GateResult {
        val checks = mutableListOf<Pair<String, Boolean>>()

        checks.add("Source files found" to skeleton.sourceFiles.isNotEmpty())
        checks.add("Build system identified" to (skeleton.buildSystem != "Не обнаружена"))
        checks.add("Languages detected" to skeleton.languages.isNotEmpty())

        // Optional but tracked
        if (skeleton.readme != null) checks.add("README found" to true)
        if (skeleton.entryPoints.isNotEmpty()) checks.add("Entry points found" to true)

        val passed = checks.count { it.second }
        val gaps = checks.filter { !it.second }.map { it.first }

        // Gate passes if at least source files are found
        return GateResult(
            passed = skeleton.sourceFiles.isNotEmpty(),
            foundCount = passed,
            totalChecks = checks.size,
            gaps = gaps
        )
    }

    // ==================== DETERMINISTIC REPORT ====================

    private fun buildDeterministicReport(skeleton: ProjectSkeleton, repoType: RepoType, basePath: String): String {
        val sb = StringBuilder()

        // B. Tech stack
        sb.appendLine("B. Технологический стек")
        sb.appendLine("Аспект | Факт")
        sb.appendLine("Язык | ${skeleton.languages.joinToString(", ")}")
        if (skeleton.uiFramework != null) sb.appendLine("UI | ${skeleton.uiFramework}")
        val classCount = skeleton.sourceFiles.sumOf { it.classes.size }
        sb.appendLine("Классы | $classCount классов в ${skeleton.sourceFiles.size} файлах")
        sb.appendLine("Зависимости | ${skeleton.externalDeps}")
        sb.appendLine("Тип репозитория | $repoType")
        if (skeleton.targetOS != null) sb.appendLine("Целевая ОС | ${skeleton.targetOS}")
        sb.appendLine()

        // C. Build system
        sb.appendLine("C. Build system и конфигурация")
        sb.appendLine(skeleton.buildSystem)
        sb.appendLine()
        if (skeleton.buildCommands.isNotEmpty()) {
            sb.appendLine("Команды сборки:")
            skeleton.buildCommands.forEach { sb.appendLine("  $it") }
            sb.appendLine()
        }
        for (cfg in skeleton.configs) {
            sb.appendLine("${cfg.name} (${cfg.lines} строк):")
            sb.appendLine(cfg.content.lines().take(20).joinToString("\n"))
            sb.appendLine()
        }

        // D. Run/build/test
        sb.appendLine("D. Как запускать / собирать / тестировать")
        sb.appendLine()
        sb.appendLine("Запуск:")
        for (ep in skeleton.entryPoints) {
            sb.appendLine("  java -cp <jar> ${ep.className}  (файл: ${ep.file})")
        }
        sb.appendLine()
        sb.appendLine("Сборка:")
        if (skeleton.scripts.any { it.name.equals("compile.bat", true) }) sb.appendLine("  compile.bat")
        if (skeleton.buildCommands.isNotEmpty()) skeleton.buildCommands.forEach { sb.appendLine("  $it") }
        sb.appendLine()
        sb.appendLine("Скрипты:")
        for (s in skeleton.scripts) sb.appendLine("  ${s.name} (${s.lines} строк, ${fmtSize(s.bytes)})")
        sb.appendLine()
        sb.appendLine("Тестирование:")
        if (skeleton.testFiles.isNotEmpty()) {
            skeleton.testFiles.forEach { sb.appendLine("  $it") }
            sb.appendLine("  Формальный test framework не обнаружен.")
        } else sb.appendLine("  Тесты не обнаружены.")
        sb.appendLine()

        // E. Tree
        sb.appendLine("E. Основные папки и source roots")
        sb.appendLine("${skeleton.projectName}/ (${skeleton.totalFiles} файлов, ${skeleton.totalDirs} директорий)")
        sb.appendLine(skeleton.tree)
        sb.appendLine()

        // F. Entry points
        sb.appendLine("F. Важные entry points")
        if (skeleton.entryPoints.isEmpty()) {
            sb.appendLine("Точки входа (main()) не обнаружены.")
        } else {
            sb.appendLine("Найдено ${skeleton.entryPoints.size} точек входа:")
            sb.appendLine()
            sb.appendLine("Класс | Файл | Роль")
            for (ep in skeleton.entryPoints) sb.appendLine("${ep.className} | ${ep.file} | main()")
            sb.appendLine()
            sb.appendLine("Код entry points:")
            for (ep in skeleton.entryPoints) {
                sb.appendLine("--- ${ep.className} (${ep.file}) ---")
                sb.appendLine(ep.codeSnippet)
                sb.appendLine()
            }
        }

        // G. What to read
        sb.appendLine("G. Что стоит прочитать в первую очередь")
        val reads = mutableListOf<String>()
        if (skeleton.readme != null) reads.add("README.md — обзор проекта")
        skeleton.sourceFiles.sortedByDescending { it.lines }.take(3).forEach {
            reads.add("${it.path} — ${it.lines} строк, ${it.classes.joinToString(", ")}")
        }
        if (skeleton.licenseType != null) reads.add("LICENSE — ${skeleton.licenseType}")
        reads.forEachIndexed { i, s -> sb.appendLine("${i+1}. $s") }
        sb.appendLine()

        // H. Assessment
        sb.appendLine("H. Краткая общая оценка структуры проекта")
        sb.appendLine()
        sb.appendLine("Исходные файлы (${skeleton.sourceFiles.size}):")
        for (sf in skeleton.sourceFiles.sortedByDescending { it.lines }) {
            sb.appendLine("  ${sf.path}: ${sf.lang}, ${sf.lines} строк, ${fmtSize(sf.bytes)}")
            if (sf.classes.isNotEmpty()) sb.appendLine("    Классы: ${sf.classes.joinToString(", ")}")
            if (sf.methods.isNotEmpty()) sb.appendLine("    Методы: ${sf.methods.joinToString(", ")}")
            sb.appendLine("    main(): ${if (sf.hasMain) "✅ Да" else "❌ Нет"}")
        }
        sb.appendLine()
        if (skeleton.hardcodedIPs.isNotEmpty() || skeleton.hardcodedURLs.isNotEmpty()) {
            sb.appendLine("Hardcoded значения:")
            if (skeleton.hardcodedIPs.isNotEmpty()) sb.appendLine("  IP-адреса: ${skeleton.hardcodedIPs.joinToString(", ")}")
            if (skeleton.hardcodedURLs.isNotEmpty()) sb.appendLine("  URLs: ${skeleton.hardcodedURLs.take(5).joinToString(", ")}")
        }

        return sb.toString()
    }

    // ==================== LLM DESCRIPTION ====================

    private fun generateProjectDescription(skeleton: ProjectSkeleton): String {
        if (skeleton.readme == null) {
            return "Проект ${skeleton.projectName}. README не найден."
        }
        val client = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.example.localai.services.OllamaClientService::class.java)
        val settings = com.example.localai.settings.LocalAiSettingsState.instance
        val messages = listOf(
            mapOf("role" to "system", "content" to "You are a senior engineer. Based on the README below, write 2-3 sentences describing what this project is, what it does, and what platform it targets. Write in Russian. Be factual."),
            mapOf("role" to "user", "content" to skeleton.readme.take(2000))
        )
        return try {
            client.chat(settings.defaultModel, messages, formatJson = false)
        } catch (e: Exception) {
            "Проект ${skeleton.projectName} (описание: ошибка LLM — ${e.message})"
        }
    }

    // ==================== HELPERS ====================

    private fun trace(session: SessionState, type: TimelineEventType, desc: String) {
        session.timeline.add(TimelineEntry(type, desc))
    }

    private fun addEvidence(session: SessionState, type: EvidenceType, path: String, status: EvidenceStatus) {
        session.evidence.add(EvidenceRecord(type, path, status, "structural_scan"))
    }

    private fun emptyProjectSkeleton(name: String) = ProjectSkeleton(
        name, 0, 0, "", null, emptyList(), emptyList(), emptyList(), emptyList(),
        emptySet(), "Не обнаружена", emptyList(), null, "N/A",
        emptyList(), emptyList(), null, emptyList(), null
    )

    private fun buildTree(root: File, dir: File, out: MutableList<String>, depth: Int, counter: (Boolean) -> Unit) {
        if (out.size >= 200 || depth > 5) return
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for ((i, child) in children.withIndex()) {
            if (out.size >= 200) return
            if (child.name.startsWith(".") && child.isDirectory) continue
            val indent = "│   ".repeat(depth)
            val connector = if (i == children.lastIndex) "└── " else "├── "
            if (child.isDirectory) {
                counter(true); out.add("$indent$connector${child.name}/")
                buildTree(root, child, out, depth + 1, counter)
            } else {
                counter(false); out.add("$indent$connector${child.name} (${fmtSize(child.length())})")
            }
        }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes > 1024*1024 -> "${bytes/(1024*1024)} MB"
        bytes > 1024 -> "${bytes/1024} KB"
        else -> "$bytes bytes"
    }

    private fun safe(f: File): String? = try {
        if (f.length() > 500_000) null else f.readText(Charsets.UTF_8)
    } catch (_: Exception) { null }
}
