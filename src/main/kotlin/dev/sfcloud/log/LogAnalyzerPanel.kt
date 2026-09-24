package dev.sfcloud.log

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeTableSpeedSearch
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.Alarm
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.sfcloud.settings.ToolWindowSettings
import dev.sfcloud.tests.ApexTestTarget
import dev.sfcloud.ui.SfUi
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class LogAnalyzerPanel(
    private val project: Project,
    parent: Disposable,
    private val org: () -> String?,
    private val anonymousNavigator: ((line: Int, column: Int) -> Unit)? = null,
    private val context: LogContext? = null,
) : Disposable {
    private val settings = ToolWindowSettings.getInstance(project).logAnalyzer
    private val filterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val filterField = SearchTextField("SfCloud.LogAnalyzer.RawFilter")
    private val debugOnly = JBCheckBox("Debug Only", settings.debugOnly)
    private val highlighting = JBCheckBox("Highlighting", settings.showHighlighting)
    private val tabs = JBTabbedPane()
    private val treePanel = JPanel(BorderLayout())
    private var treeTable: TreeTable? = null
    private var view: LogTreeView = context?.kind?.defaultView ?: LogTreeView.of(settings.treeView, analysis = false)
    private var treeStale = true

    var log: ApexLog? = null
        private set
    var text: String = ""
        private set
    var name: String = ""
        private set
    private var header: List<Pair<String, ConsoleViewContentType>> = emptyList()

    val component: JComponent

    init {
        Disposer.register(parent, this)
        Disposer.register(this, console)
        console.addMessageFilter(ApexStackTraceFilter(project, anonymousNavigator))
        console.addMessageFilter(SalesforceIdFilter(project, org))

        val treeTab = SfUi.verticalToolbarPanel("SfCloudLogTree", treeActions(), treePanel)
        if (context != null) {
            component = treeTab
            log = context.log
            showTree()
        } else {
            tabs.tabComponentInsets = JBUI.emptyInsets()
            tabs.addTab("Raw", AllIcons.Toolwindows.ToolWindowMessages, rawTab())
            tabs.addTab("Tree", AllIcons.Actions.ShowAsTree, treeTab)
            tabs.selectedIndex = settings.selectedTabIndex.coerceIn(0, 1)
            tabs.addChangeListener {
                settings.selectedTabIndex = tabs.selectedIndex
                if (tabs.selectedIndex == 1 && treeStale) showTree()
            }
            component = tabs
        }
    }

    fun show(name: String, text: String, parsed: ApexLog? = null, header: List<Pair<String, ConsoleViewContentType>> = emptyList()) {
        this.name = name
        this.text = text
        this.header = header
        log = if (text.isEmpty()) null else parsed ?: ApexLogParser.parse(text)
        treeStale = true
        renderRaw()
        if (tabs.selectedIndex == 1) showTree()
    }

    fun clear() {
        name = ""
        text = ""
        header = emptyList()
        log = null
        console.clear()
        treePanel.removeAll()
        treePanel.revalidate()
        treePanel.repaint()
        treeTable = null
    }

    fun printRaw(message: String, type: ConsoleViewContentType = ConsoleViewContentType.SYSTEM_OUTPUT) = console.print(message, type)

    private fun rawTab(): JComponent {
        filterField.toolTipText = "Optional regular expression-based filter."
        filterField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterAlarm.cancelAllRequests()
                filterAlarm.addRequest({ renderRaw() }, 300)
            }
        })
        debugOnly.mnemonic = KeyEvent.VK_D
        debugOnly.toolTipText = "When checked, only USER_DEBUG level log entries are displayed."
        debugOnly.addActionListener {
            settings.debugOnly = debugOnly.isSelected
            renderRaw()
        }
        highlighting.mnemonic = KeyEvent.VK_H
        highlighting.toolTipText = "When checked, user debug log entries are highlighted according to log level."
        highlighting.addActionListener {
            settings.showHighlighting = highlighting.isSelected
            renderRaw()
        }
        val header = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.insets = JBUI.insets(2, 4)
        c.fill = GridBagConstraints.HORIZONTAL
        header.add(JBLabel(AllIcons.General.Filter).apply { toolTipText = filterField.toolTipText; displayedMnemonic = KeyEvent.VK_F; labelFor = filterField.textEditor }, c)
        c.weightx = 1.0
        header.add(filterField, c)
        c.weightx = 0.0
        header.add(debugOnly, c)
        header.add(highlighting, c)

        val consoleComponent = console.component
        val consoleActions = DefaultActionGroup()
        console.createConsoleActions()
            .filterNot { it.templatePresentation.text?.startsWith("Clear") == true }
            .forEach { consoleActions.add(it) }
        val consolePanel = SfUi.verticalToolbarPanel("SfCloudLogRaw", consoleActions, consoleComponent)
        return SfUi.north(header, consolePanel)
    }

    private fun renderRaw() {
        console.clear()
        header.forEach { (message, type) -> console.print(message, type) }
        if (text.isEmpty()) return
        val pattern = filterField.text.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { Regex(raw, RegexOption.IGNORE_CASE) }.getOrElse { Regex(Regex.escape(raw), RegexOption.IGNORE_CASE) }
        }
        val onlyDebug = debugOnly.isSelected
        val colored = highlighting.isSelected
        val buffer = StringBuilder()
        var bufferType = ConsoleViewContentType.NORMAL_OUTPUT
        fun flush() {
            if (buffer.isNotEmpty()) console.print(buffer.toString(), bufferType)
            buffer.setLength(0)
        }
        text.lineSequence().forEach { line ->
            if (onlyDebug && !line.contains("|USER_DEBUG|")) return@forEach
            if (pattern != null && !pattern.containsMatchIn(line)) return@forEach
            val type = if (colored) contentType(line) else ConsoleViewContentType.NORMAL_OUTPUT
            if (type != bufferType) {
                flush()
                bufferType = type
            }
            buffer.append(line).append('\n')
        }
        flush()
        console.scrollTo(0)
    }

    private fun contentType(line: String): ConsoleViewContentType {
        if (line.contains("|EXCEPTION_THROWN|") || line.contains("|FATAL_ERROR|")) return ConsoleViewContentType.ERROR_OUTPUT
        if (!line.contains("|USER_DEBUG|")) return ConsoleViewContentType.NORMAL_OUTPUT
        val level = line.split('|').getOrNull(3)
        return when (ApexLogLevel.parse(level)) {
            ApexLogLevel.ERROR -> ConsoleViewContentType.LOG_ERROR_OUTPUT
            ApexLogLevel.WARN -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            ApexLogLevel.INFO -> ConsoleViewContentType.LOG_INFO_OUTPUT
            ApexLogLevel.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
            ApexLogLevel.FINE, ApexLogLevel.FINER, ApexLogLevel.FINEST -> ConsoleViewContentType.LOG_VERBOSE_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
    }

    private fun showTree() {
        treeStale = false
        val log = log ?: return
        val columns = mutableListOf<ColumnInfo<*, *>>(TreeColumnInfo("Event"))
        view.columns().forEach { columns += it.bind(log) }
        val rootValue: Any = context?.root ?: view.root(log)
        val root = LogTreeNode(rootValue) { value ->
            when (value) {
                is LogNode -> value.children
                is AggregateNode -> value.children.values.toList()
                else -> emptyList()
            }
        }
        val holder = DefaultMutableTreeNode().apply { if (context != null) add(root) }
        val model = ListTreeTableModelOnColumns(if (context != null) holder else root, columns.toTypedArray())
        val table = TreeTable(model)
        table.setRootVisible(false)
        table.tree.showsRootHandles = true
        table.setTreeCellRenderer(EventRenderer())
        columns.drop(1).forEachIndexed { index, column ->
            val sample = column.preferredStringValue ?: return@forEachIndexed
            table.columnModel.getColumn(index + 1).preferredWidth = table.getFontMetrics(table.font).stringWidth(sample) + JBUI.scale(16)
        }
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(320)
        TreeTableSpeedSearch.installOn(table) { path -> label(path.lastPathComponent) }
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && settings.autoScrollToSource) navigate(selected(table), focus = false)
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate(selected(table), focus = true)
            }
        })
        PopupHandler.installPopupMenu(table, analysisGroup(), "SfCloudLogTreePopup")
        treeTable = table
        treePanel.removeAll()
        treePanel.add(JBScrollPane(table), BorderLayout.CENTER)
        treePanel.revalidate()
        treePanel.repaint()
        expandInitially(table)
    }

    private fun expandInitially(table: TreeTable) {
        val tree = table.tree
        for (level in 1..MAX_AUTO_EXPAND_LEVELS) {
            var expanded = false
            var row = 0
            while (row < tree.rowCount) {
                val path = tree.getPathForRow(row)
                if (path.pathCount == level + 1 && !tree.isExpanded(path) && !tree.model.isLeaf(path.lastPathComponent)) {
                    tree.expandPath(path)
                    expanded = true
                }
                row++
            }
            if (!expanded) return
            if (tree.rowCount > MAX_AUTO_EXPAND_ROWS) {
                SfUi.notify(project, LOG_GROUP, "Limited Tree Expansion", "This is a large log. Expanded the first $level levels of the tree.", NotificationType.INFORMATION)
                return
            }
        }
    }

    private fun label(node: Any?): String {
        val value = (node as? DefaultMutableTreeNode)?.userObject ?: return ""
        return when (value) {
            is LogNode -> "${value.event?.type.orEmpty()} ${value.displayName}"
            is AggregateNode -> value.key.name
            else -> value.toString()
        }
    }

    private fun selected(table: TreeTable): Any? {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return null
        return (table.tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
    }

    private fun navigate(value: Any?, focus: Boolean) {
        val node = when (value) {
            is LogNode -> value
            is AggregateNode -> value.sample
            else -> return
        }
        val (className, line) = source(node) ?: return
        if (className == null) {
            anonymousNavigator?.invoke(line, 1)
            return
        }
        if (DumbService.isDumb(project)) return
        val file = ApexTestTarget.findClassFile(project, className)?.virtualFile ?: return
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(focus)
    }

    private fun source(node: LogNode): Pair<String?, Int>? {
        val line = node.event?.sourceLine ?: return null
        var current: LogNode? = node
        while (current != null) {
            val event = current.event ?: return null
            if (event.type in OWNER_EVENTS) {
                val owner = owner(event)
                return when {
                    owner != null -> owner to line
                    event.name.contains("execute_anonymous_apex") -> null to line
                    else -> null
                }
            }
            current = current.parent
        }
        return null
    }

    private fun owner(event: LogEvent): String? {
        val name = event.name
        return when (event.type) {
            "CONSTRUCTOR_ENTRY" -> name.substringBefore('.').takeIf { it.isNotBlank() && !it.startsWith("<") }
            "CODE_UNIT_STARTED" -> when {
                name.startsWith("__sfdc_trigger/") -> name.substringAfter('/')
                name.contains(" on ") -> name.substringBefore(' ')
                name.contains('.') && name.contains('(') -> name.substringBefore('.')
                else -> null
            }
            else -> name.substringBefore('(').substringBeforeLast('.').substringBefore('.').takeIf { it.isNotBlank() }
        }
    }

    private fun treeActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        val views = DefaultActionGroup("Change View", true).apply {
            templatePresentation.icon = AllIcons.Debugger.RestoreLayout
            LogTreeView.entries.filter { it.analysis == (context != null) }.forEach { add(ViewAction(it)) }
        }
        group.add(views)
        group.add(SfUi.action("Expand All", null, AllIcons.Actions.Expandall) { treeTable?.let { TreeUtil.expandAll(it.tree) } })
        group.add(SfUi.action("Collapse All", null, AllIcons.Actions.Collapseall) { treeTable?.let { TreeUtil.collapseAll(it.tree, 0) } })
        group.add(SfUi.action("Smart Expand", "Expands the selected node to show branching behavior as appropriate for the view.", AllIcons.Actions.ShowAsTree) { smartExpand() })
        group.addSeparator()
        group.add(SfUi.toggle("Autoscroll to Source", null, AllIcons.General.AutoscrollToSource, { settings.autoScrollToSource }) { settings.autoScrollToSource = it })
        group.addSeparator()
        analysisActions().forEach { group.add(it) }
        return group
    }

    private fun analysisGroup(): DefaultActionGroup = DefaultActionGroup().apply { analysisActions().forEach { add(it) } }

    private fun analysisActions(): List<AnAction> = ContextKind.entries.map { kind ->
        object : DumbAwareAction("Show ${kind.label}", "Opens a new tab filtered to display ${kind.label} of the selected method or trigger.", ICONS.getValue(kind)) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = analysisTarget() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                val log = log ?: return
                val target = analysisTarget() ?: return
                val key = when (target) {
                    is LogNode -> AggregateKey.of(target)
                    is AggregateNode -> target.key
                    else -> return
                }
                val invocations = log.invocations(key)
                if (invocations.isEmpty()) return
                LogAnalyzerWindow.openContext(project, LogContext(kind, "${kind.label} of ${key.name}", log, kind.build(invocations)), org())
            }
        }
    }

    private fun analysisTarget(): Any? {
        val table = treeTable ?: return null
        return when (val value = selected(table)) {
            is LogNode -> value.takeIf { it.isBlock && it.event?.category in ANALYZABLE }
            is AggregateNode -> value.takeIf { it.key.category in ANALYZABLE }
            else -> null
        }
    }

    private fun smartExpand() {
        val table = treeTable ?: return
        val tree = table.tree
        val path = tree.getPathForRow(table.selectedRow.takeIf { it >= 0 } ?: return) ?: return
        fun expand(current: TreePath) {
            tree.expandPath(current)
            val node = current.lastPathComponent as DefaultMutableTreeNode
            val branches = (0 until node.childCount).map { node.getChildAt(it) as DefaultMutableTreeNode }.filter { it.childCount > 0 }
            if (branches.size == 1) expand(current.pathByAddingChild(branches.single()))
        }
        expand(path)
    }

    private inner class ViewAction(private val target: LogTreeView) : ToggleAction(target.label, target.description, null), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent) = view == target

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state || view == target) return
            view = target
            if (context == null) settings.treeView = target.name
            showTree()
        }
    }

    private class EventRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is LogNode -> {
                    val event = item.event ?: return
                    icon = ApexLogCache.icon(event.category)
                    val attributes = when {
                        event.category == LogCategory.ERROR -> SimpleTextAttributes.ERROR_ATTRIBUTES
                        item.isBlock -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    append(event.type, attributes)
                    event.sourceLine?.let { append("  [$it]", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is AggregateNode -> {
                    icon = ApexLogCache.icon(item.key.category)
                    append(item.key.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
            }
        }
    }

    override fun dispose() = Unit

    companion object {
        const val LOG_GROUP = "SF Cloud Log Analyzer"
        private const val MAX_AUTO_EXPAND_LEVELS = 6
        private const val MAX_AUTO_EXPAND_ROWS = 2000
        private val OWNER_EVENTS = setOf("METHOD_ENTRY", "CONSTRUCTOR_ENTRY", "CODE_UNIT_STARTED")
        private val ANALYZABLE = setOf(LogCategory.METHOD, LogCategory.CODE_UNIT, LogCategory.AUTOMATION)
        private val ICONS = mapOf(
            ContextKind.CALLERS to IconLoader.getIcon("/icons/callers.svg", LogAnalyzerPanel::class.java),
            ContextKind.CALLEES to IconLoader.getIcon("/icons/callees.svg", LogAnalyzerPanel::class.java),
            ContextKind.MERGED_CALLEES to IconLoader.getIcon("/icons/mergedCallees.svg", LogAnalyzerPanel::class.java),
        )
    }
}

class LogContext(val kind: ContextKind, val title: String, val log: ApexLog, val root: AggregateNode)
