package com.example.localai.llm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger

/**
 * Application-level service for discovering and caching available Ollama models.
 *
 * Provides a cached view of available models to avoid repeated API calls.
 * Refresh is explicit — call refresh() when you need fresh data.
 */
@Service(Service.Level.APP)
class ModelDiscoveryService {

    private val logger = Logger.getInstance(ModelDiscoveryService::class.java)

    @Volatile
    private var cachedModels: List<String> = emptyList()

    @Volatile
    private var lastRefreshTimeMs: Long = 0

    @Volatile
    var isOllamaConnected: Boolean = false
        private set

    /**
     * Refresh the model list from Ollama.
     * Safe to call from any thread.
     */
    fun refresh() {
        try {
            val client = ApplicationManager.getApplication()
                .getService(OllamaClientService::class.java)

            isOllamaConnected = client.healthCheck()

            if (isOllamaConnected) {
                cachedModels = client.listModels()
                lastRefreshTimeMs = System.currentTimeMillis()
                logger.info("Model discovery: ${cachedModels.size} models found")
            } else {
                cachedModels = emptyList()
                logger.info("Model discovery: Ollama not reachable")
            }
        } catch (e: Exception) {
            isOllamaConnected = false
            cachedModels = emptyList()
            logger.warn("Model discovery failed", e)
        }
    }

    /**
     * Get the cached list of available model names.
     * Returns empty list if no refresh has been done or Ollama is unreachable.
     */
    fun getAvailableModels(): List<String> = cachedModels

    /**
     * Check if a specific model is available locally.
     */
    fun isModelAvailable(modelName: String): Boolean {
        return cachedModels.any { it == modelName || it.startsWith("$modelName:") }
    }

    /**
     * Get models filtered by name substring (e.g., "coder", "embed").
     */
    fun searchModels(query: String): List<String> {
        return cachedModels.filter { it.contains(query, ignoreCase = true) }
    }

    /**
     * Seconds since last successful refresh, or -1 if never refreshed.
     */
    fun secondsSinceRefresh(): Long {
        return if (lastRefreshTimeMs == 0L) -1
        else (System.currentTimeMillis() - lastRefreshTimeMs) / 1000
    }

    companion object {
        val instance: ModelDiscoveryService
            get() = ApplicationManager.getApplication()
                .getService(ModelDiscoveryService::class.java)
    }
}
