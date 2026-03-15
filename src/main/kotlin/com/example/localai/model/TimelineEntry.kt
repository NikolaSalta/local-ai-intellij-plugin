package com.example.localai.model

/**
 * Types of events that appear in the agent execution timeline.
 */
enum class TimelineEventType {
    // Orchestration events
    REQUEST_PARSED,
    TASK_CLASSIFIED,
    TARGET_RESOLVED,
    STRATEGY_SELECTED,

    // Model events
    MODEL_DISCOVERY_START,
    MODEL_DISCOVERY_FINISH,
    MODEL_CALL_START,
    MODEL_CALL_FINISH,

    // Scan & retrieval events
    STRUCTURAL_SCAN_START,
    STRUCTURAL_SCAN_FINISH,
    REPO_TYPE_DETECTED,
    RETRIEVAL_PASS_START,
    RETRIEVAL_PASS_FINISH,

    // Evidence events
    EVIDENCE_COLLECTED,
    EVIDENCE_GATE_CHECK,
    EVIDENCE_GATE_PASSED,
    EVIDENCE_GATE_FAILED,

    // Tool events
    TOOL_REQUEST,
    TOOL_EXEC_START,
    TOOL_EXEC_FINISH,

    // Artifact events
    ARTIFACT_CREATED,

    // Synthesis events
    SYNTHESIS_START,
    SYNTHESIS_FINISH,
    FINAL_TEXT,

    // Control events
    LOOP_STOP,
    STOP_REQUESTED,
    CLEAR_REQUESTED,
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
