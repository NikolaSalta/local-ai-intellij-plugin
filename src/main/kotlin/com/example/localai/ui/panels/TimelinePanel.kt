package com.example.localai.ui.panels

import com.example.localai.model.TimelineEntry
import com.example.localai.model.TimelineEventType
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Timeline panel: displays pipeline execution events with icons and timestamps.
 */
class TimelinePanel : JPanel(BorderLayout()) {

    private val listPanel = JPanel()
    private val dateFormat = SimpleDateFormat("HH:mm:ss")

    init {
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        listPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Timeline"),
            EmptyBorder(4, 4, 4, 4)
        )
        preferredSize = Dimension(0, 120)

        val scroll = JBScrollPane(listPanel)
        scroll.border = BorderFactory.createEmptyBorder()
        add(scroll, BorderLayout.CENTER)
    }

    fun refresh(entries: List<TimelineEntry>) {
        listPanel.removeAll()

        val sorted = entries
            .distinctBy { "${it.timestamp}_${it.eventType}_${it.description}" }
            .sortedBy { it.timestamp }

        for (entry in sorted) {
            val icon = getIcon(entry.eventType)
            val label = JLabel("$icon ${dateFormat.format(Date(entry.timestamp))} ${entry.description}")
            label.font = Font("Monospaced", Font.PLAIN, 10)
            label.foreground = getColor(entry.eventType)
            label.border = EmptyBorder(1, 4, 1, 4)
            listPanel.add(label)
        }

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun getIcon(type: TimelineEventType): String = when (type) {
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

    private fun getColor(type: TimelineEventType): JBColor = when (type) {
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
        else -> JBColor(JBColor.foreground(), JBColor.foreground())
    }
}
