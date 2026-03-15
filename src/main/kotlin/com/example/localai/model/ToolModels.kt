package com.example.localai.model

/**
 * Represents a tool call request extracted from a model response.
 */
data class ToolCall(
    val tool: String,
    val args: Map<String, String>
)

/**
 * The result of executing a tool.
 */
data class ToolResult(
    val tool: String,
    val success: Boolean,
    val output: String
)

/**
 * Definition of an available tool (for system prompt).
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
)

/**
 * A single parameter in a tool definition.
 */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)
