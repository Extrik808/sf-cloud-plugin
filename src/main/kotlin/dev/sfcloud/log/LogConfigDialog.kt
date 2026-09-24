package dev.sfcloud.log

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import dev.sfcloud.api.SfApiException
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.ui.SfUi
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class LogConfigDialog private constructor(
    private val project: Project,
    private val org: String?,
    private val connectionName: String,
    private val original: LoggingConfiguration,
    private val users: List<TracedEntity>,
    private val apexTypes: List<TracedEntity>,
) : DialogWrapper(project, true) {
    private val initialLevels = original.debugLevels.map { it.copy() }.sortedBy { it.name.lowercase() }
    private val initialFlags = original.traceFlags.map { flag -> flag.copy(debugLevel = initialLevels.firstOrNull { it.id == flag.debugLevel?.id }) }
    private val levelColumns: Array<ColumnInfo<DebugLevelRecord, *>> =
        (listOf<ColumnInfo<DebugLevelRecord, *>>(NameColumn(), PresetColumn()) + LogCategoryKey.entries.map { LevelColumn(it) }).toTypedArray()
    private val flagColumns: Array<ColumnInfo<TraceFlagRecord, *>> = arrayOf(
        TypeColumn(), EntityColumn(), CreatedByColumn(), DateColumn("Start Date", start = true), DateColumn("Expiration Date", start = false), DebugLevelColumn(),
    )
    private val levelModel = ListTableModel(levelColumns, initialLevels.toMutableList(), 0)
    private val flagModel = ListTableModel(flagColumns, initialFlags.toMutableList(), 0)
    private val levels: List<DebugLevelRecord> get() = levelModel.items
    private val flags: List<TraceFlagRecord> get() = flagModel.items
    private val levelTable = TableView(levelModel)
    private val flagTable = TableView(flagModel)

    init {
        title = "Configure Logging for '$connectionName'"
        setOKButtonText("Save")
        setOKButtonTooltip("Saves logging configuration to '$connectionName'")
        init()
    }

    override fun createCenterPanel(): JComponent {
        levelTable.setShowGrid(false)
        flagTable.setShowGrid(false)
        val levelsPanel = ToolbarDecorator.createDecorator(levelTable)
            .setAddAction { addLevel() }
            .setAddActionName("Add Debug Level")
            .setRemoveAction { removeLevels() }
            .setRemoveActionName("Remove Debug Level")
            .setRemoveActionUpdater { levelTable.selectedObjects.isNotEmpty() && levelTable.selectedObjects.none { it.isDevConsole } }
            .createPanel()
        val flagsPanel = ToolbarDecorator.createDecorator(flagTable)
            .setAddAction { addFlag() }
            .setAddActionName("Add Trace Flag")
            .setRemoveAction { removeFlags() }
            .setRemoveActionName("Remove Trace Flag")
            .addExtraAction(object : DumbAwareAction("Refresh Expired Trace Flags", "Moves the expiration date for all expired trace flags into the future", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshExpired()
            })
            .addExtraAction(object : DumbAwareAction("Remove Expired Trace Flags", "Removes all expired trace flags", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) = removeExpired()
            })
            .createPanel()
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.gridx = 0
        c.weightx = 1.0
        c.fill = GridBagConstraints.BOTH
        c.insets = JBUI.insets(4, 0)
        c.gridy = 0
        panel.add(JBLabel("Debug Levels"), c)
        c.gridy = 1
        c.weighty = 1.0
        panel.add(levelsPanel, c)
        c.gridy = 2
        c.weighty = 0.0
        panel.add(JBLabel("Trace Flags"), c)
        c.gridy = 3
        c.weighty = 1.0
        panel.add(flagsPanel, c)
        panel.preferredSize = JBUI.size(1100, 620)
        return panel
    }

    override fun doValidateAll(): List<ValidationInfo> {
        val issues = mutableListOf<String>()
        if (flags.any { it.type == null }) issues += "All trace flags must specify a log type."
        if (flags.any { flag -> flag.entity == null || flag.type?.tracesUsers == flag.entity?.isApex }) {
            issues += "All trace flags must specify a traced entity of the appropriate type."
        }
        if (flags.any { it.debugLevel == null }) issues += "All trace flags must specify a debug level."
        val names = levels.map { it.name }
        if (levels.any { !DebugLevelRecord.isValidName(it.name) } || names.size != names.toSet().size) issues += NAME_ERROR
        return issues.map { ValidationInfo(it) }
    }

    override fun doOKAction() {
        val issues = doValidateAll()
        if (issues.isNotEmpty()) {
            Messages.showErrorDialog(
                project,
                "<html>The logging configuration is invalid. The following issues must be resolved before it can be saved:<ul>" +
                    issues.joinToString("") { "<li>${it.message}</li>" } + "</ul></html>",
                "Invalid Configuration",
            )
            return
        }
        super.doOKAction()
        val updated = LoggingConfiguration(levels.toList(), flags.toList())
        object : Task.Backgroundable(project, "Updating Logging Configuration for '$connectionName'", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val report = LoggingConfig(project).save(org, original, updated, indicator)
                    val body = if (report.isEmpty()) "No changes." else report.joinToString("<br>") { "• $it" }
                    SfUi.notify(project, LogAnalyzerPanel.LOG_GROUP, "Logging Configuration Updated for '$connectionName'", body)
                } catch (e: SfApiException) {
                    SfUi.notify(project, LogAnalyzerPanel.LOG_GROUP, "Logging Configuration Update Failed for '$connectionName'", "ERROR - ${e.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun addLevel() {
        var name = "NewDebugLevel"
        var suffix = 1
        while (levels.any { it.name == name }) name = "NewDebugLevel${suffix++}"
        levelModel.addRow(DebugLevelRecord(null, name, LogLevels.DEFAULT))
        levelTable.selection = listOf(levels.last())
    }

    private fun removeLevels() {
        val selected = levelTable.selectedObjects.filterNot { it.isDevConsole }
        if (selected.isEmpty()) return
        val referencing = flags.filter { it.debugLevel in selected }
        if (referencing.isNotEmpty()) {
            val answer = Messages.showOkCancelDialog(
                project,
                "The selected debug level(s) is/are referenced by one or more trace flags. If you remove referenced debug level(s), the referencing trace flag(s) will be removed as well.",
                SfUi.TITLE,
                "Remove",
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            )
            if (answer != Messages.OK) return
            referencing.forEach { flagModel.removeRow(flags.indexOf(it)) }
        }
        selected.forEach { levelModel.removeRow(levels.indexOf(it)) }
    }

    private fun addFlag() {
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        flagModel.addRow(TraceFlagRecord(null, TraceFlagType.USER_DEBUG, null, null, now, now.plus(1, ChronoUnit.HOURS), levels.firstOrNull { it.isDevConsole }))
        flagTable.selection = listOf(flags.last())
    }

    private fun removeFlags() {
        flagTable.selectedObjects.toList().forEach { flagModel.removeRow(flags.indexOf(it)) }
    }

    private fun refreshExpired() {
        val expired = flags.filter { it.isExpired }
        if (expired.isEmpty()) return
        val choice = Messages.showDialog(
            project,
            "You may extend all expired trace flags by two hours or to the maximum allowed expiration date (24-hours from now).",
            "Refresh Expired Trace Flags",
            arrayOf("Two Hours", "Maximum", Messages.getCancelButton()),
            0,
            Messages.getQuestionIcon(),
        )
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val expiration = when (choice) {
            0 -> now.plus(2, ChronoUnit.HOURS)
            1 -> now.plus(LoggingConfig.MAX_TRACE_HOURS, ChronoUnit.HOURS).minus(1, ChronoUnit.MINUTES)
            else -> return
        }
        expired.forEach {
            it.startDate = now
            it.expirationDate = expiration
        }
        flagModel.fireTableDataChanged()
    }

    private fun removeExpired() {
        val expired = flags.filter { it.isExpired }
        if (expired.isEmpty()) return
        if (Messages.showYesNoDialog(project, "Remove ${expired.size} expired trace flag(s)?", SfUi.TITLE, Messages.getQuestionIcon()) != Messages.YES) return
        expired.forEach { flagModel.removeRow(flags.indexOf(it)) }
    }

    private fun <T> combo(values: List<T>, render: (T?) -> String): TableCellEditor {
        val box = ComboBox(values.toTypedArray<Any?>())
        box.renderer = com.intellij.ui.SimpleListCellRenderer.create("<Select>") { render(it as T?) }
        return DefaultCellEditor(box)
    }

    private fun renderer(text: (Any?) -> Pair<String, SimpleTextAttributes>, tooltip: (Any?) -> String? = { null }): TableCellRenderer =
        object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                val (label, attributes) = text(value)
                append(label, attributes)
                toolTipText = tooltip(value)
            }
        }

    private fun selectLabel(value: String?): Pair<String, SimpleTextAttributes> =
        if (value == null) "<Select>" to SimpleTextAttributes.GRAYED_ATTRIBUTES else value to SimpleTextAttributes.REGULAR_ATTRIBUTES

    private inner class NameColumn : ColumnInfo<DebugLevelRecord, String>("Name") {
        override fun valueOf(item: DebugLevelRecord): String = item.name

        override fun isCellEditable(item: DebugLevelRecord): Boolean = !item.isDevConsole

        override fun setValue(item: DebugLevelRecord, value: String?) {
            item.name = value.orEmpty().trim()
        }

        override fun getRenderer(item: DebugLevelRecord): TableCellRenderer = renderer({ value ->
            val name = value as String
            name to if (DebugLevelRecord.isValidName(name) && levels.count { it.name == name } == 1) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES
        }) { if (item.isDevConsole) DEV_CONSOLE_TOOLTIP else if (!DebugLevelRecord.isValidName(item.name)) NAME_ERROR else null }

        override fun getComparator(): Comparator<DebugLevelRecord> = compareBy { it.name.lowercase() }
    }

    private inner class PresetColumn : ColumnInfo<DebugLevelRecord, LogLevelPreset?>("Preset") {
        override fun valueOf(item: DebugLevelRecord): LogLevelPreset? = LogLevelPreset.matching(item.levels)

        override fun isCellEditable(item: DebugLevelRecord): Boolean = true

        override fun setValue(item: DebugLevelRecord, value: LogLevelPreset?) {
            if (value != null) item.levels = value.levels
            levelModel.fireTableRowsUpdated(levels.indexOf(item), levels.indexOf(item))
        }

        override fun getEditor(item: DebugLevelRecord): TableCellEditor = combo(LogLevelPreset.entries) { it?.displayName ?: "<Select>" }

        override fun getRenderer(item: DebugLevelRecord): TableCellRenderer =
            renderer({ selectLabel((it as LogLevelPreset?)?.displayName) }) { (it as LogLevelPreset?)?.description ?: "Select a pre-defined logging level." }
    }

    private inner class LevelColumn(private val key: LogCategoryKey) : ColumnInfo<DebugLevelRecord, ApexLogLevel>(key.label) {
        override fun valueOf(item: DebugLevelRecord): ApexLogLevel = item.levels[key]

        override fun isCellEditable(item: DebugLevelRecord): Boolean = true

        override fun setValue(item: DebugLevelRecord, value: ApexLogLevel?) {
            if (value != null) item.levels = item.levels.with(key, value)
            levelModel.fireTableRowsUpdated(levels.indexOf(item), levels.indexOf(item))
        }

        override fun getEditor(item: DebugLevelRecord): TableCellEditor = combo(ApexLogLevel.entries) { it?.name.orEmpty() }

        override fun getTooltipText(): String = key.tooltip
    }

    private inner class TypeColumn : ColumnInfo<TraceFlagRecord, TraceFlagType?>("Type") {
        override fun valueOf(item: TraceFlagRecord): TraceFlagType? = item.type

        override fun isCellEditable(item: TraceFlagRecord): Boolean = item.id == null

        override fun setValue(item: TraceFlagRecord, value: TraceFlagType?) {
            if (value?.tracesUsers != item.type?.tracesUsers) item.entity = null
            item.type = value
        }

        override fun getEditor(item: TraceFlagRecord): TableCellEditor = combo(TraceFlagType.entries) { it?.label ?: "<Select>" }

        override fun getRenderer(item: TraceFlagRecord): TableCellRenderer =
            renderer({ selectLabel((it as TraceFlagType?)?.label) }) { if (it == null) "Select a trace flag type." else null }
    }

    private inner class EntityColumn : ColumnInfo<TraceFlagRecord, TracedEntity?>("Traced Entity") {
        override fun valueOf(item: TraceFlagRecord): TracedEntity? = item.entity

        override fun isCellEditable(item: TraceFlagRecord): Boolean = item.id == null && item.type != null

        override fun setValue(item: TraceFlagRecord, value: TracedEntity?) {
            item.entity = value
        }

        override fun getEditor(item: TraceFlagRecord): TableCellEditor {
            val box = ComboBox((if (item.type?.tracesUsers == false) apexTypes else users).toTypedArray<TracedEntity?>())
            box.renderer = com.intellij.ui.SimpleListCellRenderer.create("<Select>") { it?.label.orEmpty() }
            box.isSwingPopup = false
            com.intellij.ui.ComboboxSpeedSearch.installOn(box)
            return DefaultCellEditor(box)
        }

        override fun getRenderer(item: TraceFlagRecord): TableCellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                val entity = value as TracedEntity?
                if (entity == null) {
                    append("<Select>", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = "Select a traced entity."
                    return
                }
                icon = when {
                    entity.id.startsWith("01q") -> dev.sfcloud.lang.SfCloudIcons.Trigger
                    entity.isApex -> dev.sfcloud.lang.SfCloudIcons.Apex
                    entity.name == "Automated Process" -> AllIcons.Nodes.Deploy
                    else -> AllIcons.General.User
                }
                append(entity.label)
            }
        }
    }

    private inner class CreatedByColumn : ColumnInfo<TraceFlagRecord, String>("Created By") {
        override fun valueOf(item: TraceFlagRecord): String = item.createdBy ?: "(populated on save)"
    }

    private inner class DateColumn(name: String, private val start: Boolean) : ColumnInfo<TraceFlagRecord, Instant?>(name) {
        override fun valueOf(item: TraceFlagRecord): Instant? = if (start) item.startDate else item.expirationDate

        override fun isCellEditable(item: TraceFlagRecord): Boolean = true

        override fun setValue(item: TraceFlagRecord, value: Instant?) {
            if (start) item.startDate = value else item.expirationDate = value
            flagModel.fireTableRowsUpdated(flags.indexOf(item), flags.indexOf(item))
        }

        override fun getEditor(item: TraceFlagRecord): TableCellEditor {
            val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
            val base = if (start) now else (item.startDate ?: now).truncatedTo(ChronoUnit.MINUTES)
            val slots = (0..48).map { base.plus(it * 30L, ChronoUnit.MINUTES) }
            val current = valueOf(item)
            val values = (listOfNotNull(current) + slots).distinct()
            return combo(values) { SfUi.formatDate(it) }
        }

        override fun getRenderer(item: TraceFlagRecord): TableCellRenderer = renderer({ value ->
            val instant = value as Instant?
            val expired = !start && instant?.isBefore(Instant.now()) == true
            SfUi.formatDate(instant) to if (expired) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        }) { value -> if (!start && (value as Instant?)?.isBefore(Instant.now()) == true) "This trace flag has expired." else null }
    }

    private inner class DebugLevelColumn : ColumnInfo<TraceFlagRecord, DebugLevelRecord?>("Debug Level") {
        override fun valueOf(item: TraceFlagRecord): DebugLevelRecord? = item.debugLevel

        override fun isCellEditable(item: TraceFlagRecord): Boolean = true

        override fun setValue(item: TraceFlagRecord, value: DebugLevelRecord?) {
            item.debugLevel = value
        }

        override fun getEditor(item: TraceFlagRecord): TableCellEditor = combo(levels.toList()) { it?.name ?: "<Select>" }

        override fun getRenderer(item: TraceFlagRecord): TableCellRenderer =
            renderer({ selectLabel((it as DebugLevelRecord?)?.name) }) { if (it == null) "Select a debug level." else null }
    }

    companion object {
        private const val NAME_ERROR = "The Debug Level API Name can only contain underscores and alphanumeric characters. It must be unique, begin with a letter, not include spaces, not end with an underscore, and not contain two consecutive underscores."
        private const val DEV_CONSOLE_TOOLTIP = "The SFDC_DevConsole debug level is managed by SF Cloud and may not be renamed or deleted."

        fun show(project: Project, org: String?, connectionName: String) {
            object : Task.Backgroundable(project, "Loading Logging Configuration for '$connectionName'", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val config = LoggingConfig(project)
                        val configuration = config.load(org, indicator)
                        indicator.text2 = "Loading users"
                        val users = config.users(org, indicator)
                        indicator.text2 = "Loading Apex classes and triggers"
                        val apexTypes = config.apexTypes(org, indicator)
                        SfUi.edt(project) { LogConfigDialog(project, org, connectionName, configuration, users, apexTypes).show() }
                    } catch (e: SfApiException) {
                        SfNotifier.error(project, "Cannot load logging configuration for '$connectionName'", e.message.orEmpty())
                    }
                }
            }.queue()
        }
    }
}
