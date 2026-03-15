package com.example.localai.orchestration

import com.example.localai.model.*
import com.example.localai.retrieval.RepoTypeClassifierService
import com.example.localai.retrieval.StructuralScanService
import com.example.localai.retrieval.StructuralScanService.ProjectSkeleton
import com.example.localai.state.ExecutionPhase
import com.example.localai.state.PipelineStage
import com.example.localai.state.SessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Slim pipeline coordinator — delegates scan/classify to retrieval services.
 *
 * Pipeline stages:
 * 1. Structural scan (→ StructuralScanService)
 * 2. Repo type classification (→ RepoTypeClassifierService)
 * 3. Evidence gate check
 * 4. Deterministic report
 * 5. Task-aware LLM synthesis (or description-only for analysis tasks)
 * 6. Final assembly
 */
@Service(Service.Level.PROJECT)
class PipelineCoordinatorService(private val project: Project) {

    private val logger = Logger.getInstance(PipelineCoordinatorService::class.java)

    /**
     * Execute the full pipeline for the given interpretation.
     */
    fun executePipeline(session: SessionState, interpretation: RequestInterpretation): String {
        val basePath = interpretation.primaryTarget
        val scanService = project.getService(StructuralScanService::class.java)
        val classifierService = project.getService(RepoTypeClassifierService::class.java)

        // Stage 1: Structural Scan
        if (session.shouldStop()) return "[Stopped]"
        session.executionPhase = ExecutionPhase.RUNNING_STRUCTURAL_SCAN
        trace(session, TimelineEventType.STRUCTURAL_SCAN_START, "Scanning project: $basePath")

        val skeleton = scanService.scan(basePath, session)
        session.pipelineStage = PipelineStage.STRUCTURAL_SCAN_DONE
        trace(session, TimelineEventType.STRUCTURAL_SCAN_FINISH,
            "${skeleton.totalFiles} files, ${skeleton.sourceFiles.size} source files found")

        // Stage 2: Repo Type Detection
        if (session.shouldStop()) return "[Stopped]"
        val repoType = classifierService.classify(skeleton)
        session.repoType = repoType
        session.pipelineStage = PipelineStage.REPO_TYPE_DETECTED
        trace(session, TimelineEventType.REPO_TYPE_DETECTED, "Repo type: $repoType")

        // Stage 3: Evidence Gate
        if (session.shouldStop()) return "[Stopped]"
        session.executionPhase = ExecutionPhase.CHECKING_EVIDENCE
        trace(session, TimelineEventType.EVIDENCE_GATE_CHECK, "Checking evidence sufficiency...")

        val gateResult = checkEvidenceGate(skeleton)
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

        val deterministicReport = buildDeterministicReport(skeleton, repoType, scanService)

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
            llmResponse = generateProjectDescription(skeleton)
        } else {
            llmResponse = executeTaskAwareSynthesis(interpretation, skeleton, deterministicReport, session)
        }

        session.pipelineStage = PipelineStage.FINAL_SYNTHESIS_DONE
        trace(session, TimelineEventType.SYNTHESIS_FINISH, "LLM synthesis complete (${llmResponse.length} chars)")

        // Stage 7: Assemble final output
        session.executionPhase = ExecutionPhase.READY
        return assembleOutput(interpretation, session, repoType, gateResult, isAnalysisOnly,
            llmResponse, deterministicReport)
    }

    // ==================== OUTPUT ASSEMBLY ====================

    private fun assembleOutput(
        interpretation: RequestInterpretation,
        session: SessionState,
        repoType: RepoType,
        gateResult: GateResult,
        isAnalysisOnly: Boolean,
        llmResponse: String,
        deterministicReport: String
    ): String {
        val sb = StringBuilder()

        sb.appendLine("═══ ORCHESTRATION METADATA ═══")
        sb.appendLine()
        sb.appendLine("A. Primary Target: ${interpretation.primaryTarget}")
        sb.appendLine("B. Host Project: ${interpretation.hostProject}")
        sb.appendLine("C. Task Type: ${interpretation.taskType}")
        sb.appendLine("D. Execution Strategy: ${interpretation.executionStrategy}")
        sb.appendLine()

        sb.appendLine("═══ EVIDENCE ═══")
        sb.appendLine()
        val found = session.evidence.filter { it.status == EvidenceStatus.FOUND }
        val notFound = session.evidence.filter { it.status == EvidenceStatus.NOT_FOUND }
        sb.appendLine("I. Evidence Collected (${found.size}):")
        for (ev in found) { sb.appendLine("  ✅ ${ev.type}: ${ev.path}") }
        sb.appendLine()
        if (notFound.isNotEmpty()) {
            sb.appendLine("J. Missing Evidence (${notFound.size}):")
            for (ev in notFound) { sb.appendLine("  ❌ ${ev.type}: ${ev.path}") }
        } else {
            sb.appendLine("J. Missing Evidence: none")
        }
        sb.appendLine()

        sb.appendLine("K. Pipeline: ${session.pipelineStage}")
        sb.appendLine("L. Repo Type: $repoType")
        sb.appendLine("M. Evidence Gate: ${if (gateResult.passed) "PASSED (${gateResult.foundCount}/${gateResult.totalChecks})" else "FAILED — gaps: ${gateResult.gaps.joinToString(", ")}"}")
        sb.appendLine()

        if (isAnalysisOnly) {
            sb.appendLine("═══ DETERMINISTIC REPORT ═══")
            sb.appendLine()
            sb.appendLine("N. Что это за проект")
            sb.appendLine(llmResponse)
            sb.appendLine()
            sb.append(deterministicReport)
        } else {
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

    private fun executeTaskAwareSynthesis(
        interpretation: RequestInterpretation,
        skeleton: ProjectSkeleton,
        deterministicReport: String,
        session: SessionState
    ): String {
        val client = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.example.localai.llm.OllamaClientService::class.java)
        val settings = com.example.localai.settings.LocalAiSettingsState.instance

        val contextBlock = buildLlmContext(skeleton)
        val systemPrompt = buildSystemPrompt(interpretation)

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

    private fun buildLlmContext(skeleton: ProjectSkeleton): String {
        val sb = StringBuilder()
        sb.appendLine("Project: ${skeleton.projectName}")
        sb.appendLine("Languages: ${skeleton.languages.joinToString(", ")}")
        sb.appendLine("Build: ${skeleton.buildSystem}")
        if (skeleton.uiFramework != null) sb.appendLine("UI: ${skeleton.uiFramework}")
        sb.appendLine("Target OS: ${skeleton.targetOS ?: "Unknown"}")
        sb.appendLine("Source files: ${skeleton.sourceFiles.size}")
        sb.appendLine("Entry points: ${skeleton.entryPoints.size}")
        sb.appendLine()
        sb.appendLine("=== Class & Method Map ===")
        for (sf in skeleton.sourceFiles) {
            sb.appendLine("${sf.path} (${sf.lang}, ${sf.lines} lines)")
            if (sf.classes.isNotEmpty()) sb.appendLine("  Classes: ${sf.classes.joinToString(", ")}")
            if (sf.methods.isNotEmpty()) sb.appendLine("  Methods: ${sf.methods.joinToString(", ")}")
            sb.appendLine("  main(): ${sf.hasMain}")
        }
        sb.appendLine()
        if (skeleton.readme != null) {
            sb.appendLine("=== README (first 3000 chars) ===")
            sb.appendLine(skeleton.readme.take(3000))
            sb.appendLine()
        }
        sb.appendLine("=== Source Code Snippets ===")
        for (sf in skeleton.sourceFiles.sortedByDescending { it.lines }.take(3)) {
            val content = skeleton.entryPoints.find { it.file == sf.path }?.codeSnippet
            if (content != null && content.isNotEmpty()) {
                sb.appendLine("--- ${sf.path} (entry point) ---")
                sb.appendLine(content)
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    private fun buildSystemPrompt(interpretation: RequestInterpretation): String {
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

    // ==================== EVIDENCE GATE ====================

    data class GateResult(val passed: Boolean, val foundCount: Int, val totalChecks: Int, val gaps: List<String>)

    private fun checkEvidenceGate(skeleton: ProjectSkeleton): GateResult {
        val checks = mutableListOf<Pair<String, Boolean>>()
        checks.add("Source files found" to skeleton.sourceFiles.isNotEmpty())
        checks.add("Build system identified" to (skeleton.buildSystem != "Не обнаружена"))
        checks.add("Languages detected" to skeleton.languages.isNotEmpty())
        if (skeleton.readme != null) checks.add("README found" to true)
        if (skeleton.entryPoints.isNotEmpty()) checks.add("Entry points found" to true)

        val passed = checks.count { it.second }
        val gaps = checks.filter { !it.second }.map { it.first }

        return GateResult(
            passed = skeleton.sourceFiles.isNotEmpty(),
            foundCount = passed,
            totalChecks = checks.size,
            gaps = gaps
        )
    }

    // ==================== DETERMINISTIC REPORT ====================

    private fun buildDeterministicReport(
        skeleton: ProjectSkeleton,
        repoType: RepoType,
        scanService: StructuralScanService
    ): String {
        val sb = StringBuilder()

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
        for (s in skeleton.scripts) sb.appendLine("  ${s.name} (${s.lines} строк, ${scanService.fmtSize(s.bytes)})")
        sb.appendLine()
        sb.appendLine("Тестирование:")
        if (skeleton.testFiles.isNotEmpty()) {
            skeleton.testFiles.forEach { sb.appendLine("  $it") }
            sb.appendLine("  Формальный test framework не обнаружен.")
        } else sb.appendLine("  Тесты не обнаружены.")
        sb.appendLine()

        sb.appendLine("E. Основные папки и source roots")
        sb.appendLine("${skeleton.projectName}/ (${skeleton.totalFiles} файлов, ${skeleton.totalDirs} директорий)")
        sb.appendLine(skeleton.tree)
        sb.appendLine()

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

        sb.appendLine("G. Что стоит прочитать в первую очередь")
        val reads = mutableListOf<String>()
        if (skeleton.readme != null) reads.add("README.md — обзор проекта")
        skeleton.sourceFiles.sortedByDescending { it.lines }.take(3).forEach {
            reads.add("${it.path} — ${it.lines} строк, ${it.classes.joinToString(", ")}")
        }
        if (skeleton.licenseType != null) reads.add("LICENSE — ${skeleton.licenseType}")
        reads.forEachIndexed { i, s -> sb.appendLine("${i+1}. $s") }
        sb.appendLine()

        sb.appendLine("H. Краткая общая оценка структуры проекта")
        sb.appendLine()
        sb.appendLine("Исходные файлы (${skeleton.sourceFiles.size}):")
        for (sf in skeleton.sourceFiles.sortedByDescending { it.lines }) {
            sb.appendLine("  ${sf.path}: ${sf.lang}, ${sf.lines} строк, ${scanService.fmtSize(sf.bytes)}")
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
            .getService(com.example.localai.llm.OllamaClientService::class.java)
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
}
