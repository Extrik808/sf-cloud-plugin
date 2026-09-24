package dev.sfcloud.log

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.TableView
import com.intellij.util.Alarm
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import dev.sfcloud.api.SfApi
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.core.int
import dev.sfcloud.core.obj
import dev.sfcloud.core.str
import dev.sfcloud.org.ConnectionComboBox
import dev.sfcloud.org.OrgService
import dev.sfcloud.settings.ToolWindowSettings
import dev.sfcloud.ui.SfUi
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

data class LogRow(
    val id: String,
    val user: String,
    val application: String,
    val operation: String,
    val startTime: Instant?,
    val lastModified: Instant?,
    val durationMs: Long,
    val status: String,
    val size: Int,
)

object LogAnalyzerWindow {
    const val ID = "Log Analyzer"

    fun show(project: Project, then: () -> Unit = {}) {
        ToolWindowManager.getInstance(project).getToolWindow(ID)?.show(then)
    }

    fun openLog(project: Project, name: String, text: String, org: String? = null) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        window.show {
            val holder = Disposer.newDisposable("SF Cloud log tab")
            val panel = LogAnalyzerPanel(project, holder, { org })
            panel.show(name, text)
            addTab(window, panel.component, name, holder)
        }
    }

    fun openContext(project: Project, context: LogContext, org: String?) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        window.show {
            val holder = Disposer.newDisposable("SF Cloud log context tab")
            val panel = LogAnalyzerPanel(project, holder, { org }, context = context)
            addTab(window, panel.component, context.title, holder)
        }
    }

    private fun addTab(window: ToolWindow, component: JComponent, name: String, holder: Disposable) {
        val content = ContentFactory.getInstance().createContent(component, name, false)
        content.isCloseable = true
        content.setDisposer(holder)
        window.contentManager.addContent(content)
        window.contentManager.setSelectedContent(content)
    }
}

class LogAnalyzerToolWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean = SfdxProject.isSfdx(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tab = LogAnalyzerMainTab(project, toolWindow.disposable)
        val content: Content = ContentFactory.getInstance().createContent(tab.component, "Default", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown.id == LogAnalyzerWindow.ID) tab.activated()
            }
        })
        tab.activated()
    }
}

class LogAnalyzerMainTab(private val project: Project, parent: Disposable) : Disposable {
    private val settings = ToolWindowSettings.getInstance(project).logAnalyzer
    private val api get() = SfApi.getInstance(project)
    private val connection = ConnectionComboBox(project, this, settings.connection) { key ->
        settings.connection = key.orEmpty()
        connectionChanged()
    }
    private val levelsPanel = LogLevelsPanel(LogLevels.decode(settings.logLevels) ?: LogLevels.DEFAULT) { saveLevels(it) }
    private val filterField = SearchTextField("SfCloud.LogAnalyzer.LogFilter")
    private val filterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val rows = ListTableModel<LogRow>(COLUMNS, mutableListOf())
    private val table = TableView(rows)
    private val tableScroll = JBScrollPane(table)
    private val logPanel = LogAnalyzerPanel(project, this, { connection.selectedKey })
    private val splitter = SfUi.splitter(settings.splitVertical, settings.splitterProportion) { settings.splitterProportion = it }
    private var maximized = false

    @Volatile
    private var loading = false

    @Volatile
    private var tracing = false

    val component: JComponent

    init {
        Disposer.register(parent, this)
        connection.toolTipText = "The Salesforce connection from which logs should be retrieved."
        filterField.toolTipText = "Optional regular expression-based filter."
        filterField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                filterAlarm.cancelAllRequests()
                filterAlarm.addRequest({ refresh() }, 500)
            }
        })
        table.setShowGrid(false)
        table.fillsViewportHeight = true
        table.emptyText.text = "No logs"
        rows.isSortable = true
        table.rowSorter.sortKeys = listOf(javax.swing.RowSorter.SortKey(3, SortOrder.DESCENDING))
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelected()
                return true
            }
        }.installOn(table)
        table.registerKeyboardAction({ openSelected() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)

        splitter.firstComponent = tableScroll
        splitter.secondComponent = logPanel.component

        val filterPanel = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.insets = JBUI.insets(2, 4)
        filterPanel.add(JBLabel(AllIcons.General.Filter).apply { toolTipText = filterField.toolTipText }, c)
        c.weightx = 1.0
        c.fill = GridBagConstraints.HORIZONTAL
        filterPanel.add(filterField, c)

        val header = SfUi.stack(connection, levelsPanel, filterPanel)
        component = SfUi.verticalToolbarPanel("SfCloudLogAnalyzer", toolbar(), SfUi.north(header, splitter))
    }

    fun activated() {
        enableTracing()
        refresh()
    }

    private fun toolbar() = SfUi.group(
        SfUi.action("Refresh", "Refreshes the list of logs.", AllIcons.Actions.Refresh, { !loading }) { refresh() },
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.text = if (maximized) "Restore" else "Maximize"
                e.presentation.description = if (maximized) "Restore the log view" else "Maximize the log view"
                e.presentation.icon = if (maximized) AllIcons.General.CollapseComponent else AllIcons.General.ExpandComponent
            }

            override fun actionPerformed(e: AnActionEvent) {
                maximized = !maximized
                tableScroll.isVisible = !maximized
                splitter.revalidate()
            }
        },
        object : DumbAwareAction("Toggle Splitter", "Toggles the splitter orientation.", null) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.icon = if (splitter.orientation) AllIcons.Actions.SplitVertically else AllIcons.Actions.SplitHorizontally
            }

            override fun actionPerformed(e: AnActionEvent) {
                splitter.orientation = !splitter.orientation
                settings.splitVertical = splitter.orientation
            }
        },
        null,
        SfUi.action("Delete", "Deletes the selected logs from the organization.", AllIcons.Actions.Cancel, { table.selectedObjects.isNotEmpty() }) {
            delete(table.selectedObjects.map { it.id })
        },
        SfUi.action("Delete All", "Deletes all logs from the organization.", AllIcons.Actions.GC) { deleteAll() },
        null,
        SfUi.action("Load External Log", "Loads an external log from a file.", AllIcons.Actions.MenuOpen) { loadExternal() },
        SfUi.action("Paste External Log", "Shows an external log from the clipboard.", AllIcons.Actions.MenuPaste) { pasteExternal() },
        SfUi.action("Save Log(s)", "Saves the selected logs as local files.", AllIcons.Actions.MenuSaveall) { saveLogs() },
        null,
        SfUi.action("Configure Logging", "Configures log levels and trace flags.", AllIcons.General.GearPlain) {
            LogConfigDialog.show(project, connection.selectedKey, connectionName())
        },
    )

    private fun connectionName(): String = connection.selectedOrg?.displayName ?: connection.selectedKey ?: "default org"

    private fun connectionChanged() {
        rows.items = mutableListOf()
        logPanel.clear()
        enableTracing()
        refresh()
    }

    private fun error(title: String): (String) -> Unit = { message ->
        SfUi.notify(project, LogAnalyzerPanel.LOG_GROUP, title, message, NotificationType.ERROR)
    }

    fun refresh() {
        if (loading) return
        loading = true
        val org = connection.selectedKey
        val filter = filterField.text.trim()
        table.emptyText.text = "Retrieving logs…"
        SfUi.background(project, "Retrieving Logs", onError = {
            loading = false
            SfUi.edt(project) { table.emptyText.text = "No logs" }
            error("Cannot retrieve logs")(it)
        }) { indicator ->
            indicator.text2 = "Retrieving log information"
            val where = if (filter.isEmpty()) "" else {
                val value = SfApi.escapeSoql(filter)
                " WHERE Application LIKE '%$value%' OR Operation LIKE '%$value%' OR Status LIKE '%$value%'" +
                    " OR LogUser.Username LIKE '%$value%' OR LogUser.Name LIKE '%$value%'"
            }
            val result = api.query(
                org,
                "SELECT Id, Application, LogUser.Username, LogUser.Name, Operation, StartTime, LastModifiedDate, " +
                    "DurationMilliseconds, Status, Location, LogLength FROM ApexLog$where ORDER BY StartTime DESC LIMIT 2500",
                indicator = indicator,
            )
            val loaded = result.records.map { record ->
                LogRow(
                    id = record.str("Id").orEmpty(),
                    user = record.obj("LogUser")?.str("Name").orEmpty(),
                    application = record.str("Application").orEmpty(),
                    operation = record.str("Operation").orEmpty(),
                    startTime = LoggingConfig.parseDate(record.str("StartTime")),
                    lastModified = LoggingConfig.parseDate(record.str("LastModifiedDate")),
                    durationMs = record.str("DurationMilliseconds")?.toLongOrNull() ?: 0,
                    status = record.str("Status").orEmpty(),
                    size = record.int("LogLength") ?: 0,
                )
            }
            SfUi.edt(project) {
                rows.items = loaded.toMutableList()
                table.emptyText.text = "No logs"
                loading = false
            }
        }
    }

    private fun enableTracing() {
        if (tracing) return
        tracing = true
        val org = connection.selectedKey
        val fallback = LogLevels.decode(settings.logLevels) ?: LogLevels.DEFAULT
        SfUi.background(project, "Enabling Debug Logs", onError = {
            tracing = false
            error("Cannot enable debug logs")(it)
        }) { indicator ->
            try {
                val levels = LoggingConfig(project).enableUserTracing(org, fallback, indicator)
                SfUi.edt(project) {
                    settings.logLevels = levels.encode()
                    levelsPanel.setLevels(levels)
                }
            } finally {
                tracing = false
            }
        }
    }

    private fun saveLevels(levels: LogLevels) {
        settings.logLevels = levels.encode()
        val org = connection.selectedKey
        SfUi.background(project, "Saving log levels", onError = error("Cannot save log levels")) { indicator ->
            LoggingConfig(project).saveLevels(org, levels, indicator)
        }
    }

    private fun openSelected() {
        val row = table.selectedObject ?: return
        val org = connection.selectedKey
        SfUi.background(project, "Retrieving Log Body", onError = error("Cannot retrieve log")) { indicator ->
            indicator.text2 = "Retrieving log body"
            val body = api.logBody(org, row.id, indicator)
            indicator.text2 = "Parsing log body"
            val parsed = ApexLogParser.parse(body)
            SfUi.edt(project) {
                if (body.isBlank()) logPanel.printRaw("Warning: No Apex log found.\n") else logPanel.show("ApexLog_${row.id}", body, parsed)
            }
        }
    }

    private fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        val answer = Messages.showYesNoDialog(project, "Delete ${ids.size} logs from `${connectionName()}`?", "Delete Logs", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        val org = connection.selectedKey
        SfUi.background(project, "Deleting logs", onError = error("Delete Failed")) { indicator ->
            val started = System.currentTimeMillis()
            val failures = api.deleteRecords(org, ids, indicator)
            val elapsed = StringUtil.formatDuration(System.currentTimeMillis() - started)
            if (failures.isEmpty()) {
                SfUi.notify(project, LogAnalyzerPanel.LOG_GROUP, "Delete Complete", "Deleted ${ids.size} logs in $elapsed.")
            } else {
                SfUi.notify(project, LogAnalyzerPanel.LOG_GROUP, "Delete Completed with Failures", failures.joinToString("<br>") { "• $it" }, NotificationType.WARNING)
            }
            SfUi.edt(project) { refresh() }
        }
    }

    private fun deleteAll() {
        val org = connection.selectedKey
        SfUi.background(project, "Retrieving Log IDs", onError = error("Cannot retrieve logs")) { indicator ->
            val ids = api.query(org, "SELECT Id FROM ApexLog ORDER BY StartTime DESC", indicator = indicator).records.mapNotNull { it.str("Id") }
            SfUi.edt(project) {
                if (ids.isEmpty()) {
                    SfUi.notify(project, LogAnalyzerPanel.LOG_GROUP, "No Logs", "There are no logs in `${connectionName()}`.")
                } else {
                    delete(ids)
                }
            }
        }
    }

    private fun loadExternal() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Load External Log")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        LogAnalyzerWindow.openLog(project, file.name, VfsUtilCore.loadText(file), connection.selectedKey)
    }

    private fun pasteExternal() {
        val text = Messages.showMultilineInputDialog(project, "Paste the log body here:", "External Log", "", null, null) ?: return
        if (text.isBlank()) return
        LogAnalyzerWindow.openLog(project, "External Log", text, connection.selectedKey)
    }

    private fun saveLogs() {
        val selected = table.selectedObjects.toList()
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project, "One or more logs must be selected.", SfUi.TITLE)
            return
        }
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Save Logs")
        val folder = FileChooser.chooseFile(descriptor, project, null)?.let { File(it.path) } ?: return
        val org = connection.selectedKey
        SfUi.background(project, "Saving Logs", onError = error("Cannot save logs")) { indicator ->
            var overwriteAll: Boolean? = null
            var saved = 0
            for (row in selected) {
                indicator.checkCanceled()
                val target = File(folder, "ApexLog_${row.id}.log")
                if (target.exists() && overwriteAll == null) {
                    var choice = -1
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                        choice = Messages.showDialog(
                            project,
                            "Log file `${target.path}` already exists. Would you like to overwrite it?",
                            SfUi.TITLE,
                            arrayOf("Yes", "Yes to All", "No", "No to All", "Cancel"),
                            0,
                            Messages.getQuestionIcon(),
                        )
                    }
                    when (choice) {
                        1 -> overwriteAll = true
                        2 -> continue
                        3 -> overwriteAll = false
                        4, -1 -> break
                    }
                }
                if (target.exists() && overwriteAll == false) continue
                target.writeText(api.logBody(org, row.id, indicator))
                saved++
            }
            val message = if (saved == 0) {
                "No log files were saved."
            } else {
                "Saved $saved log ${StringUtil.pluralize("file", saved)} to `${folder.path}`. Note that these files are not monitored or managed by SF Cloud."
            }
            SfUi.notify(project, LogAnalyzerPanel.LOG_GROUP, "Save Logs", message)
        }
    }

    override fun dispose() = Unit

    companion object {
        private val DATE_RENDERER = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                text = (value as? Instant)?.let { SfUi.formatDate(it) }.orEmpty()
            }
        }
        private val DURATION_RENDERER = object : DefaultTableCellRenderer() {
            init {
                horizontalAlignment = SwingConstants.RIGHT
            }

            override fun setValue(value: Any?) {
                text = (value as? Long)?.let { StringUtil.formatDuration(it) }.orEmpty()
            }
        }
        private val NUMBER_RENDERER = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }

        private fun <T : Comparable<T>> column(name: String, renderer: TableCellRenderer? = null, value: (LogRow) -> T?): ColumnInfo<LogRow, T> =
            object : ColumnInfo<LogRow, T>(name) {
                override fun valueOf(item: LogRow): T? = value(item)

                override fun getComparator(): Comparator<LogRow> = compareBy(nullsFirst()) { value(it) }

                override fun getRenderer(item: LogRow): TableCellRenderer? = renderer
            }

        private val COLUMNS: Array<ColumnInfo<LogRow, *>> = arrayOf(
            column("User") { it.user },
            column("Application") { it.application },
            column("Operation") { it.operation },
            column("Start Time", DATE_RENDERER) { it.startTime },
            column("Last Modified Date", DATE_RENDERER) { it.lastModified },
            column("Duration", DURATION_RENDERER) { it.durationMs },
            column("Status") { it.status },
            column("Size (bytes)", NUMBER_RENDERER) { it.size },
        )
    }
}

class OpenInLogAnalyzerAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
        e.presentation.isEnabledAndVisible = e.project != null && files.isNotEmpty() && files.all { !it.isDirectory && it.fileType == ApexLogFileType }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty().forEach { file ->
            LogAnalyzerWindow.openLog(project, file.name, VfsUtilCore.loadText(file), OrgService.getInstance(project).targetOrgKey)
        }
    }
}

class ViewSalesforceLogsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { SfdxProject.isSfdx(it) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        LogAnalyzerWindow.show(e.project ?: return)
    }
}
