package com.example.localai.model

/**
 * Evidence record — tracks a single piece of evidence collected during analysis.
 */
data class EvidenceRecord(
    val type: EvidenceType,
    val path: String,
    val status: EvidenceStatus,
    val source: String,
    val confidence: Double = 1.0,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class EvidenceType {
    README,
    BUILD_FILE,
    SOURCE_ROOT,
    CONFIG_FILE,
    ENTRY_POINT,
    TEST_FILE,
    SCRIPT,
    API_ROUTE,
    SECURITY_SENSITIVE,
    REPRESENTATIVE_SOURCE,
    DEPENDENCY_FILE,
    DOCUMENTATION,
    LICENSE
}

enum class EvidenceStatus {
    FOUND,
    NOT_FOUND,
    PARTIAL,
    SKIPPED,
    ERROR
}

/**
 * An intermediate artifact created during pipeline execution.
 */
data class ExecutionArtifact(
    val id: String,
    val type: ArtifactType,
    val title: String,
    val summary: String,
    val content: String = "",
    val sourceRefs: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Double = 1.0
)

enum class ArtifactType {
    PROJECT_SKELETON,
    BUILD_SUMMARY,
    PACKAGE_MAP,
    ENTRY_POINT_MAP,
    RUNTIME_WIRING,
    SECURITY_FINDINGS,
    QUALITY_FINDINGS,
    ARCHITECTURE_FINDINGS,
    GENERATION_CONTEXT,
    EVIDENCE_SUMMARY,
    FINAL_REPORT
}
