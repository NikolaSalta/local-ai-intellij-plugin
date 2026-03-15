package com.example.localai.state

/**
 * Execution control state: tracks what the pipeline is currently doing
 * and whether stop/clear has been requested.
 *
 * Thread-safe: stop/clear flags are @Volatile for cross-thread visibility.
 */
class ExecutionState {
    var executionPhase: ExecutionPhase = ExecutionPhase.IDLE
    var pipelineStage: PipelineStage = PipelineStage.NOT_STARTED

    @Volatile
    var stopRequested: Boolean = false

    @Volatile
    var clearRequested: Boolean = false

    @Volatile
    var isBusy: Boolean = false

    /**
     * Check if execution should be halted at the next stage boundary.
     */
    fun shouldStop(): Boolean = stopRequested || clearRequested

    /**
     * Request stop — pipeline will halt between stages.
     */
    fun requestStop() {
        stopRequested = true
    }

    /**
     * Request clear — pipeline will halt and state will be wiped.
     */
    fun requestClear() {
        clearRequested = true
    }

    /**
     * Reset execution state to idle.
     */
    fun reset() {
        executionPhase = ExecutionPhase.IDLE
        pipelineStage = PipelineStage.NOT_STARTED
        stopRequested = false
        clearRequested = false
        isBusy = false
    }
}
