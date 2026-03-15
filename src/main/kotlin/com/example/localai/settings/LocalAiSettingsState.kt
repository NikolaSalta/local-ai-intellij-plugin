package com.example.localai.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings state for the Local AI plugin.
 * Registered as an application-level service with empty constructor.
 */
@State(
    name = "com.example.localai.settings.LocalAiSettingsState",
    storages = [Storage("LocalAiSettings.xml")]
)
class LocalAiSettingsState : PersistentStateComponent<LocalAiSettingsState.State> {

    data class State(
        var ollamaBaseUrl: String = "http://127.0.0.1:11434",
        var defaultModel: String = "qwen2.5-coder:7b",
        var embeddingModel: String = "qwen3-embedding:0.6b",
        var timeoutMs: Int = 120000
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val ollamaBaseUrl: String get() = myState.ollamaBaseUrl
    val defaultModel: String get() = myState.defaultModel
    val embeddingModel: String get() = myState.embeddingModel
    val timeoutMs: Int get() = myState.timeoutMs

    companion object {
        val instance: LocalAiSettingsState
            get() = ApplicationManager.getApplication().getService(LocalAiSettingsState::class.java)
    }
}
