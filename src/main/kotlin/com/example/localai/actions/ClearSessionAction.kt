package com.example.localai.actions

import com.example.localai.services.ChatSessionService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Toolbar action: clears the current session (chat history, evidence, timeline).
 */
class ClearSessionAction : AnAction("Clear Session", "Clear chat history and reset session state", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val chatSession = project.getService(ChatSessionService::class.java)
        chatSession.clearSession()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
