package com.example.localai.services

import com.example.localai.model.ChatMessage
import com.example.localai.model.ChatMode
import com.example.localai.model.ChatRole
import com.example.localai.model.PlanResponse
import com.example.localai.model.PlanStep
import com.example.localai.model.TimelineEntry
import com.example.localai.model.TimelineEventType
import com.example.localai.settings.LocalAiSettingsState
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-level service managing chat conversation state and mode dispatch.
 * Constructor takes Project only.
 */
@Service(Service.Level.PROJECT)
class ChatSessionService(private val project: Project) {

    private val logger = Logger.getInstance(ChatSessionService::class.java)
    private val gson = Gson()

    var currentMode: ChatMode = ChatMode.ASK
    val messages: MutableList<ChatMessage> = mutableListOf()
    val timeline: MutableList<TimelineEntry> = mutableListOf()
    var lastPlanResponse: PlanResponse? = null
    var currentModel: String = LocalAiSettingsState.instance.defaultModel
    var isBusy: Boolean = false
        private set

    /**
     * Callback invoked when the conversation state changes (for UI updates).
     */
    var onStateChanged: (() -> Unit)? = null

    /**
     * Sends a user message and processes the response according to the current mode.
     * This runs synchronously — callers should invoke from a background thread.
     */
    fun sendMessage(userText: String) {
        if (isBusy) return
        isBusy = true
        notifyChanged()

        try {
            messages.add(ChatMessage(ChatRole.USER, userText))

            when (currentMode) {
                ChatMode.ASK -> handleAsk()
                ChatMode.PLAN -> handlePlan()
                ChatMode.AGENT -> handleAgent()
            }
        } catch (e: Exception) {
            logger.error("Error processing message", e)
            timeline.add(TimelineEntry(TimelineEventType.ERROR, "Error: ${e.message}"))
            messages.add(ChatMessage(ChatRole.ASSISTANT, "Error: ${e.message ?: "Unknown error"}"))
        } finally {
            isBusy = false
            notifyChanged()
        }
    }

    /**
     * Clears the conversation history.
     */
    fun clearSession() {
        messages.clear()
        timeline.clear()
        lastPlanResponse = null
        notifyChanged()
    }

    private fun handleAsk() {
        // ALL modes use deterministic analysis for project queries
        runDeterministicAnalysis()
    }

    private fun handlePlan() {
        // ALL modes use deterministic analysis for project queries
        runDeterministicAnalysis()
    }

    private fun handleAgent() {
        // ALL modes use deterministic analysis for project queries
        runDeterministicAnalysis()
    }

    /**
     * Core analysis method — runs deterministic fact extraction.
     * Used by ALL modes to ensure factual, non-hallucinated output.
     */
    private fun runDeterministicAnalysis() {
        try {
            timeline.add(TimelineEntry(TimelineEventType.TOOL_EXEC_START, "Starting deterministic project analysis..."))
            notifyChanged()

            // Directly instantiate — NO service lookup
            val runner = ProjectAnalysisRunner(project)
            val userPrompt = messages.lastOrNull { it.role == ChatRole.USER }?.content ?: "Analyze this project"

            runner.runAnalysis(this, userPrompt)
        } catch (e: Exception) {
            logger.error("Analysis failed", e)
            timeline.add(TimelineEntry(TimelineEventType.ERROR, "Error: ${e.message}"))
            messages.add(ChatMessage(ChatRole.ASSISTANT,
                "Analysis error: ${e.javaClass.simpleName}: ${e.message}\n\nStack:\n${e.stackTraceToString().take(2000)}"))
        }
    }

    /**
     * Builds the list of messages for the Ollama API call.
     */
    fun buildOllamaMessages(systemMessage: String): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        result.add(mapOf("role" to "system", "content" to systemMessage))
        for (msg in messages) {
            val role = when (msg.role) {
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
                ChatRole.TOOL -> "user"  // Ollama doesn't have a tool role; send as user
                ChatRole.SYSTEM -> "system"
            }
            result.add(mapOf("role" to role, "content" to msg.content))
        }
        return result
    }

    private fun parsePlanResponse(response: String): PlanResponse? {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val goal = json.get("goal")?.asString ?: "Plan"
            val stepsArray = json.getAsJsonArray("steps")
            val steps = stepsArray?.mapIndexed { index, element ->
                val obj = element.asJsonObject
                PlanStep(
                    step = obj.get("step")?.asInt ?: (index + 1),
                    title = obj.get("title")?.asString ?: "Step ${index + 1}",
                    description = obj.get("description")?.asString ?: ""
                )
            } ?: emptyList()
            PlanResponse(goal, steps)
        } catch (e: Exception) {
            logger.warn("Failed to parse plan response", e)
            null
        }
    }

    private fun notifyChanged() {
        onStateChanged?.invoke()
    }

    companion object {
        private const val PLAN_SYSTEM_PROMPT = """You must respond with a JSON object containing a structured plan.
The JSON must have this exact format:
{
  "goal": "Brief description of what the plan achieves",
  "steps": [
    {"step": 1, "title": "Step title", "description": "Detailed description of this step"},
    {"step": 2, "title": "Step title", "description": "Detailed description of this step"}
  ]
}
Do NOT include any text outside the JSON object. Respond with ONLY valid JSON."""

        /**
         * Agent system prompt — forces the model to use tools first,
         * then produce the structured A-K validation report.
         */
        const val AGENT_SYSTEM_PROMPT = """You are a local AI agent inside IntelliJ IDEA with READ-ONLY tools for project inspection.

IMPORTANT RULES:
1. FIRST use tools (list_files, read_file, grep_text, search_code) to gather evidence.
2. NEVER guess file contents. NEVER fabricate classes or methods.
3. After gathering evidence, produce your FINAL ANSWER as a structured report.

CONTEXT AWARENESS:
- The PRIMARY TARGET is the project the user asks you to analyze.
- The HOST PROJECT is the IntelliJ IDEA / IDE environment where you run.
- These may be DIFFERENT. If the user specifies an external path, that path is the PRIMARY TARGET, NOT the IDE plugin project.
- Always identify both clearly.

YOUR FINAL RESPONSE MUST follow this EXACT structure. Do NOT output step-by-step reasoning. Output ONLY the final report:

Итоговый инженерный отчёт по валидации AI-агента

A. Разрешение контекста
Параметр | Значение
Primary Target | [full path to analyzed project]
Host Project | [IDE/plugin environment, e.g. "IntelliJ IDEA / Cursor IDE"]
Additional References | [list or "Отсутствуют"]
Почему выбрана эта цель | [reason, e.g. "Явно указана в запросе пользователя"]
Что исключено | [what is excluded, e.g. "IDE-конфигурация агента, внешние зависимости"]

B. Сводка по Primary Target
[2-3 sentence description of what the project does, based on README and source code you read]

C. Технологический стек
Слой | Технология
[e.g. Язык | Java 8+]
[e.g. GUI Framework | Swing (javax.swing)]
[e.g. Сеть | java.net.Socket, java.net.URL]
[e.g. Процессы | ProcessBuilder]
[e.g. Persistence | java.util.prefs.Preferences]
[e.g. Сборка | Batch-скрипты]
[e.g. Дистрибуция | JAR-файл]

D. Основные точки входа
[List each entry point with filename and code snippet]

E. Ключевые компоненты
Компонент | Назначение
[Component name | What it does]

F. Runtime Flow
1. [First step]
2. [Second step]
...

G. Качество использования инструментов
Критерий | Оценка
[e.g. Использование list_files для структуры | ✅ Применён]
[e.g. Чтение README.md для контекста | ✅ Применён]
[e.g. Чтение основных файлов | ✅ Применён]
[e.g. Догадки без подтверждения | ❌ Нет]
[e.g. "Галлюцинации" классов | ❌ Нет]

H. Оценка изоляции контекста
- Утечка контекста HOST PROJECT? — [Да/Нет + explanation]
- Приоритет правильной цели? — [Да/Нет]
- Внешний путь уважен? — [Да/Нет]
- Признаки неправильной orchestration — [description]

I. Риски / запахи
Риск | Уровень | Описание
[Risk name | Низкий/Средний/Высокий | Description]

J. Что ещё требует проверки
[List of files/components NOT fully inspected]

K. Финальный вердикт
[✅ PASS / ⚠️ PASS WITH WARNINGS / ❌ FAIL]
Обоснование:
[Bullet points explaining why]

VERDICT CRITERIA:
- PASS: correct target, tools used correctly, factual summary, no host context pollution
- PASS WITH WARNINGS: mostly correct but ambiguities or weak evidence
- FAIL: wrong target, hallucinations, guesses without tools, or context pollution

REMEMBER: Output ONLY the final structured report. Do NOT output intermediate steps or reasoning."""
    }
}

