package com.example.localai.state

import com.example.localai.model.EvidenceRecord
import com.example.localai.model.ExecutionArtifact
import com.example.localai.model.TimelineEntry

/**
 * Trace state: holds the timeline, evidence records, and artifacts
 * accumulated during pipeline execution.
 *
 * This is the observability layer — everything that happened is recorded here.
 */
class TraceState {
    val timeline: MutableList<TimelineEntry> = mutableListOf()
    val evidence: MutableList<EvidenceRecord> = mutableListOf()
    val artifacts: MutableList<ExecutionArtifact> = mutableListOf()

    /**
     * Total number of trace events recorded.
     */
    val totalEvents: Int get() = timeline.size + evidence.size + artifacts.size

    /**
     * Clear all trace data.
     */
    fun clear() {
        timeline.clear()
        evidence.clear()
        artifacts.clear()
    }
}
