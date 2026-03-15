package com.example.localai.orchestration

import com.example.localai.model.TargetResolutionResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Resolves the primary analysis target vs the host project.
 *
 * Key rule: the host IDE project is not necessarily the analysis target.
 * If user specifies an external path, that becomes PRIMARY TARGET.
 */
@Service(Service.Level.PROJECT)
class TargetResolutionService(private val project: Project) {

    /**
     * Resolve target from the user prompt and IDE context.
     */
    fun resolve(userPrompt: String): TargetResolutionResult {
        val hostProject = project.basePath ?: "unknown"

        // Check if user explicitly references a path
        val explicitPath = extractExplicitPath(userPrompt)

        return if (explicitPath != null) {
            TargetResolutionResult(
                primaryTarget = explicitPath,
                hostProject = hostProject,
                confidence = 0.9,
                resolutionReason = "Explicit path found in user prompt: $explicitPath"
            )
        } else {
            // Default: analyze the current project
            val openFiles = getOpenFiles()
            TargetResolutionResult(
                primaryTarget = hostProject,
                hostProject = hostProject,
                references = openFiles,
                confidence = 1.0,
                resolutionReason = "No explicit path specified — using current project as target"
            )
        }
    }

    private fun extractExplicitPath(prompt: String): String? {
        // Strategy: look for absolute paths that start with known filesystem roots
        // AND validate they exist on disk. This prevents matching random text with slashes.

        // Known filesystem root prefixes (Unix/macOS)
        val rootPrefixes = listOf("/Users/", "/home/", "/tmp/", "/opt/", "/var/", "/etc/",
            "/Applications/", "/Library/", "/Volumes/", "/mnt/", "/srv/", "/root/")

        for (prefix in rootPrefixes) {
            val idx = prompt.indexOf(prefix)
            if (idx < 0) continue

            // Extract the path — take chars until we hit a newline, or certain stop chars
            val remaining = prompt.substring(idx)
            val pathEnd = remaining.indexOfFirst { it == '\n' || it == '\r' || it == '"' || it == '\'' || it == '>' || it == '<' }
            val rawPath = if (pathEnd > 0) remaining.substring(0, pathEnd).trim() else remaining.trim()

            // Clean: remove trailing punctuation
            val cleaned = rawPath.trimEnd('.', ',', ';', ':', '!', '?', ')')

            // Reject if it contains " / " (space-slash-space = always natural language)
            if (cleaned.contains(" / ")) continue

            // Validate the path exists on disk
            val file = java.io.File(cleaned)
            if (file.exists()) {
                return cleaned
            }

            // Try progressively shorter paths (the prompt may have words after the path)
            val segments = cleaned.split("/").filter { it.isNotEmpty() }
            for (i in segments.size downTo 2) {
                val candidate = "/" + segments.subList(0, i).joinToString("/")
                if (java.io.File(candidate).exists()) {
                    return candidate
                }
            }
        }

        return null
    }

    private fun getOpenFiles(): List<String> {
        return try {
            ReadAction.compute<List<String>, Exception> {
                val fem = FileEditorManager.getInstance(project)
                fem.openFiles.map { it.path }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
