package com.example.localai.model

/**
 * Types of events that appear in the agent execution timeline.
 */
enum class TimelineEventType {
    MODEL_CALL_START,
    TOOL_REQUEST,
    TOOL_EXEC_START,
    TOOL_EXEC_FINISH,
    FINAL_TEXT,
    LOOP_STOP,
    ERROR
}

/**
 * A single entry in the execution timeline.
 */
data class TimelineEntry(
    val eventType: TimelineEventType,
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)
