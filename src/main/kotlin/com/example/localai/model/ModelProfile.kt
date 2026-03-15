package com.example.localai.model

/**
 * Category of model capability.
 */
enum class ModelCategory {
    CODER,
    REASONING,
    GENERAL,
    EMBEDDING
}

/**
 * Strategy used for tool calling with this model.
 */
enum class ToolStrategy {
    JSON_FALLBACK,
    NONE
}

/**
 * Profile describing the capabilities of a known model.
 */
data class ModelProfile(
    val modelName: String,
    val category: ModelCategory,
    val supportsChat: Boolean,
    val supportsJson: Boolean,
    val supportsEmbedding: Boolean,
    val toolStrategy: ToolStrategy
)
