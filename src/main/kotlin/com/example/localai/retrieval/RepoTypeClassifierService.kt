package com.example.localai.retrieval

import com.example.localai.model.RepoType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service for classifying repository type based on structural scan results.
 *
 * Uses deterministic heuristics — no LLM calls.
 */
@Service(Service.Level.PROJECT)
class RepoTypeClassifierService(private val project: Project) {

    fun classify(skeleton: StructuralScanService.ProjectSkeleton): RepoType {
        return when {
            skeleton.entryPoints.isNotEmpty() && skeleton.uiFramework != null -> RepoType.APPLICATION
            skeleton.entryPoints.isNotEmpty() -> RepoType.APPLICATION
            skeleton.buildSystem.contains("Gradle") &&
                skeleton.configs.any { it.name == "settings.gradle.kts" } -> RepoType.PLUGIN
            skeleton.sourceFiles.isEmpty() && skeleton.configs.isNotEmpty() -> RepoType.DOCS_CONFIG_REPO
            skeleton.sourceFiles.size > 20 -> RepoType.MONOREPO
            else -> RepoType.UNKNOWN
        }
    }
}
