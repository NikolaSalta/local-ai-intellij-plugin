package com.example.localai.ui.panels

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder

/**
 * Composer panel: input text area + send/stop/clear buttons.
 */
class ComposerPanel : JPanel(BorderLayout(4, 4)) {

    val composerInput = JTextArea(3, 40)
    val sendButton = JButton("Send")
    val stopButton = JButton("■ Stop")
    val clearButton = JButton("Clear")

    init {
        border = BorderFactory.createCompoundBorder(
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

        add(JBScrollPane(composerInput), BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.EAST)
    }
}
