package com.example.localai.retrieval

import com.example.localai.model.EvidenceRecord
import com.example.localai.model.EvidenceStatus
import com.example.localai.model.EvidenceType
import com.example.localai.state.SessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Project-level service for deterministic structural scanning of repositories.
 *
 * Walks a project directory, collects source files, scripts, configs,
 * entry points, and hardcoded values. Produces a ProjectSkeleton
 * containing all verified facts about the project.
 *
 * No LLM calls — everything is deterministic.
 */
@Service(Service.Level.PROJECT)
class StructuralScanService(private val project: Project) {

    // ==================== DATA CLASSES ====================

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

    // ==================== SCAN ====================

    fun scan(basePath: String, session: SessionState): ProjectSkeleton {
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

    // ==================== HELPERS ====================

    fun emptyProjectSkeleton(name: String) = ProjectSkeleton(
        name, 0, 0, "", null, emptyList(), emptyList(), emptyList(), emptyList(),
        emptySet(), "Не обнаружена", emptyList(), null, "N/A",
        emptyList(), emptyList(), null, emptyList(), null
    )

    private fun addEvidence(session: SessionState, type: EvidenceType, path: String, status: EvidenceStatus) {
        session.evidence.add(EvidenceRecord(type, path, status, "structural_scan"))
    }

    fun buildTree(root: File, dir: File, out: MutableList<String>, depth: Int, counter: (Boolean) -> Unit) {
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

    fun fmtSize(bytes: Long): String = when {
        bytes > 1024*1024 -> "${bytes/(1024*1024)} MB"
        bytes > 1024 -> "${bytes/1024} KB"
        else -> "$bytes bytes"
    }

    fun safe(f: File): String? = try {
        if (f.length() > 500_000) null else f.readText(Charsets.UTF_8)
    } catch (_: Exception) { null }
}
