package com.example.localai.services

import com.example.localai.model.ChatMessage
import com.example.localai.model.ChatMode
import com.example.localai.model.ChatRole
import com.example.localai.model.PlanResponse
import com.example.localai.model.TimelineEntry
import com.example.localai.model.TimelineEventType
import com.example.localai.orchestration.RequestOrchestratorService
import com.example.localai.settings.LocalAiSettingsState
import com.example.localai.state.SessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-level service managing chat state and delegating to the orchestrator.
 * Thin wrapper — all analysis logic lives in the orchestration layer.
 */
@Service(Service.Level.PROJECT)
class ChatSessionService(private val project: Project) {

    private val logger = Logger.getInstance(ChatSessionService::class.java)

    var currentMode: ChatMode = ChatMode.ASK
    val messages: MutableList<ChatMessage> = mutableListOf()
    val timeline: MutableList<TimelineEntry> = mutableListOf()
    var lastPlanResponse: PlanResponse? = null
    var currentModel: String = LocalAiSettingsState.instance.defaultModel
    var isBusy: Boolean = false
        private set

    /** Internal session state for the orchestration pipeline. */
    val sessionState = SessionState()

    /** Callback invoked when the conversation state changes (for UI updates). */
    var onStateChanged: (() -> Unit)? = null

    /**
     * Sends a user message and processes through the orchestration pipeline.
     * This runs synchronously — callers should invoke from a background thread.
     */
    fun sendMessage(userText: String) {
        if (isBusy) return
        isBusy = true
        sessionState.stopRequested = false
        notifyChanged()

        try {
            messages.add(ChatMessage(ChatRole.USER, userText))
            sessionState.messages.add(ChatMessage(ChatRole.USER, userText))

            // All modes delegate to the orchestrator
            timeline.add(TimelineEntry(TimelineEventType.REQUEST_PARSED,
                "Request received (mode: $currentMode)"))
            notifyChanged()

            val orchestrator = RequestOrchestratorService(project)
            val result = orchestrator.processRequest(userText, sessionState)

            // Sync timeline from sessionState to our timeline
            for (entry in sessionState.timeline) {
                if (entry !in timeline) timeline.add(entry)
            }

            messages.add(ChatMessage(ChatRole.ASSISTANT, result))
            timeline.add(TimelineEntry(TimelineEventType.FINAL_TEXT, "Response ready"))

        } catch (e: Exception) {
            logger.error("Error processing message", e)
            timeline.add(TimelineEntry(TimelineEventType.ERROR, "Error: ${e.message}"))
            messages.add(ChatMessage(ChatRole.ASSISTANT,
                "Error: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString().take(1500)}"))
        } finally {
            isBusy = false
            notifyChanged()
        }
    }

    /** Stop the current pipeline execution. */
    fun stopExecution() {
        sessionState.stopRequested = true
        timeline.add(TimelineEntry(TimelineEventType.STOP_REQUESTED, "Stop requested by user"))
        notifyChanged()
    }

    /** Clear the conversation and all state. */
    fun clearSession() {
        messages.clear()
        timeline.clear()
        lastPlanResponse = null
        sessionState.clear()
        notifyChanged()
    }

    fun notifyChanged() {
        onStateChanged?.invoke()
    }

    companion object {
        const val PLAN_SYSTEM_PROMPT = """You are a planning assistant. Analyze the user's request and create a structured plan.
Return a JSON object with:
- "summary": brief description of the plan
- "steps": array of step objects, each with "title" and "description"
"""
    }
}
