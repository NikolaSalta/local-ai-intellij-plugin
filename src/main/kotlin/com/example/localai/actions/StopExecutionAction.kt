package com.example.localai.actions

import com.example.localai.services.ChatSessionService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Toolbar action: stops the current pipeline execution.
 */
class StopExecutionAction : AnAction("Stop Execution", "Stop the current AI pipeline execution", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val chatSession = project.getService(ChatSessionService::class.java)
        chatSession.stopExecution()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = if (project != null) {
            val chatSession = project.getService(ChatSessionService::class.java)
            chatSession.isBusy
        } else false
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
