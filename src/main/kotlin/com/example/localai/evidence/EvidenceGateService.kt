package com.example.localai.evidence

import com.example.localai.model.EvidenceRecord
import com.example.localai.model.EvidenceStatus
import com.example.localai.model.EvidenceType
import com.example.localai.retrieval.StructuralScanService.ProjectSkeleton
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service for checking evidence sufficiency before LLM synthesis.
 *
 * The evidence gate ensures the pipeline has enough verified facts
 * before sending context to the LLM. This prevents hallucination-prone
 * responses based on insufficient evidence.
 */
@Service(Service.Level.PROJECT)
class EvidenceGateService(private val project: Project) {

    data class GateResult(
        val passed: Boolean,
        val foundCount: Int,
        val totalChecks: Int,
        val gaps: List<String>
    )

    /**
     * Check if evidence is sufficient for the given skeleton.
     *
     * Minimum bar: source files must be found.
     * Optional checks: build system, languages, README, entry points.
     */
    fun check(skeleton: ProjectSkeleton): GateResult {
        val checks = mutableListOf<Pair<String, Boolean>>()

        checks.add("Source files found" to skeleton.sourceFiles.isNotEmpty())
        checks.add("Build system identified" to (skeleton.buildSystem != "Не обнаружена"))
        checks.add("Languages detected" to skeleton.languages.isNotEmpty())

        if (skeleton.readme != null) checks.add("README found" to true)
        if (skeleton.entryPoints.isNotEmpty()) checks.add("Entry points found" to true)

        val passed = checks.count { it.second }
        val gaps = checks.filter { !it.second }.map { it.first }

        return GateResult(
            passed = skeleton.sourceFiles.isNotEmpty(),
            foundCount = passed,
            totalChecks = checks.size,
            gaps = gaps
        )
    }
}
