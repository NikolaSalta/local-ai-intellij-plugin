package com.example.localai.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.math.max
import kotlin.math.min

/**
 * Project-level service that collects IDE context for AI conversations.
 *
 * Gathers: current file, selection, editor fragment, open files, project root.
 * All IntelliJ API access is wrapped in ReadAction for thread safety.
 */
@Service(Service.Level.PROJECT)
class IdeContextService(private val project: Project) {

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
                    val doc = ed.document
                    val offset = ed.caretModel.offset
                    val line = doc.getLineNumber(offset)
                    val startLine = max(0, line - FRAGMENT_LINES_RADIUS)
                    val endLine = min(doc.lineCount - 1, line + FRAGMENT_LINES_RADIUS)
                    val startOffset = doc.getLineStartOffset(startLine)
                    val endOffset = doc.getLineEndOffset(endLine)
                    doc.text.substring(startOffset, endOffset).take(MAX_FRAGMENT_CHARS)
                }

                val openFileHints = editorManager.openFiles
                    .take(MAX_OPEN_FILES)
                    .map { vf ->
                        if (projectRoot != null && vf.path.startsWith(projectRoot)) {
                            vf.path.removePrefix(projectRoot).removePrefix("/")
                        } else vf.path
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
}
