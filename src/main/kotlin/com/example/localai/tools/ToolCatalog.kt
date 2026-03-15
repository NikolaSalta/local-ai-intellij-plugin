package com.example.localai.tools

import com.example.localai.model.ToolDefinition
import com.example.localai.model.ToolParameter

/**
 * Registry of all available read-only tools. Plain class — not an IntelliJ service.
 */
object ToolCatalog {

    val tools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read_file",
            description = "Read the contents of a file in the project. Returns the file text (truncated to 500 lines).",
            parameters = listOf(
                ToolParameter(
                    name = "path",
                    type = "string",
                    description = "Relative path to the file from the project root"
                )
            )
        ),
        ToolDefinition(
            name = "list_files",
            description = "List files and directories at a given path in the project.",
            parameters = listOf(
                ToolParameter(
                    name = "path",
                    type = "string",
                    description = "Relative directory path from the project root",
                    required = false
                ),
                ToolParameter(
                    name = "recursive",
                    type = "boolean",
                    description = "If true, list files recursively (max 200 entries)",
                    required = false
                )
            )
        ),
        ToolDefinition(
            name = "grep_text",
            description = "Search for a text pattern in project files. Returns matching lines with file paths and line numbers (max 50 matches).",
            parameters = listOf(
                ToolParameter(
                    name = "pattern",
                    type = "string",
                    description = "Text pattern to search for"
                ),
                ToolParameter(
                    name = "path",
                    type = "string",
                    description = "Relative directory path to search within",
                    required = false
                )
            )
        ),
        ToolDefinition(
            name = "search_code",
            description = "Search for a code symbol or identifier across the project. Returns matching lines with file paths and line numbers (max 50 matches).",
            parameters = listOf(
                ToolParameter(
                    name = "query",
                    type = "string",
                    description = "Symbol or identifier to search for"
                )
            )
        )
    )

    /**
     * Formats tool definitions for inclusion in a system prompt.
     */
    fun formatToolsForPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("You have access to the following tools. To use a tool, respond with ONLY a JSON object in this exact format:")
        sb.appendLine("""{"tool": "tool_name", "args": {"param": "value"}}""")
        sb.appendLine()
        sb.appendLine("If you do NOT need to use a tool, respond with plain text (no JSON).")
        sb.appendLine()
        sb.appendLine("Available tools:")
        sb.appendLine()
        for (tool in tools) {
            sb.appendLine("- ${tool.name}: ${tool.description}")
            for (param in tool.parameters) {
                val req = if (param.required) "required" else "optional"
                sb.appendLine("    ${param.name} (${param.type}, $req): ${param.description}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}
