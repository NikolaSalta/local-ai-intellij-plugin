package com.example.localai.services

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.math.max
import kotlin.math.min

/**
 * Project-level service that collects IDE context for AI conversations.
 * Constructor takes Project only (IntelliJ project-service rule).
 */
class ContextAssemblerService(private val project: Project) {

    companion object {
        private const val MAX_SELECTION_CHARS = 2000
        private const val MAX_FRAGMENT_CHARS = 5000
        private const val MAX_OPEN_FILES = 20
        private const val FRAGMENT_LINES_RADIUS = 50
    }

    data class IdeContext(
        val projectRoot: String?,
        val currentFilePath: String?,
        val selectedText: String?,
        val editorFragment: String?,
        val openFileHints: List<String>,
        val embeddingModel: String
    )

    /**
     * Assembles the current IDE context into a structured object.
     * Wraps all IntelliJ API access in ReadAction to be safe from any thread.
     */
    fun assembleContext(): IdeContext {
        val settings = com.example.localai.settings.LocalAiSettingsState.instance
        val projectRoot = project.basePath

        return try {
            ReadAction.compute<IdeContext, Exception> {
                val editorManager = FileEditorManager.getInstance(project)
                val editor: Editor? = editorManager.selectedTextEditor
                val currentFile: VirtualFile? = editorManager.selectedFiles.firstOrNull()

                val currentFilePath = currentFile?.let {
                    if (projectRoot != null && it.path.startsWith(projectRoot)) {
                        it.path.removePrefix(projectRoot).removePrefix("/")
                    } else {
                        it.path
                    }
                }

                val selectedText = editor?.selectionModel?.selectedText?.take(MAX_SELECTION_CHARS)

                val editorFragment = editor?.let { ed ->
                    val document = ed.document
                    val caretLine = ed.caretModel.logicalPosition.line
                    val startLine = max(0, caretLine - FRAGMENT_LINES_RADIUS)
                    val endLine = min(document.lineCount - 1, caretLine + FRAGMENT_LINES_RADIUS)
                    if (startLine <= endLine && document.lineCount > 0) {
                        val startOffset = document.getLineStartOffset(startLine)
                        val endOffset = document.getLineEndOffset(endLine)
                        document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
                            .take(MAX_FRAGMENT_CHARS)
                    } else {
                        null
                    }
                }

                val openFileHints = editorManager.openFiles
                    .take(MAX_OPEN_FILES)
                    .map { file ->
                        if (projectRoot != null && file.path.startsWith(projectRoot)) {
                            file.path.removePrefix(projectRoot).removePrefix("/")
                        } else {
                            file.name
                        }
                    }

                IdeContext(
                    projectRoot = projectRoot,
                    currentFilePath = currentFilePath,
                    selectedText = selectedText,
                    editorFragment = editorFragment,
                    openFileHints = openFileHints,
                    embeddingModel = settings.embeddingModel
                )
            }
        } catch (e: Exception) {
            // Fallback: return minimal context if ReadAction fails
            IdeContext(
                projectRoot = projectRoot,
                currentFilePath = null,
                selectedText = null,
                editorFragment = null,
                openFileHints = emptyList(),
                embeddingModel = settings.embeddingModel
            )
        }
    }

    /**
     * Formats IDE context into a system message string for the LLM.
     */
    fun formatContextAsSystemMessage(context: IdeContext): String {
        val parts = mutableListOf<String>()

        parts.add("You are a helpful AI coding assistant. You have access to the user's IDE context.")

        context.projectRoot?.let {
            parts.add("Project root: $it")
        }

        context.currentFilePath?.let {
            parts.add("Currently open file: $it")
        }

        context.selectedText?.let {
            parts.add("Selected text:\n```\n$it\n```")
        }

        context.editorFragment?.let {
            parts.add("Editor context (around cursor):\n```\n$it\n```")
        }

        if (context.openFileHints.isNotEmpty()) {
            parts.add("Open files: ${context.openFileHints.joinToString(", ")}")
        }

        return parts.joinToString("\n\n")
    }
}
