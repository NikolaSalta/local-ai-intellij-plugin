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
 * Mutable session state — holds the full context of an active task.
 */
class SessionState {
    var executionPhase: ExecutionPhase = ExecutionPhase.IDLE
    var pipelineStage: PipelineStage = PipelineStage.NOT_STARTED
    var requestInterpretation: RequestInterpretation? = null
    var repoType: RepoType = RepoType.UNKNOWN

    val messages: MutableList<ChatMessage> = mutableListOf()
    val timeline: MutableList<TimelineEntry> = mutableListOf()
    val evidence: MutableList<EvidenceRecord> = mutableListOf()
    val artifacts: MutableList<ExecutionArtifact> = mutableListOf()

    @Volatile
    var stopRequested: Boolean = false

    @Volatile
    var clearRequested: Boolean = false

    /**
     * Reset everything — used by Clear.
     */
    fun clear() {
        executionPhase = ExecutionPhase.IDLE
        pipelineStage = PipelineStage.NOT_STARTED
        requestInterpretation = null
        repoType = RepoType.UNKNOWN
        messages.clear()
        timeline.clear()
        evidence.clear()
        artifacts.clear()
        stopRequested = false
        clearRequested = false
    }

    /**
     * Check if execution should be halted.
     */
    fun shouldStop(): Boolean = stopRequested || clearRequested
}
