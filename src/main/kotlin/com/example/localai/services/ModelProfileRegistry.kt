package com.example.localai.services

import com.example.localai.model.ModelCategory
import com.example.localai.model.ModelProfile
import com.example.localai.model.ToolStrategy

/**
 * Application-level service that classifies known models by capability.
 * Empty constructor — static registry, no dependencies.
 */
class ModelProfileRegistry {

    private val profiles: Map<String, ModelProfile> = buildMap {
        put("qwen2.5-coder:7b", ModelProfile(
            modelName = "qwen2.5-coder:7b",
            category = ModelCategory.CODER,
            supportsChat = true,
            supportsJson = true,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        ))
        put("qwen2.5-coder:14b", ModelProfile(
            modelName = "qwen2.5-coder:14b",
            category = ModelCategory.CODER,
            supportsChat = true,
            supportsJson = true,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        ))
        put("qwen-coder-local:latest", ModelProfile(
            modelName = "qwen-coder-local:latest",
            category = ModelCategory.CODER,
            supportsChat = true,
            supportsJson = true,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        ))
        put("deepseek-coder:6.7b", ModelProfile(
            modelName = "deepseek-coder:6.7b",
            category = ModelCategory.CODER,
            supportsChat = true,
            supportsJson = false,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        ))
        put("deepseek-coder:1.3b", ModelProfile(
            modelName = "deepseek-coder:1.3b",
            category = ModelCategory.CODER,
            supportsChat = true,
            supportsJson = false,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        ))
        put("deepseek-r1:8b", ModelProfile(
            modelName = "deepseek-r1:8b",
            category = ModelCategory.REASONING,
            supportsChat = true,
            supportsJson = false,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        ))
        put("ministral-3:8b", ModelProfile(
            modelName = "ministral-3:8b",
            category = ModelCategory.GENERAL,
            supportsChat = true,
            supportsJson = false,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        ))
        put("ministral-3:3b", ModelProfile(
            modelName = "ministral-3:3b",
            category = ModelCategory.GENERAL,
            supportsChat = true,
            supportsJson = false,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        ))
        put("qwen3-embedding:0.6b", ModelProfile(
            modelName = "qwen3-embedding:0.6b",
            category = ModelCategory.EMBEDDING,
            supportsChat = false,
            supportsJson = false,
            supportsEmbedding = true,
            toolStrategy = ToolStrategy.NONE
        ))
        put("nomic-embed-text:latest", ModelProfile(
            modelName = "nomic-embed-text:latest",
            category = ModelCategory.EMBEDDING,
            supportsChat = false,
            supportsJson = false,
            supportsEmbedding = true,
            toolStrategy = ToolStrategy.NONE
        ))
    }

    /**
     * Gets the profile for a model by name. Returns a default GENERAL
     * profile for unknown models.
     */
    fun getProfile(modelName: String): ModelProfile {
        return profiles[modelName] ?: ModelProfile(
            modelName = modelName,
            category = ModelCategory.GENERAL,
            supportsChat = true,
            supportsJson = false,
            supportsEmbedding = false,
            toolStrategy = ToolStrategy.JSON_FALLBACK
        )
    }

    /**
     * Returns all known model profiles.
     */
    fun getAllProfiles(): List<ModelProfile> = profiles.values.toList()

    companion object {
        val instance: ModelProfileRegistry
            get() = com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ModelProfileRegistry::class.java)
    }
}
