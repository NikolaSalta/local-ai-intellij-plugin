package com.example.localai.model

/**
 * Classification of what the user is requesting.
 */
enum class TaskType {
    REPO_ANALYSIS,
    PROJECT_OVERVIEW,
    BUILD_AND_RUN_ANALYSIS,
    ARCHITECTURE_REVIEW,
    SECURITY_REVIEW,
    CODE_QUALITY_REVIEW,
    BUG_ANALYSIS,
    IMPLEMENT_FEATURE,
    VERIFY_FEATURE,
    SEARCH_ONLY,
    PLAN_ONLY,
    SYSTEM_VALIDATION,
    API_DESIGN,
    FRONTEND_DESIGN,
    BACKEND_DESIGN,
    FULLSTACK_FEATURE,
    DOCS_ALIGNMENT,
    GENERAL_QUESTION
}

/**
 * How the pipeline should execute.
 */
enum class ExecutionStrategy {
    DIRECT_SYNTHESIS,
    RETRIEVAL_FIRST,
    STRUCTURE_FIRST,
    PLAN_FIRST,
    ANALYZE_THEN_GENERATE,
    VALIDATE_THEN_ANSWER,
    INSPECT_THEN_REPORT,
    GENERATE_THEN_VALIDATE
}

/**
 * Classification of the repository type.
 */
enum class RepoType {
    APPLICATION,
    LIBRARY,
    ALGORITHMS_COLLECTION,
    FRAMEWORK,
    PLUGIN,
    TOOLING_REPO,
    MONOREPO,
    DOCS_CONFIG_REPO,
    ML_PIPELINE_REPO,
    MIXED,
    UNKNOWN
}

/**
 * Structured interpretation of the user's request.
 * Built by TaskClassifierService + TargetResolutionService.
 */
data class RequestInterpretation(
    val userIntent: String,
    val taskType: TaskType,
    val expectedOutput: String,
    val primaryTarget: String,
    val hostProject: String,
    val additionalReferences: List<String> = emptyList(),
    val confidence: Double = 1.0,
    val executionStrategy: ExecutionStrategy = ExecutionStrategy.RETRIEVAL_FIRST,
    val requiresCodeGeneration: Boolean = false,
    val requiresValidation: Boolean = false,
    val requiresTrace: Boolean = true
)

/**
 * Result of resolving where analysis should be directed.
 */
data class TargetResolutionResult(
    val primaryTarget: String,
    val hostProject: String,
    val references: List<String> = emptyList(),
    val confidence: Double = 1.0,
    val resolutionReason: String = ""
)
