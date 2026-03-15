package com.example.localai.services

import com.example.localai.settings.LocalAiSettingsState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * Application-level service for communicating with the local Ollama API.
 * Empty constructor — reads settings via LocalAiSettingsState.instance.
 */
class OllamaClientService {

    private val logger = Logger.getInstance(OllamaClientService::class.java)
    private val gson = Gson()

    /**
     * Fetches the list of available models from Ollama.
     * GET /api/tags
     */
    fun listModels(): List<String> {
        return try {
            val settings = LocalAiSettingsState.instance
            val url = "${settings.ollamaBaseUrl}/api/tags"
            val response = httpGet(url, settings.timeoutMs)
            val json = JsonParser.parseString(response).asJsonObject
            val models = json.getAsJsonArray("models")
            models.map { it.asJsonObject.get("name").asString }
        } catch (e: Exception) {
            logger.warn("Failed to list Ollama models", e)
            emptyList()
        }
    }

    /**
     * Sends a chat request to Ollama (non-streaming).
     * POST /api/chat with stream=false
     *
     * @param model Model name to use
     * @param messages List of message maps with "role" and "content" keys
     * @param formatJson If true, request JSON format output
     * @return The assistant's response content string
     */
    fun chat(
        model: String,
        messages: List<Map<String, String>>,
        formatJson: Boolean = false
    ): String {
        val settings = LocalAiSettingsState.instance
        val url = "${settings.ollamaBaseUrl}/api/chat"

        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", false)
            add("messages", gson.toJsonTree(messages))
            if (formatJson) {
                addProperty("format", "json")
            }
        }

        val response = httpPost(url, body.toString(), settings.timeoutMs)
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val message = json.getAsJsonObject("message")
            message.get("content").asString
        } catch (e: Exception) {
            logger.error("Failed to parse Ollama chat response", e)
            "Error: Failed to parse response from Ollama."
        }
    }

    /**
     * Generates embeddings for the given text.
     * POST /api/embed
     *
     * @param model Embedding model name
     * @param input Text to embed
     * @return List of float embeddings, or empty list on failure
     */
    fun embed(model: String, input: String): List<Float> {
        return try {
            val settings = LocalAiSettingsState.instance
            val url = "${settings.ollamaBaseUrl}/api/embed"

            val body = JsonObject().apply {
                addProperty("model", model)
                addProperty("input", input)
            }

            val response = httpPost(url, body.toString(), settings.timeoutMs)
            val json = JsonParser.parseString(response).asJsonObject
            val embeddingsArray = json.getAsJsonArray("embeddings")
            if (embeddingsArray != null && embeddingsArray.size() > 0) {
                val firstEmbedding = embeddingsArray[0].asJsonArray
                firstEmbedding.map { it.asFloat }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate embeddings", e)
            emptyList()
        }
    }

    /**
     * Checks if Ollama is reachable.
     */
    fun isAvailable(): Boolean {
        return try {
            val settings = LocalAiSettingsState.instance
            val url = "${settings.ollamaBaseUrl}/api/tags"
            httpGet(url, 5000)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Accept", "application/json")

        return try {
            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            val result = reader.readText()
            reader.close()
            result
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(url: String, body: String, timeoutMs: Int): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")

        val writer = OutputStreamWriter(connection.outputStream, Charsets.UTF_8)
        writer.write(body)
        writer.flush()
        writer.close()

        return try {
            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                val errorStream = connection.errorStream
                val errorBody = if (errorStream != null) {
                    val reader = BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8))
                    val result = reader.readText()
                    reader.close()
                    result
                } else {
                    "No error body"
                }
                logger.error("Ollama HTTP error $responseCode: $errorBody")
                throw Exception("Ollama returned HTTP $responseCode: $errorBody")
            }
            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            val result = reader.readText()
            reader.close()
            result
        } finally {
            connection.disconnect()
        }
    }
}
