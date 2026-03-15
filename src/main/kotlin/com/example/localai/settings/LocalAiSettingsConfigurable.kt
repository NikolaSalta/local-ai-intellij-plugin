package com.example.localai.settings

import com.intellij.openapi.options.Configurable
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.EmptyBorder

/**
 * Settings UI page under Tools > Local AI Settings.
 */
class LocalAiSettingsConfigurable : Configurable {

    private var urlField: JTextField? = null
    private var modelField: JTextField? = null
    private var embeddingField: JTextField? = null
    private var timeoutField: JTextField? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = "Local AI Settings"

    override fun createComponent(): JComponent {
        val settings = LocalAiSettingsState.instance
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(10, 10, 10, 10)

        urlField = JTextField(settings.ollamaBaseUrl, 40)
        modelField = JTextField(settings.defaultModel, 40)
        embeddingField = JTextField(settings.embeddingModel, 40)
        timeoutField = JTextField(settings.timeoutMs.toString(), 40)

        panel.add(createRow("Ollama Base URL:", urlField!!))
        panel.add(createRow("Default Model:", modelField!!))
        panel.add(createRow("Embedding Model:", embeddingField!!))
        panel.add(createRow("Timeout (ms):", timeoutField!!))

        mainPanel = panel
        return panel
    }

    private fun createRow(label: String, field: JTextField): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.border = EmptyBorder(4, 0, 4, 0)
        val lbl = JLabel(label)
        lbl.border = EmptyBorder(0, 0, 0, 8)
        row.add(lbl)
        row.add(field)
        return row
    }

    override fun isModified(): Boolean {
        val settings = LocalAiSettingsState.instance
        return urlField?.text != settings.ollamaBaseUrl ||
                modelField?.text != settings.defaultModel ||
                embeddingField?.text != settings.embeddingModel ||
                timeoutField?.text != settings.timeoutMs.toString()
    }

    override fun apply() {
        val state = LocalAiSettingsState.instance.state
        state.ollamaBaseUrl = urlField?.text ?: state.ollamaBaseUrl
        state.defaultModel = modelField?.text ?: state.defaultModel
        state.embeddingModel = embeddingField?.text ?: state.embeddingModel
        state.timeoutMs = timeoutField?.text?.toIntOrNull() ?: state.timeoutMs
    }

    override fun reset() {
        val settings = LocalAiSettingsState.instance
        urlField?.text = settings.ollamaBaseUrl
        modelField?.text = settings.defaultModel
        embeddingField?.text = settings.embeddingModel
        timeoutField?.text = settings.timeoutMs.toString()
    }

    override fun disposeUIResources() {
        mainPanel = null
        urlField = null
        modelField = null
        embeddingField = null
        timeoutField = null
    }
}
