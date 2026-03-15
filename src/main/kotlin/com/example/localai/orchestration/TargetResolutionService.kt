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
        // Match absolute paths in the prompt
        val pathPattern = Regex("""(/[\w\s\-./\\]+(?:/[\w\s\-./\\]+)+)""")
        val match = pathPattern.find(prompt) ?: return null
        val path = match.value.trim()

        // Only return if it looks like a real path (has at least 2 segments)
        return if (path.count { it == '/' } >= 2) path else null
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
