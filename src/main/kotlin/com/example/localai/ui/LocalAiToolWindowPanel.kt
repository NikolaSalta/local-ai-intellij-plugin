package com.example.localai.ui

import com.example.localai.model.ChatMessage
import com.example.localai.model.ChatMode
import com.example.localai.model.ChatRole
import com.example.localai.model.PlanResponse
import com.example.localai.model.TimelineEntry
import com.example.localai.model.TimelineEventType
import com.example.localai.services.ChatSessionService
import com.example.localai.context.IdeContextService
import com.example.localai.llm.OllamaClientService
import com.example.localai.settings.LocalAiSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * Main tool window panel composing the Local AI chat UI.
 */
class LocalAiToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val chatSession: ChatSessionService = project.getService(ChatSessionService::class.java)
    private val contextAssembler: IdeContextService = project.getService(IdeContextService::class.java)

    private val messageListPanel = JPanel()
    private val contextChipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    private val timelinePanel = JPanel()
    private val composerInput = JTextArea(3, 40)
    private val statusLabel = JLabel("Ready")
    private val targetLabel = JLabel("Target: —")
    private val modeCombo = JComboBox(ChatMode.entries.toTypedArray())
    private val modelCombo = JComboBox<String>()
    private val sendButton = JButton("Send")
    private val stopButton = JButton("■ Stop")
    private val clearButton = JButton("Clear")
    private val refreshModelsButton = JButton("↻")

    init {
        buildUI()
        setupListeners()
        chatSession.onStateChanged = { SwingUtilities.invokeLater { refreshUI() } }
        refreshContextChips()
        loadModels()
    }

    private fun buildUI() {
        // === HEADER ===
        val headerRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        headerRow1.add(JLabel("Mode:"))
        modeCombo.selectedItem = ChatMode.ASK
        headerRow1.add(modeCombo)
        headerRow1.add(JLabel("Model:"))
        modelCombo.preferredSize = Dimension(200, modelCombo.preferredSize.height)
        headerRow1.add(modelCombo)
        refreshModelsButton.toolTipText = "Refresh model list from Ollama"
        headerRow1.add(refreshModelsButton)

        val headerRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        targetLabel.font = targetLabel.font.deriveFont(Font.ITALIC, 10f)
        targetLabel.foreground = JBColor(Color(80, 100, 130), Color(140, 160, 190))
        headerRow2.add(targetLabel)

        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
        headerPanel.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        headerPanel.add(headerRow1)
        headerPanel.add(headerRow2)

        // === CONTEXT CHIPS ===
        contextChipsPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            EmptyBorder(2, 4, 2, 4)
        )

        // === MESSAGE LIST + TIMELINE AREA ===
        val centerPanel = JPanel(BorderLayout())

        messageListPanel.layout = BoxLayout(messageListPanel, BoxLayout.Y_AXIS)
        messageListPanel.border = EmptyBorder(8, 8, 8, 8)
        val messageScroll = JBScrollPane(messageListPanel)
        messageScroll.border = BorderFactory.createEmptyBorder()

        timelinePanel.layout = BoxLayout(timelinePanel, BoxLayout.Y_AXIS)
        timelinePanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Timeline"),
            EmptyBorder(4, 4, 4, 4)
        )
        timelinePanel.preferredSize = Dimension(0, 120)
        val timelineScroll = JBScrollPane(timelinePanel)
        timelineScroll.border = BorderFactory.createEmptyBorder()

        centerPanel.add(messageScroll, BorderLayout.CENTER)
        centerPanel.add(timelineScroll, BorderLayout.SOUTH)

        // === COMPOSER ===
        val composerPanel = JPanel(BorderLayout(4, 4))
        composerPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            EmptyBorder(8, 8, 8, 8)
        )
        composerInput.lineWrap = true
        composerInput.wrapStyleWord = true
        composerInput.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            EmptyBorder(4, 6, 4, 6)
        )
        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.Y_AXIS)
        sendButton.alignmentX = Component.CENTER_ALIGNMENT
        stopButton.alignmentX = Component.CENTER_ALIGNMENT
        clearButton.alignmentX = Component.CENTER_ALIGNMENT
        stopButton.foreground = JBColor(Color(200, 50, 50), Color(255, 100, 100))
        stopButton.isEnabled = false
        buttonPanel.add(sendButton)
        buttonPanel.add(Box.createVerticalStrut(4))
        buttonPanel.add(stopButton)
        buttonPanel.add(Box.createVerticalStrut(4))
        buttonPanel.add(clearButton)

        composerPanel.add(JBScrollPane(composerInput), BorderLayout.CENTER)
        composerPanel.add(buttonPanel, BorderLayout.EAST)

        // === STATUS ===
        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        statusPanel.border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        statusLabel.font = statusLabel.font.deriveFont(Font.ITALIC, 11f)
        statusPanel.add(statusLabel)

        // === BOTTOM ===
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(composerPanel, BorderLayout.CENTER)
        bottomPanel.add(statusPanel, BorderLayout.SOUTH)

        // === MAIN LAYOUT ===
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(headerPanel)
        topPanel.add(contextChipsPanel)

        add(topPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        sendButton.addActionListener { sendMessage() }
        stopButton.addActionListener {
            chatSession.stopExecution()
            stopButton.isEnabled = false
        }
        clearButton.addActionListener {
            chatSession.clearSession()
            targetLabel.text = "Target: —"
            refreshUI()
        }
        refreshModelsButton.addActionListener { loadModels() }
        modeCombo.addActionListener {
            chatSession.currentMode = modeCombo.selectedItem as ChatMode
        }
        modelCombo.addActionListener {
            val selected = modelCombo.selectedItem as? String
            if (selected != null) {
                chatSession.currentModel = selected
            }
        }

        // Allow Ctrl+Enter to send
        composerInput.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && e.isControlDown) {
                    e.consume()
                    sendMessage()
                }
            }
        })
    }

    private fun sendMessage() {
        val text = composerInput.text?.trim() ?: return
        if (text.isEmpty() || chatSession.isBusy) return

        composerInput.text = ""
        statusLabel.text = "Processing..."
        stopButton.isEnabled = true
        sendButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            chatSession.sendMessage(text)
            SwingUtilities.invokeLater {
                statusLabel.text = "Ready"
                stopButton.isEnabled = false
                sendButton.isEnabled = true
                // Update target display
                val interp = chatSession.sessionState.requestInterpretation
                if (interp != null) {
                    val targetName = interp.primaryTarget.substringAfterLast("/")
                    targetLabel.text = "Target: $targetName | Type: ${interp.taskType} | Repo: ${chatSession.sessionState.repoType}"
                }
                refreshContextChips()
            }
        }
    }

    private fun loadModels() {
        statusLabel.text = "Loading models..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val ollamaClient = ApplicationManager.getApplication().getService(OllamaClientService::class.java)
            val models = ollamaClient.listModels()
            SwingUtilities.invokeLater {
                val currentModel = chatSession.currentModel
                val comboModel = DefaultComboBoxModel<String>()
                for (m in models) {
                    comboModel.addElement(m)
                }
                if (models.isEmpty()) {
                    comboModel.addElement(LocalAiSettingsState.instance.defaultModel)
                }
                modelCombo.model = comboModel
                if (models.contains(currentModel)) {
                    modelCombo.selectedItem = currentModel
                } else if (models.isNotEmpty()) {
                    modelCombo.selectedIndex = 0
                    chatSession.currentModel = models[0]
                }
                val available = ollamaClient.isAvailable()
                statusLabel.text = if (available) "Ollama connected" else "Ollama not available"
            }
        }
    }

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

        // Render plan card if in PLAN mode and we have plan data
        if (chatSession.currentMode == ChatMode.PLAN && chatSession.lastPlanResponse != null) {
            val planCard = createPlanCard(chatSession.lastPlanResponse!!)
            messageListPanel.add(planCard)
        }

        messageListPanel.revalidate()
        messageListPanel.repaint()
    }

    private fun createMessageBubble(msg: ChatMessage): JPanel {
        val panel = JPanel(BorderLayout())

        // -- Header: role label + copy button --
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

        val copyButton = JButton("Copy")
        copyButton.font = copyButton.font.deriveFont(9f)
        copyButton.isFocusable = false
        copyButton.toolTipText = "Copy full message to clipboard"
        copyButton.addActionListener {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(msg.content), null)
        }
        headerPanel.add(copyButton, BorderLayout.EAST)

        // -- Content: selectable, scrollable text area --
        val contentArea = JTextArea(msg.content)
        contentArea.isEditable = false
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.isOpaque = false
        contentArea.font = Font("Monospaced", Font.PLAIN, 12)
        contentArea.border = EmptyBorder(4, 0, 0, 0)

        // Enable text selection and copy
        contentArea.isFocusable = true
        contentArea.caret.isVisible = true
        contentArea.highlighter = javax.swing.text.DefaultHighlighter()

        // Right-click context menu for copy
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

    private fun refreshTimeline() {
        timelinePanel.removeAll()
        val dateFormat = SimpleDateFormat("HH:mm:ss")

        // Merge main timeline + session state timeline
        val allEntries = (chatSession.timeline + chatSession.sessionState.timeline)
            .distinctBy { "${it.timestamp}_${it.eventType}_${it.description}" }
            .sortedBy { it.timestamp }

        for (entry in allEntries) {
            val icon = when (entry.eventType) {
                TimelineEventType.REQUEST_PARSED -> "📥"
                TimelineEventType.TASK_CLASSIFIED -> "🏷"
                TimelineEventType.TARGET_RESOLVED -> "🎯"
                TimelineEventType.STRATEGY_SELECTED -> "⚙"
                TimelineEventType.STRUCTURAL_SCAN_START, TimelineEventType.STRUCTURAL_SCAN_FINISH -> "🔍"
                TimelineEventType.REPO_TYPE_DETECTED -> "📦"
                TimelineEventType.EVIDENCE_GATE_CHECK, TimelineEventType.EVIDENCE_GATE_PASSED -> "✅"
                TimelineEventType.EVIDENCE_GATE_FAILED -> "⚠"
                TimelineEventType.MODEL_CALL_START, TimelineEventType.MODEL_CALL_FINISH -> "🤖"
                TimelineEventType.SYNTHESIS_START, TimelineEventType.SYNTHESIS_FINISH -> "✨"
                TimelineEventType.ARTIFACT_CREATED -> "📄"
                TimelineEventType.FINAL_TEXT -> "✅"
                TimelineEventType.ERROR -> "❌"
                TimelineEventType.STOP_REQUESTED -> "⏹"
                else -> "•"
            }
            val label = JLabel("$icon ${dateFormat.format(Date(entry.timestamp))} ${entry.description}")
            label.font = Font("Monospaced", Font.PLAIN, 10)
            label.foreground = when (entry.eventType) {
                TimelineEventType.ERROR -> JBColor.RED
                TimelineEventType.FINAL_TEXT, TimelineEventType.EVIDENCE_GATE_PASSED ->
                    JBColor(Color(0, 130, 80), Color(80, 200, 140))
                TimelineEventType.TASK_CLASSIFIED, TimelineEventType.TARGET_RESOLVED,
                TimelineEventType.STRATEGY_SELECTED, TimelineEventType.REQUEST_PARSED ->
                    JBColor(Color(50, 90, 180), Color(100, 150, 230))
                TimelineEventType.STRUCTURAL_SCAN_START, TimelineEventType.STRUCTURAL_SCAN_FINISH,
                TimelineEventType.REPO_TYPE_DETECTED ->
                    JBColor(Color(140, 100, 20), Color(220, 180, 80))
                TimelineEventType.STOP_REQUESTED, TimelineEventType.EVIDENCE_GATE_FAILED ->
                    JBColor(Color(200, 100, 0), Color(255, 160, 60))
                else -> JBColor.foreground()
            }
            label.border = EmptyBorder(1, 4, 1, 4)
            timelinePanel.add(label)
        }

        timelinePanel.revalidate()
        timelinePanel.repaint()
    }

    private fun refreshContextChips() {
        contextChipsPanel.removeAll()

        val context = contextAssembler.assembleContext()

        context.projectRoot?.let {
            contextChipsPanel.add(createChip("Project: ${it.substringAfterLast('/')}"))
        }
        context.currentFilePath?.let {
            contextChipsPanel.add(createChip("File: $it"))
        }
        context.selectedText?.let {
            contextChipsPanel.add(createChip("Selection (${it.length} chars)"))
        }
        context.editorFragment?.let {
            contextChipsPanel.add(createChip("Editor context"))
        }
        if (context.openFileHints.isNotEmpty()) {
            contextChipsPanel.add(createChip("${context.openFileHints.size} open files"))
        }
        contextChipsPanel.add(createChip("Embed: ${context.embeddingModel}"))

        contextChipsPanel.revalidate()
        contextChipsPanel.repaint()
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
