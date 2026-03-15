package com.example.localai.ui

import com.example.localai.model.ChatMessage
import com.example.localai.model.ChatMode
import com.example.localai.model.ChatRole
import com.example.localai.model.PlanResponse
import com.example.localai.services.ChatSessionService
import com.example.localai.context.IdeContextService
import com.example.localai.llm.OllamaClientService
import com.example.localai.settings.LocalAiSettingsState
import com.example.localai.ui.panels.ComposerPanel
import com.example.localai.ui.panels.HeaderPanel
import com.example.localai.ui.panels.StatusPanel
import com.example.localai.ui.panels.TimelinePanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * Main tool window panel — slim shell that composes sub-panels.
 */
class LocalAiToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val chatSession: ChatSessionService = project.getService(ChatSessionService::class.java)
    private val contextAssembler: IdeContextService = project.getService(IdeContextService::class.java)

    // === Sub-panels ===
    private val headerPanel = HeaderPanel()
    private val composerPanel = ComposerPanel()
    private val timelinePanel = TimelinePanel()
    private val statusPanel = StatusPanel()

    // === Message area (stays here — complex rendering) ===
    private val messageListPanel = JPanel()
    private val contextChipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

    init {
        buildLayout()
        setupListeners()
        chatSession.onStateChanged = { SwingUtilities.invokeLater { refreshUI() } }
        refreshContextChips()
        loadModels()
    }

    // ==================== LAYOUT ====================

    private fun buildLayout() {
        // Context chips
        contextChipsPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            EmptyBorder(2, 4, 2, 4)
        )

        // Message list
        messageListPanel.layout = BoxLayout(messageListPanel, BoxLayout.Y_AXIS)
        messageListPanel.border = EmptyBorder(8, 8, 8, 8)
        val messageScroll = JBScrollPane(messageListPanel)
        messageScroll.border = BorderFactory.createEmptyBorder()

        // Center = messages + timeline
        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(messageScroll, BorderLayout.CENTER)
        centerPanel.add(timelinePanel, BorderLayout.SOUTH)

        // Top = header + context chips
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(headerPanel)
        topPanel.add(contextChipsPanel)

        // Bottom = composer + status
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(composerPanel, BorderLayout.CENTER)
        bottomPanel.add(statusPanel, BorderLayout.SOUTH)

        add(topPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    // ==================== LISTENERS ====================

    private fun setupListeners() {
        composerPanel.sendButton.addActionListener { sendMessage() }
        composerPanel.stopButton.addActionListener {
            chatSession.stopExecution()
            composerPanel.stopButton.isEnabled = false
        }
        composerPanel.clearButton.addActionListener {
            chatSession.clearSession()
            headerPanel.targetLabel.text = "Target: —"
            refreshUI()
        }
        headerPanel.refreshModelsButton.addActionListener { loadModels() }
        headerPanel.modeCombo.addActionListener {
            chatSession.currentMode = headerPanel.modeCombo.selectedItem as ChatMode
        }
        headerPanel.modelCombo.addActionListener {
            val selected = headerPanel.modelCombo.selectedItem as? String
            if (selected != null) {
                chatSession.currentModel = selected
            }
        }

        // Ctrl+Enter to send
        composerPanel.composerInput.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && e.isControlDown) {
                    e.consume()
                    sendMessage()
                }
            }
        })
    }

    // ==================== ACTIONS ====================

    private fun sendMessage() {
        val text = composerPanel.composerInput.text?.trim() ?: return
        if (text.isEmpty() || chatSession.isBusy) return

        composerPanel.composerInput.text = ""
        statusPanel.setStatus("Processing...")
        composerPanel.stopButton.isEnabled = true
        composerPanel.sendButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            chatSession.sendMessage(text)
            SwingUtilities.invokeLater {
                statusPanel.setStatus("Ready")
                composerPanel.stopButton.isEnabled = false
                composerPanel.sendButton.isEnabled = true
                val interp = chatSession.sessionState.requestInterpretation
                if (interp != null) {
                    val targetName = interp.primaryTarget.substringAfterLast("/")
                    headerPanel.targetLabel.text = "Target: $targetName | Type: ${interp.taskType} | Repo: ${chatSession.sessionState.repoType}"
                }
                refreshContextChips()
            }
        }
    }

    private fun loadModels() {
        statusPanel.setStatus("Loading models...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val ollamaClient = ApplicationManager.getApplication().getService(OllamaClientService::class.java)
            val models = ollamaClient.listModels()
            SwingUtilities.invokeLater {
                val currentModel = chatSession.currentModel
                val comboModel = DefaultComboBoxModel<String>()
                for (m in models) comboModel.addElement(m)
                if (models.isEmpty()) {
                    comboModel.addElement(LocalAiSettingsState.instance.defaultModel)
                }
                headerPanel.modelCombo.model = comboModel
                if (models.contains(currentModel)) {
                    headerPanel.modelCombo.selectedItem = currentModel
                } else if (models.isNotEmpty()) {
                    headerPanel.modelCombo.selectedIndex = 0
                    chatSession.currentModel = models[0]
                }
                val available = ollamaClient.isAvailable()
                statusPanel.setStatus(if (available) "Ollama connected" else "Ollama not available")
            }
        }
    }

    // ==================== REFRESH ====================

    private fun refreshUI() {
        refreshMessages()
        refreshTimeline()
        refreshContextChips()
        revalidate()
        repaint()
    }

    private fun refreshMessages() {
        messageListPanel.removeAll()
        for (msg in chatSession.messages) {
            val bubble = createMessageBubble(msg)
            messageListPanel.add(bubble)
            messageListPanel.add(Box.createVerticalStrut(6))
        }
        if (chatSession.currentMode == ChatMode.PLAN && chatSession.lastPlanResponse != null) {
            val planCard = createPlanCard(chatSession.lastPlanResponse!!)
            messageListPanel.add(planCard)
        }
        messageListPanel.revalidate()
        messageListPanel.repaint()
    }

    private fun refreshTimeline() {
        val allEntries = chatSession.timeline + chatSession.sessionState.timeline
        timelinePanel.refresh(allEntries)
    }

    private fun refreshContextChips() {
        contextChipsPanel.removeAll()
        val context = contextAssembler.assembleContext()
        context.projectRoot?.let { contextChipsPanel.add(createChip("Project: ${it.substringAfterLast('/')}")) }
        context.currentFilePath?.let { contextChipsPanel.add(createChip("File: $it")) }
        context.selectedText?.let { contextChipsPanel.add(createChip("Selection (${it.length} chars)")) }
        context.editorFragment?.let { contextChipsPanel.add(createChip("Editor context")) }
        if (context.openFileHints.isNotEmpty()) {
            contextChipsPanel.add(createChip("${context.openFileHints.size} open files"))
        }
        contextChipsPanel.add(createChip("Embed: ${context.embeddingModel}"))
        contextChipsPanel.revalidate()
        contextChipsPanel.repaint()
    }

    // ==================== RENDERING ====================

    private fun createMessageBubble(msg: ChatMessage): JPanel {
        val panel = JPanel(BorderLayout())

        val headerPanel = JPanel(BorderLayout())
        headerPanel.isOpaque = false
        val roleLabel = JLabel(msg.role.name)
        roleLabel.font = roleLabel.font.deriveFont(Font.BOLD, 11f)
        roleLabel.foreground = when (msg.role) {
            ChatRole.USER -> JBColor(Color(0, 100, 200), Color(100, 180, 255))
            ChatRole.ASSISTANT -> JBColor(Color(0, 130, 80), Color(80, 200, 140))
            ChatRole.TOOL -> JBColor(Color(180, 100, 0), Color(255, 180, 80))
            ChatRole.SYSTEM -> JBColor.GRAY
        }
        headerPanel.add(roleLabel, BorderLayout.WEST)

        val copyButton = javax.swing.JButton("Copy")
        copyButton.font = copyButton.font.deriveFont(9f)
        copyButton.isFocusable = false
        copyButton.toolTipText = "Copy full message to clipboard"
        copyButton.addActionListener {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(msg.content), null)
        }
        headerPanel.add(copyButton, BorderLayout.EAST)

        val contentArea = JTextArea(msg.content)
        contentArea.isEditable = false
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.isOpaque = false
        contentArea.font = Font("Monospaced", Font.PLAIN, 12)
        contentArea.border = EmptyBorder(4, 0, 0, 0)
        contentArea.isFocusable = true
        contentArea.caret.isVisible = true
        contentArea.highlighter = javax.swing.text.DefaultHighlighter()

        val popup = javax.swing.JPopupMenu()
        val copyItem = javax.swing.JMenuItem("Copy")
        copyItem.addActionListener { contentArea.copy() }
        val selectAllItem = javax.swing.JMenuItem("Select All")
        selectAllItem.addActionListener { contentArea.selectAll() }
        popup.add(copyItem)
        popup.add(selectAllItem)
        contentArea.componentPopupMenu = popup

        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(contentArea, BorderLayout.CENTER)

        val bgColor = when (msg.role) {
            ChatRole.USER -> JBColor(Color(240, 245, 255), Color(40, 45, 55))
            ChatRole.ASSISTANT -> JBColor(Color(240, 255, 245), Color(35, 50, 40))
            ChatRole.TOOL -> JBColor(Color(255, 248, 235), Color(50, 45, 35))
            ChatRole.SYSTEM -> JBColor(Color(245, 245, 245), Color(45, 45, 45))
        }
        panel.background = bgColor
        panel.isOpaque = true
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            EmptyBorder(6, 8, 6, 8)
        )
        return panel
    }

    private fun createPlanCard(plan: PlanResponse): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Plan: ${plan.goal}"),
            EmptyBorder(8, 8, 8, 8)
        )
        panel.background = JBColor(Color(245, 248, 255), Color(35, 40, 50))
        panel.isOpaque = true

        for (step in plan.steps) {
            val stepPanel = JPanel(BorderLayout())
            stepPanel.isOpaque = false
            stepPanel.border = EmptyBorder(4, 0, 4, 0)
            val checkbox = javax.swing.JCheckBox("${step.step}. ${step.title}")
            checkbox.isEnabled = false
            checkbox.font = checkbox.font.deriveFont(Font.BOLD)
            val descLabel = JLabel("<html><p style='margin-left:24px'>${step.description}</p></html>")
            descLabel.font = descLabel.font.deriveFont(11f)
            stepPanel.add(checkbox, BorderLayout.NORTH)
            stepPanel.add(descLabel, BorderLayout.CENTER)
            panel.add(stepPanel)
        }
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun createChip(text: String): JLabel {
        val label = JLabel(text)
        label.font = label.font.deriveFont(10f)
        label.isOpaque = true
        label.background = JBColor(Color(230, 235, 245), Color(55, 60, 70))
        label.foreground = JBColor(Color(60, 70, 90), Color(180, 190, 210))
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(200, 210, 225), Color(70, 75, 85))),
            EmptyBorder(2, 6, 2, 6)
        )
        return label
    }
}
