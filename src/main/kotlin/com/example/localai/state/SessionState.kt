package com.example.localai.state

import com.example.localai.model.*

/**
 * Global execution state of the pipeline.
 */
enum class ExecutionPhase {
    IDLE,
    RESOLVING_REQUEST,
    RESOLVING_TARGET,
    DISCOVERING_MODELS,
    RUNNING_STRUCTURAL_SCAN,
    RUNNING_RETRIEVAL,
    BUILDING_ARTIFACTS,
    CHECKING_EVIDENCE,
    WAITING_FOR_LLM,
    GENERATING_CODE,
    VALIDATING,
    READY,
    PAUSED,
    STOPPED,
    ERROR
}

/**
 * Pipeline stage progression — each stage must be completed in order.
 */
enum class PipelineStage {
    NOT_STARTED,
    REQUEST_PARSED,
    TASK_CLASSIFIED,
    TARGET_RESOLVED,
    EVIDENCE_PROFILE_SELECTED,
    MODEL_DISCOVERY_DONE,
    STRUCTURAL_SCAN_DONE,
    REPO_TYPE_DETECTED,
    REPRESENTATIVE_FILES_SELECTED,
    FOCUSED_RETRIEVAL_DONE,
    ARTIFACTS_BUILT,
    EVIDENCE_GATE_PASSED,
    FINAL_SYNTHESIS_DONE
}

/**
 * Mutable session state — composes ExecutionState + TraceState.
 *
 * Architecture: SessionState is the top-level composite that holds both
 * execution control (phase, pipeline, stop/clear) and observability
 * (timeline, evidence, artifacts). Consumers can access the sub-states
 * directly or use the convenience delegates on SessionState.
 */
class SessionState {

    /** Execution control: phase, pipeline stage, stop/clear. */
    val execution = ExecutionState()

    /** Observability: timeline, evidence, artifacts. */
    val trace = TraceState()

    // === Convenience delegates (backward compatibility) ===

    var executionPhase: ExecutionPhase
        get() = execution.executionPhase
        set(value) { execution.executionPhase = value }

    var pipelineStage: PipelineStage
        get() = execution.pipelineStage
        set(value) { execution.pipelineStage = value }

    var stopRequested: Boolean
        get() = execution.stopRequested
        set(value) { execution.stopRequested = value }

    var clearRequested: Boolean
        get() = execution.clearRequested
        set(value) { execution.clearRequested = value }

    val timeline: MutableList<TimelineEntry> get() = trace.timeline
    val evidence: MutableList<EvidenceRecord> get() = trace.evidence
    val artifacts: MutableList<ExecutionArtifact> get() = trace.artifacts

    // === Domain state ===

    var requestInterpretation: RequestInterpretation? = null
    var repoType: RepoType = RepoType.UNKNOWN
    val messages: MutableList<ChatMessage> = mutableListOf()

    /**
     * Check if execution should be halted.
     */
    fun shouldStop(): Boolean = execution.shouldStop()

    /**
     * Reset everything — used by Clear.
     */
    fun clear() {
        execution.reset()
        trace.clear()
        requestInterpretation = null
        repoType = RepoType.UNKNOWN
        messages.clear()
    }
}
