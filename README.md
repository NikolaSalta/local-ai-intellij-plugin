# Local AI Assistant — IntelliJ IDEA Plugin

A local Cursor-like AI coding assistant plugin for IntelliJ IDEA, powered entirely by **Ollama**. No cloud APIs, no remote backends — everything runs locally.

## Features

- **ASK mode** — One-shot question/answer with IDE context
- **PLAN mode** — Structured plan generation with plan card rendering
- **AGENT mode** — Bounded tool-use loop with read-only workspace tools
- **Context-aware** — Automatically collects project info, current file, selection, and editor context
- **Tool Window UI** — Integrated chat interface with message bubbles, timeline, and context chips
- **Model Selector** — Choose from any model available in your local Ollama instance
- **Settings Page** — Configure Ollama URL, default models, and timeout

## Prerequisites

1. **Java 17+** — Required to build and run the plugin
2. **Ollama** — Must be installed and running locally
   ```bash
   # Install Ollama: https://ollama.ai
   # Pull the default model:
   ollama pull qwen2.5-coder:7b
   # Optionally pull the embedding model:
   ollama pull qwen3-embedding:0.6b
   ```

## How to Run

```bash
# Clone or open the project directory
cd local-ai-plugin

# Build and launch IntelliJ IDEA with the plugin:
./gradlew runIde
```

This will open a sandboxed IntelliJ IDEA instance with the plugin pre-installed.

## Configuration

1. Open **Settings** → **Tools** → **Local AI Settings**
2. Configure:
   - **Ollama Base URL**: `http://127.0.0.1:11434` (default)
   - **Default Model**: `qwen2.5-coder:7b` (default)
   - **Embedding Model**: `qwen3-embedding:0.6b` (default)
   - **Timeout**: `60000` ms (default)

## Using the Plugin

1. Open the **Local AI** tool window (right sidebar, or via **Tools → Open Local AI**)
2. Select a mode: **ASK**, **PLAN**, or **AGENT**
3. Select a model from the dropdown (refresh with ↻ button)
4. Type your message and press **Send** (or **Ctrl+Enter**)

### Modes

| Mode | Behavior |
|------|----------|
| **ASK** | Single question → response with full IDE context |
| **PLAN** | Generates a structured plan as numbered step cards |
| **AGENT** | Multi-step loop that can use read-only tools (read files, search code, list directories, grep) |

## Read-Only Tools (Agent Mode)

| Tool | Description |
|------|-------------|
| `read_file` | Read contents of a project file |
| `list_files` | List directory contents |
| `grep_text` | Search for text patterns in files |
| `search_code` | Search for symbols/identifiers across the project |

All tools are read-only and scoped to the project directory. No file modifications, no shell execution.

## Default Models

| Purpose | Model |
|---------|-------|
| Chat/Code | `qwen2.5-coder:7b` |
| Embedding | `qwen3-embedding:0.6b` |

## License

This is a local development tool. Use as you see fit.
