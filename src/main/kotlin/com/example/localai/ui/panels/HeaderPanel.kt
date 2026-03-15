package com.example.localai.ui.panels

import com.example.localai.model.ChatMode
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Header panel: mode selector, model selector, refresh button, target display.
 */
class HeaderPanel : JPanel() {

    val modeCombo = JComboBox(ChatMode.entries.toTypedArray())
    val modelCombo = JComboBox<String>()
    val refreshModelsButton = JButton("↻")
    val targetLabel = JLabel("Target: —")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        row1.add(JLabel("Mode:"))
        modeCombo.selectedItem = ChatMode.ASK
        row1.add(modeCombo)
        row1.add(JLabel("Model:"))
        modelCombo.preferredSize = Dimension(200, modelCombo.preferredSize.height)
        row1.add(modelCombo)
        refreshModelsButton.toolTipText = "Refresh model list from Ollama"
        row1.add(refreshModelsButton)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        targetLabel.font = targetLabel.font.deriveFont(Font.ITALIC, 10f)
        targetLabel.foreground = JBColor(Color(80, 100, 130), Color(140, 160, 190))
        row2.add(targetLabel)

        add(row1)
        add(row2)
    }
}
