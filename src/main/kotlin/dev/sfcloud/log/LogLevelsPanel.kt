package dev.sfcloud.log

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JList
import javax.swing.JPanel

class LogLevelsPanel(initial: LogLevels, private val onChange: (LogLevels) -> Unit) : JPanel(GridBagLayout()) {
    private val presetCombo = ComboBox(DefaultComboBoxModel(arrayOf<LogLevelPreset?>(null) + LogLevelPreset.entries))
    private val levelCombos = LogCategoryKey.entries.associateWith { ComboBox(ApexLogLevel.entries.toTypedArray()) }
    private var updating = false

    var levels: LogLevels = initial
        private set

    init {
        presetCombo.toolTipText = "Pre-defined logging levels."
        presetCombo.renderer = object : ColoredListCellRenderer<LogLevelPreset?>() {
            override fun customizeCellRenderer(list: JList<out LogLevelPreset?>, value: LogLevelPreset?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) {
                    append("<Select>", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = "Select a pre-defined logging level."
                } else {
                    append(value.label)
                    append(" (${value.category.label})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = value.description
                }
            }
        }
        presetCombo.addActionListener {
            if (updating) return@addActionListener
            val preset = presetCombo.selectedItem as? LogLevelPreset ?: return@addActionListener
            apply(preset.levels, fire = true)
        }
        levelCombos.forEach { (key, combo) ->
            combo.toolTipText = key.tooltip
            combo.addActionListener {
                if (updating) return@addActionListener
                val level = combo.selectedItem as? ApexLogLevel ?: return@addActionListener
                apply(levels.with(key, level), fire = true)
            }
        }

        val c = GridBagConstraints()
        c.fill = GridBagConstraints.HORIZONTAL
        c.insets = JBUI.insets(0, 1)
        column(c, 0, "Preset", presetCombo.toolTipText, presetCombo, 0.0)
        LogCategoryKey.entries.forEachIndexed { index, key ->
            column(c, index + 1, key.label, key.tooltip, levelCombos.getValue(key), 1.0)
        }
        apply(initial, fire = false)
    }

    fun setLevels(value: LogLevels) = apply(value, fire = false)

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        presetCombo.isEnabled = enabled
        levelCombos.values.forEach { it.isEnabled = enabled }
    }

    private fun column(c: GridBagConstraints, x: Int, label: String, tooltip: String, component: java.awt.Component, weight: Double) {
        c.gridx = x
        c.weightx = weight
        c.gridy = 0
        add(JBLabel(label).apply { toolTipText = tooltip; componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL }, c)
        c.gridy = 1
        add(component, c)
    }

    private fun apply(value: LogLevels, fire: Boolean) {
        updating = true
        try {
            levels = value
            levelCombos.forEach { (key, combo) -> combo.selectedItem = value[key] }
            presetCombo.selectedItem = LogLevelPreset.matching(value)
        } finally {
            updating = false
        }
        if (fire) onChange(value)
    }
}
