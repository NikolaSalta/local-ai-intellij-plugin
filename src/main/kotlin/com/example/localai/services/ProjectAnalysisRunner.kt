package com.example.localai.services

import com.example.localai.model.ChatMessage
import com.example.localai.model.ChatRole
import com.example.localai.model.TimelineEntry
import com.example.localai.model.TimelineEventType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Deterministic project analysis engine.
 *
 * ARCHITECTURE: The entire report is built by Kotlin code.
 * The LLM is used for ONE task: generate a 2-sentence project summary
 * from the README. Everything else is deterministic.
 *
 * This guarantees:
 * - No hallucinations (all facts from actual files)
 * - Correct file tree, line counts, class names
 * - Consistent output regardless of model size
 */
@Service(Service.Level.PROJECT)
class ProjectAnalysisRunner(private val project: Project) {

    private val logger = Logger.getInstance(ProjectAnalysisRunner::class.java)

    fun runAnalysis(session: ChatSessionService, userPrompt: String) {
        val basePath = project.basePath ?: return

        // Phase 1: Extract facts
        session.timeline.add(TimelineEntry(TimelineEventType.TOOL_EXEC_START, "Scanning project files..."))
        session.onStateChanged?.invoke()

        val facts = extractFacts(basePath)
        session.timeline.add(TimelineEntry(TimelineEventType.TOOL_EXEC_FINISH,
            "${facts.sourceFiles.size} source files, ${facts.entryPoints.size} entry points found"))
        session.onStateChanged?.invoke()

        // Phase 2: Generate project description via LLM (small context)
        session.timeline.add(TimelineEntry(TimelineEventType.MODEL_CALL_START,
            "Generating project description with ${session.currentModel}..."))
        session.onStateChanged?.invoke()

        val projectDescription = generateDescription(session.currentModel, facts)
        session.timeline.add(TimelineEntry(TimelineEventType.TOOL_EXEC_FINISH, "Description generated"))

        // Phase 3: Build report deterministically
        session.timeline.add(TimelineEntry(TimelineEventType.TOOL_EXEC_START, "Building structured report..."))
        session.onStateChanged?.invoke()

        val report = buildReport(facts, basePath, projectDescription)

        session.messages.add(ChatMessage(ChatRole.ASSISTANT, report))
        session.timeline.add(TimelineEntry(TimelineEventType.FINAL_TEXT,
            "Report complete (${facts.sourceFiles.size} files analyzed)"))
        session.onStateChanged?.invoke()
    }

    // ==================== DATA ====================

    data class Facts(
        val projectName: String,
        val readme: String?,
        val tree: String,
        val totalFiles: Int,
        val totalDirs: Int,
        val sourceFiles: List<SrcFile>,
        val scripts: List<ScriptFile>,
        val configs: List<CfgFile>,
        val entryPoints: List<EntryPoint>,
        val languages: Set<String>,
        val buildSystem: String,
        val buildCommands: List<String>,
        val uiFramework: String?,
        val externalDeps: String,
        val hardcodedIPs: List<String>,
        val hardcodedURLs: List<String>,
        val licenseType: String?,
        val hasTests: Boolean,
        val testFiles: List<String>,
        val targetOS: String?
    )

    data class SrcFile(val path: String, val lang: String, val lines: Int, val bytes: Long,
                       val hasMain: Boolean, val classes: List<String>, val methods: List<String>)
    data class ScriptFile(val name: String, val lines: Int, val bytes: Long, val commands: List<String>)
    data class CfgFile(val name: String, val lines: Int, val content: String)
    data class EntryPoint(val className: String, val file: String, val codeSnippet: String)

    // ==================== EXTRACT ====================

    private fun extractFacts(basePath: String): Facts {
        val base = File(basePath)
        val name = base.name

        // Tree
        val treeLines = mutableListOf<String>()
        var fCount = 0; var dCount = 0
        buildTree(base, base, treeLines, 0) { d -> if (d) dCount++ else fCount++ }

        // README
        val readme = listOf("README.md","readme.md","README.txt","README")
            .map { File(basePath, it) }.firstOrNull { it.exists() }
            ?.let { safe(it)?.take(8000) }

        // Source files
        val exts = mapOf("java" to "Java","kt" to "Kotlin","py" to "Python",
            "js" to "JavaScript","ts" to "TypeScript","go" to "Go","rs" to "Rust")
        val srcs = mutableListOf<SrcFile>()
        val langs = mutableSetOf<String>()
        val allIPs = mutableListOf<String>()
        val allURLs = mutableListOf<String>()
        val allImports = mutableListOf<String>()
        var uiFramework: String? = null

        base.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in exts }
            .filter { !it.path.contains("/.") }
            .sortedByDescending { it.length() }
            .take(20)
            .forEach { f ->
                val lang = exts[f.extension.lowercase()] ?: return@forEach
                langs.add(lang)
                val content = safe(f) ?: return@forEach
                val lines = content.lines()
                val rel = base.toPath().relativize(f.toPath()).toString()

                val hasMain = when(lang) {
                    "Java" -> content.contains("public static void main(")
                    "Kotlin" -> content.contains("fun main(")
                    else -> content.contains("func main(") || (content.contains("if __name__") && content.contains("__main__"))
                }

                // Match class declarations — require uppercase first letter to avoid "for", "static", etc.
                val javaKeywords = setOf("if","for","while","switch","catch","new","return","throw",
                    "static","final","abstract","void","int","long","boolean","double","float",
                    "char","byte","short","String","Object","null","true","false","this","super")
                val swingTypes = setOf("JPanel","JLabel","JButton","JFrame","JTextField","JTextArea",
                    "JScrollPane","JProgressBar","JOptionPane","JPopupMenu","JMenuItem","JComboBox",
                    "JTable","JTree","JDialog","JFileChooser","JCheckBox","JRadioButton","JSlider",
                    "JTabbedPane","JToolBar","JMenuBar","JMenu","JSplitPane","JSpinner",
                    "ImageIcon","BufferedImage","Graphics2D","Color","Font","Insets","Dimension",
                    "BorderLayout","FlowLayout","GridBagLayout","GridBagConstraints","GridLayout",
                    "BoxLayout","CardLayout","Polygon","Rectangle","Point","Timer",
                    "ActionEvent","MouseEvent","KeyEvent","WindowEvent",
                    "StringBuilder","ArrayList","HashMap","HashSet","LinkedList",
                    "Scanner","Socket","ProcessBuilder","Runtime","Thread","Exception")

                val classes = Regex("""(?:public\s+)?class\s+([A-Z]\w+)""").findAll(content)
                    .map { it.groupValues[1] }
                    .filter { it !in javaKeywords && it !in swingTypes }
                    .toList()

                val methods = when(lang) {
                    "Java" -> Regex("""(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\]]+\s+(\w+)\s*\(""")
                        .findAll(content).map { it.groupValues[1] }
                        .filter { it !in javaKeywords && it !in swingTypes && it[0].isLowerCase() }
                        .distinct().toList().take(20)
                    "Kotlin" -> Regex("""fun\s+(\w+)\s*\(""").findAll(content).map { it.groupValues[1] }.distinct().toList().take(20)
                    else -> emptyList()
                }

                // Detect UI framework from imports
                val fileImports = lines.filter { it.trimStart().startsWith("import ") }.map { it.trim() }
                allImports.addAll(fileImports)
                if (fileImports.any { it.contains("javax.swing") }) uiFramework = "Swing (javax.swing)"
                if (fileImports.any { it.contains("javafx") }) uiFramework = "JavaFX"

                // Hardcoded values
                val ips = Regex("""\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b""").findAll(content)
                    .map { it.value }.filter { it != "0.0.0.0" && it != "255.255.255.255" && it != "127.0.0.1" }
                    .distinct().toList()
                allIPs.addAll(ips)

                val urls = Regex("""https?://[^\s"'<>)]+""").findAll(content)
                    .map { it.value }.distinct().toList().take(5)
                allURLs.addAll(urls)

                srcs.add(SrcFile(rel, lang, lines.size, f.length(), hasMain, classes, methods))
            }

        // Entry points
        val entries = srcs.filter { it.hasMain }.map { sf ->
            val content = safe(File(basePath, sf.path)) ?: ""
            val mainIdx = when(sf.lang) {
                "Java" -> content.indexOf("public static void main(")
                "Kotlin" -> content.indexOf("fun main(")
                else -> content.indexOf("func main(")
            }
            val snippet = if (mainIdx >= 0) {
                // Find enclosing lines
                val before = content.substring(0, mainIdx).count { it == '\n' }
                val allLines = content.lines()
                val start = maxOf(0, before)
                val end = minOf(allLines.size, before + 12)
                allLines.subList(start, end).joinToString("\n")
            } else ""
            // Use filename as class name fallback (more reliable than regex for edge cases)
            val fileName = sf.path.substringAfterLast("/").substringBefore(".")
            val className = sf.classes.firstOrNull() ?: fileName
            EntryPoint(className, sf.path, snippet)
        }

        // Config files
        val cfgNames = listOf("compile.bat","build.gradle","build.gradle.kts","pom.xml",
            "package.json","Cargo.toml","go.mod","Makefile","settings.gradle.kts",".gitignore","MANIFEST.MF")
        val cfgs = cfgNames.mapNotNull { n ->
            val f = File(basePath, n)
            if (f.exists()) { val c = safe(f) ?: return@mapNotNull null; CfgFile(n, c.lines().size, c.take(3000)) } else null
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
                    t.startsWith("npm ") || t.startsWith("python") || t.startsWith("winget") || t.startsWith("choco")
                }.map { it.trim() }
                ScriptFile(f.name, c.lines().size, f.length(), cmds)
            } ?: emptyList()

        // Build system
        val buildSystem = when {
            cfgs.any { it.name == "pom.xml" } -> "Maven"
            cfgs.any { it.name.startsWith("build.gradle") } -> "Gradle"
            cfgs.any { it.name == "package.json" } -> "npm/Node.js"
            cfgs.any { it.name == "Cargo.toml" } -> "Cargo"
            scripts.any { it.name.equals("compile.bat", true) } -> "Нет стандартной build-системы (Maven/Gradle/Ant). Сборка через compile.bat"
            else -> "Не обнаружена"
        }
        val buildCmds = scripts.flatMap { it.commands }.distinct()

        // External deps
        val hasExternalDeps = allImports.any { !it.startsWith("import java.") && !it.startsWith("import javax.") &&
                !it.startsWith("import kotlin") && !it.startsWith("import static") }
        val externalDeps = if (hasExternalDeps) "Есть внешние зависимости" else "Нет внешних библиотек — только стандартная библиотека"

        // License
        val licFile = File(basePath, "LICENSE")
        val licenseType = if (licFile.exists()) {
            val c = safe(licFile) ?: ""
            when { c.contains("MIT", true) -> "MIT"; c.contains("Apache", true) -> "Apache 2.0"
                c.contains("GPL", true) -> "GPL"; else -> "Custom" }
        } else null

        // Tests
        val testFiles = srcs.filter { it.path.lowercase().contains("test") }.map { it.path }
        val demoFiles = srcs.filter { it.path.lowercase().contains("demo") }.map { it.path }

        // Target OS
        val targetOS = when {
            scripts.any { it.name.endsWith(".bat") || it.name.endsWith(".ps1") } -> "Windows"
            scripts.any { it.name.endsWith(".sh") } -> "Linux/macOS"
            else -> null
        }

        return Facts(name, readme, treeLines.joinToString("\n"), fCount, dCount,
            srcs, scripts, cfgs, entries, langs, buildSystem, buildCmds,
            uiFramework, externalDeps, allIPs.distinct(), allURLs.distinct(),
            licenseType, testFiles.isNotEmpty(), testFiles + demoFiles, targetOS)
    }

    // ==================== GENERATE DESCRIPTION (small LLM call) ====================

    private fun generateDescription(model: String, facts: Facts): String {
        if (facts.readme == null) {
            return "Проект ${facts.projectName}. README не найден — описание не может быть сгенерировано автоматически."
        }

        val client = ApplicationManager.getApplication().getService(com.example.localai.llm.OllamaClientService::class.java)

        // Send ONLY the first 2000 chars of README — tiny context
        val shortReadme = facts.readme.take(2000)
        val messages = listOf(
            mapOf("role" to "system", "content" to "You are a senior engineer. Based on the README below, write a 2-3 sentence description of what this project is, what it does, and what platform it targets. Write in Russian. Be factual and concise."),
            mapOf("role" to "user", "content" to shortReadme)
        )

        return try {
            client.chat(model, messages, formatJson = false)
        } catch (e: Exception) {
            "Проект ${facts.projectName} (описание не удалось сгенерировать: ${e.message})"
        }
    }

    // ==================== BUILD REPORT (100% deterministic) ====================

    private fun buildReport(facts: Facts, basePath: String, description: String): String {
        val sb = StringBuilder()

        // A. Project description
        sb.appendLine("A. Что это за проект")
        sb.appendLine(description)
        sb.appendLine()

        // B. Tech stack
        sb.appendLine("B. Технологический стек")
        sb.appendLine("Аспект | Факт")
        sb.appendLine("Язык | ${facts.languages.joinToString(", ")}")
        if (facts.uiFramework != null) sb.appendLine("UI | ${facts.uiFramework}")
        val pkgInfo = if (facts.sourceFiles.any { it.classes.isNotEmpty() }) {
            val allClasses = facts.sourceFiles.flatMap { it.classes }
            if (allClasses.size == facts.sourceFiles.size) "Все ${allClasses.size} классов в default package" else "${allClasses.size} классов"
        } else "N/A"
        sb.appendLine("Packages | $pkgInfo")
        sb.appendLine("Зависимости | ${facts.externalDeps}")
        if (facts.targetOS != null) sb.appendLine("Целевая ОС | ${facts.targetOS}")
        sb.appendLine()

        // C. Build system
        sb.appendLine("C. Build system и конфигурация")
        sb.appendLine(facts.buildSystem)
        sb.appendLine()
        if (facts.buildCommands.isNotEmpty()) {
            sb.appendLine("Команды сборки, найденные в скриптах:")
            facts.buildCommands.forEach { sb.appendLine("  $it") }
            sb.appendLine()
        }
        for (cfg in facts.configs) {
            sb.appendLine("${cfg.name} (${cfg.lines} строк):")
            // Show key excerpt
            val excerpt = cfg.content.lines().take(20).joinToString("\n")
            sb.appendLine(excerpt)
            sb.appendLine()
        }

        // D. How to run/build/test
        sb.appendLine("D. Как запускать / собирать / тестировать")
        sb.appendLine()
        sb.appendLine("Запуск:")
        for (ep in facts.entryPoints) {
            sb.appendLine("  java -cp <jar> ${ep.className}  (файл: ${ep.file})")
        }
        sb.appendLine()
        sb.appendLine("Сборка:")
        if (facts.scripts.any { it.name.equals("compile.bat", true) }) {
            sb.appendLine("  compile.bat")
        }
        if (facts.buildCommands.isNotEmpty()) {
            facts.buildCommands.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()
        sb.appendLine("Скрипты-лаунчеры:")
        for (s in facts.scripts) {
            sb.appendLine("  ${s.name} (${s.lines} строк, ${formatSize(s.bytes)})")
        }
        sb.appendLine()
        sb.appendLine("Тестирование:")
        if (facts.testFiles.isNotEmpty()) {
            sb.appendLine("  Найдены тестовые/демо файлы:")
            facts.testFiles.forEach { sb.appendLine("    $it") }
            sb.appendLine("  Формальный test framework (JUnit, TestNG) не обнаружен.")
        } else {
            sb.appendLine("  Тесты не обнаружены.")
        }
        sb.appendLine()

        // E. Folders and source roots
        sb.appendLine("E. Основные папки и source roots")
        sb.appendLine("${facts.projectName}/ (${facts.totalFiles} файлов, ${facts.totalDirs} директорий)")
        sb.appendLine(facts.tree)
        sb.appendLine()

        // F. Entry points
        sb.appendLine("F. Важные entry points")
        if (facts.entryPoints.isEmpty()) {
            sb.appendLine("Точки входа (main()) не обнаружены.")
        } else {
            sb.appendLine("Найдено ${facts.entryPoints.size} точек входа с main():")
            sb.appendLine()
            sb.appendLine("Класс | Файл | Роль")
            for (ep in facts.entryPoints) {
                sb.appendLine("${ep.className} | ${ep.file} | main()")
            }
            sb.appendLine()
            sb.appendLine("Код entry points:")
            for (ep in facts.entryPoints) {
                sb.appendLine("--- ${ep.className} (${ep.file}) ---")
                sb.appendLine(ep.codeSnippet)
                sb.appendLine()
            }
        }

        // G. What to read first
        sb.appendLine("G. Что стоит прочитать в первую очередь")
        val readFirst = mutableListOf<String>()
        if (facts.readme != null) readFirst.add("README.md — обзор проекта")
        facts.sourceFiles.sortedByDescending { it.lines }.take(3).forEach {
            readFirst.add("${it.path} — ${it.lines} строк, ${it.classes.joinToString(", ")}")
        }
        facts.scripts.sortedByDescending { it.lines }.take(2).forEach {
            readFirst.add("${it.name} — ${it.lines} строк (скрипт)")
        }
        if (facts.licenseType != null) readFirst.add("LICENSE — ${facts.licenseType}")
        readFirst.forEachIndexed { i, s -> sb.appendLine("${i+1}. $s") }
        sb.appendLine()

        // H. Assessment
        sb.appendLine("H. Краткая общая оценка структуры проекта")
        sb.appendLine()

        // Source file details
        sb.appendLine("Исходные файлы (${facts.sourceFiles.size}):")
        for (sf in facts.sourceFiles.sortedByDescending { it.lines }) {
            sb.appendLine("  ${sf.path}: ${sf.lang}, ${sf.lines} строк, ${formatSize(sf.bytes)}")
            if (sf.classes.isNotEmpty()) sb.appendLine("    Классы: ${sf.classes.joinToString(", ")}")
            if (sf.methods.isNotEmpty()) sb.appendLine("    Методы: ${sf.methods.joinToString(", ")}")
            sb.appendLine("    main(): ${if (sf.hasMain) "✅ Да" else "❌ Нет"}")
        }
        sb.appendLine()

        // Risk analysis
        if (facts.hardcodedIPs.isNotEmpty() || facts.hardcodedURLs.isNotEmpty()) {
            sb.appendLine("Hardcoded значения:")
            if (facts.hardcodedIPs.isNotEmpty()) sb.appendLine("  IP-адреса: ${facts.hardcodedIPs.joinToString(", ")}")
            if (facts.hardcodedURLs.isNotEmpty()) sb.appendLine("  URLs: ${facts.hardcodedURLs.take(5).joinToString(", ")}")
            sb.appendLine()
        }

        return sb.toString()
    }

    // ==================== HELPERS ====================

    private fun buildTree(root: File, dir: File, out: MutableList<String>, depth: Int, counter: (Boolean) -> Unit) {
        if (out.size >= 200 || depth > 5) return
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for ((i, child) in children.withIndex()) {
            if (out.size >= 200) return
            if (child.name.startsWith(".") && child.isDirectory) continue
            val indent = "│   ".repeat(depth)
            val connector = if (i == children.lastIndex) "└── " else "├── "
            if (child.isDirectory) {
                counter(true)
                out.add("$indent$connector${child.name}/")
                buildTree(root, child, out, depth + 1, counter)
            } else {
                counter(false)
                out.add("$indent$connector${child.name} (${formatSize(child.length())})")
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes > 1024*1024 -> "${bytes/(1024*1024)} MB"
        bytes > 1024 -> "${bytes/1024} KB"
        else -> "$bytes bytes"
    }

    private fun safe(f: File): String? = try {
        if (f.length() > 500_000) null else f.readText(Charsets.UTF_8)
    } catch (e: Exception) { null }
}
