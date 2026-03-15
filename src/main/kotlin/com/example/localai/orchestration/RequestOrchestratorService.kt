package com.example.localai.orchestration

import com.example.localai.model.*
import com.example.localai.state.ExecutionPhase
import com.example.localai.state.PipelineStage
import com.example.localai.state.SessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Main entry point for processing user requests.
 *
 * Orchestration flow:
 * 1. Parse & classify request
 * 2. Resolve target
 * 3. Select strategy
 * 4. Execute pipeline
 * 5. Return result
 *
 * All progress is reported via SessionState timeline.
 */
@Service(Service.Level.PROJECT)
class RequestOrchestratorService(private val project: Project) {

    private val logger = Logger.getInstance(RequestOrchestratorService::class.java)

    private val taskClassifier = TaskClassifierService()
    private val targetResolver = TargetResolutionService(project)
    private val strategySelector = ExecutionStrategyService()
    private val pipelineCoordinator = PipelineCoordinatorService(project)

    /**
     * Process a user request through the full orchestration pipeline.
     * Returns the final response text.
     */
    fun processRequest(userPrompt: String, session: SessionState): String {
        try {
            // Stage 1: Parse & classify
            session.executionPhase = ExecutionPhase.RESOLVING_REQUEST
            session.pipelineStage = PipelineStage.REQUEST_PARSED

            val taskType = taskClassifier.classify(userPrompt)
            session.pipelineStage = PipelineStage.TASK_CLASSIFIED
            trace(session, TimelineEventType.TASK_CLASSIFIED, "Task type: $taskType")

            if (session.shouldStop()) return "[Stopped]"

            // Stage 2: Resolve target
            session.executionPhase = ExecutionPhase.RESOLVING_TARGET
            val targetResult = targetResolver.resolve(userPrompt)
            session.pipelineStage = PipelineStage.TARGET_RESOLVED
            trace(session, TimelineEventType.TARGET_RESOLVED,
                "Target: ${targetResult.primaryTarget} | Host: ${targetResult.hostProject}")

            if (session.shouldStop()) return "[Stopped]"

            // Stage 3: Select strategy
            val strategy = strategySelector.selectStrategy(taskType)
            session.pipelineStage = PipelineStage.EVIDENCE_PROFILE_SELECTED
            trace(session, TimelineEventType.STRATEGY_SELECTED, "Strategy: $strategy")

            // Build interpretation
            val interpretation = RequestInterpretation(
                userIntent = userPrompt,
                taskType = taskType,
                expectedOutput = "structured_report",
                primaryTarget = targetResult.primaryTarget,
                hostProject = targetResult.hostProject,
                additionalReferences = targetResult.references,
                confidence = targetResult.confidence,
                executionStrategy = strategy
            )
            session.requestInterpretation = interpretation

            if (session.shouldStop()) return "[Stopped]"

            // Stage 4: Execute pipeline
            return pipelineCoordinator.executePipeline(session, interpretation)

        } catch (e: Exception) {
            logger.error("Orchestration failed", e)
            session.executionPhase = ExecutionPhase.ERROR
            trace(session, TimelineEventType.ERROR, "Error: ${e.message}")
            return "Orchestration error: ${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString().take(1500)}"
        }
    }

    private fun trace(session: SessionState, type: TimelineEventType, desc: String) {
        session.timeline.add(TimelineEntry(type, desc))
    }
}
