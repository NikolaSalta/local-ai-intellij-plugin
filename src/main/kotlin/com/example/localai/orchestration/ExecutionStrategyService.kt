package com.example.localai.orchestration

import com.example.localai.model.ExecutionStrategy
import com.example.localai.model.TaskType

/**
 * Selects execution strategy based on task type.
 */
class ExecutionStrategyService {

    fun selectStrategy(taskType: TaskType): ExecutionStrategy {
        return when (taskType) {
            // Analysis tasks → scan project first
            TaskType.REPO_ANALYSIS,
            TaskType.PROJECT_OVERVIEW,
            TaskType.BUILD_AND_RUN_ANALYSIS -> ExecutionStrategy.STRUCTURE_FIRST

            // Review tasks → inspect then report
            TaskType.ARCHITECTURE_REVIEW,
            TaskType.SECURITY_REVIEW,
            TaskType.CODE_QUALITY_REVIEW -> ExecutionStrategy.INSPECT_THEN_REPORT

            // Generation tasks → analyze context first
            TaskType.IMPLEMENT_FEATURE,
            TaskType.API_DESIGN,
            TaskType.FRONTEND_DESIGN,
            TaskType.BACKEND_DESIGN,
            TaskType.FULLSTACK_FEATURE -> ExecutionStrategy.ANALYZE_THEN_GENERATE

            // Validation tasks
            TaskType.VERIFY_FEATURE,
            TaskType.SYSTEM_VALIDATION -> ExecutionStrategy.VALIDATE_THEN_ANSWER

            // Planning
            TaskType.PLAN_ONLY -> ExecutionStrategy.PLAN_FIRST

            // Search / Bug analysis → retrieval first
            TaskType.SEARCH_ONLY,
            TaskType.BUG_ANALYSIS -> ExecutionStrategy.RETRIEVAL_FIRST

            // Docs alignment
            TaskType.DOCS_ALIGNMENT -> ExecutionStrategy.RETRIEVAL_FIRST

            // General question
            TaskType.GENERAL_QUESTION -> ExecutionStrategy.DIRECT_SYNTHESIS
        }
    }
}
