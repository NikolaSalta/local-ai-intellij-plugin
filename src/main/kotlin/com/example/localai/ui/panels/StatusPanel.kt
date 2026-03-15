package com.example.localai.ui.panels

import com.intellij.ui.JBColor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Status bar panel: displays current pipeline status.
 */
class StatusPanel : JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)) {

    val statusLabel = JLabel("Ready")

    init {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        statusLabel.font = statusLabel.font.deriveFont(Font.ITALIC, 11f)
        add(statusLabel)
    }

    fun setStatus(text: String) {
        statusLabel.text = text
    }
}
