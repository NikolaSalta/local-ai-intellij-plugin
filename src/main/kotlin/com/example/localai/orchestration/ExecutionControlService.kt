package com.example.localai.orchestration

import com.example.localai.state.SessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-level service for controlling pipeline execution.
 *
 * Provides stop/clear/resume operations that are checked at stage
 * boundaries in the pipeline coordinator.
 */
@Service(Service.Level.PROJECT)
class ExecutionControlService(private val project: Project) {

    private val logger = Logger.getInstance(ExecutionControlService::class.java)

    /**
     * Request the pipeline to stop at the next stage boundary.
     */
    fun requestStop(session: SessionState) {
        logger.info("Stop requested")
        session.execution.requestStop()
    }

    /**
     * Request clear — stop pipeline + wipe session state.
     */
    fun requestClear(session: SessionState) {
        logger.info("Clear requested")
        session.execution.requestClear()
    }

    /**
     * Reset stop/clear flags (e.g., before starting a new pipeline run).
     */
    fun resetFlags(session: SessionState) {
        session.execution.stopRequested = false
        session.execution.clearRequested = false
    }

    /**
     * Check if the pipeline should halt.
     */
    fun shouldStop(session: SessionState): Boolean = session.execution.shouldStop()
}
