package com.example.localai.orchestration

import com.example.localai.model.TaskType

/**
 * Classifies user prompts into task types using keyword/pattern matching.
 * No LLM needed — deterministic classification.
 */
class TaskClassifierService {

    companion object {
        private val ANALYSIS_KEYWORDS = listOf(
            "анализ", "analyze", "inspect", "review", "check", "audit",
            "проверь", "проанализируй", "инспект", "проверка"
        )
        private val OVERVIEW_KEYWORDS = listOf(
            "overview", "обзор", "what is", "что это", "describe", "опиши",
            "project", "проект", "repo", "repository"
        )
        private val BUILD_KEYWORDS = listOf(
            "build", "run", "compile", "сборка", "запуск", "собери",
            "how to run", "как запустить", "как собрать"
        )
        private val ARCHITECTURE_KEYWORDS = listOf(
            "architecture", "архитектур", "structure", "структур",
            "design", "дизайн", "module", "модул", "package", "пакет"
        )
        private val SECURITY_KEYWORDS = listOf(
            "security", "безопасност", "secur", "vulnerab", "уязвим",
            "hardcode", "password", "secret", "credential", "токен"
        )
        private val QUALITY_KEYWORDS = listOf(
            "quality", "качеств", "smell", "refactor", "рефактор",
            "clean", "duplication", "дупликат", "naming"
        )
        private val IMPLEMENT_KEYWORDS = listOf(
            "implement", "create", "add", "build", "make", "write",
            "реализуй", "создай", "добавь", "напиши", "сделай"
        )
        private val PLAN_KEYWORDS = listOf(
            "plan", "план", "strategy", "стратег", "roadmap", "steps", "шаги"
        )
        private val SEARCH_KEYWORDS = listOf(
            "find", "search", "where", "найди", "найти", "где", "поиск"
        )
        private val API_KEYWORDS = listOf(
            "api", "endpoint", "route", "rest", "controller", "handler"
        )
        private val FRONTEND_KEYWORDS = listOf(
            "frontend", "ui", "component", "page", "view", "react", "vue",
            "фронтенд", "интерфейс", "компонент", "страниц"
        )
        private val BACKEND_KEYWORDS = listOf(
            "backend", "server", "service", "бэкенд", "сервер", "сервис"
        )
    }

    /**
     * Classify the user prompt into one or more task types.
     * Returns primary task type.
     */
    fun classify(prompt: String): TaskType {
        val lower = prompt.lowercase()

        // Score each category
        val scores = mutableMapOf<TaskType, Int>()

        scores[TaskType.REPO_ANALYSIS] = countMatches(lower, ANALYSIS_KEYWORDS)
        scores[TaskType.PROJECT_OVERVIEW] = countMatches(lower, OVERVIEW_KEYWORDS)
        scores[TaskType.BUILD_AND_RUN_ANALYSIS] = countMatches(lower, BUILD_KEYWORDS)
        scores[TaskType.ARCHITECTURE_REVIEW] = countMatches(lower, ARCHITECTURE_KEYWORDS)
        scores[TaskType.SECURITY_REVIEW] = countMatches(lower, SECURITY_KEYWORDS)
        scores[TaskType.CODE_QUALITY_REVIEW] = countMatches(lower, QUALITY_KEYWORDS)
        scores[TaskType.IMPLEMENT_FEATURE] = countMatches(lower, IMPLEMENT_KEYWORDS)
        scores[TaskType.PLAN_ONLY] = countMatches(lower, PLAN_KEYWORDS)
        scores[TaskType.SEARCH_ONLY] = countMatches(lower, SEARCH_KEYWORDS)
        scores[TaskType.API_DESIGN] = countMatches(lower, API_KEYWORDS)
        scores[TaskType.FRONTEND_DESIGN] = countMatches(lower, FRONTEND_KEYWORDS)
        scores[TaskType.BACKEND_DESIGN] = countMatches(lower, BACKEND_KEYWORDS)

        // Find highest scoring category
        val best = scores.maxByOrNull { it.value }

        // If no keywords matched, default to PROJECT_OVERVIEW for analysis prompts
        if (best == null || best.value == 0) {
            return TaskType.PROJECT_OVERVIEW
        }

        return best.key
    }

    private fun countMatches(text: String, keywords: List<String>): Int {
        return keywords.count { text.contains(it) }
    }
}
