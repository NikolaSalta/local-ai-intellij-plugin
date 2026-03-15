package com.example.localai.tools

import com.example.localai.model.ToolResult

/**
 * Formats tool results for LLM consumption.
 * Plain class — stateless utility.
 */
object ToolResultFormatter {

    /**
     * Formats a tool result as a message string for the conversation.
     */
    fun format(result: ToolResult): String {
        val statusLabel = if (result.success) "Success" else "Error"
        return buildString {
            appendLine("[Tool: ${result.tool}] $statusLabel")
            appendLine()
            append(result.output)
        }
    }

    /**
     * Formats a tool result as a compact summary for the timeline.
     */
    fun formatCompact(result: ToolResult): String {
        val statusLabel = if (result.success) "OK" else "ERR"
        val preview = result.output.lineSequence().firstOrNull()?.take(80) ?: ""
        return "[${result.tool}] $statusLabel: $preview"
    }
}
