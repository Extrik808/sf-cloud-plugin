package dev.sfcloud.repl

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.sfcloud.log.SalesforceIdFilter
import dev.sfcloud.ui.SfUi
import java.awt.Component
import java.awt.Cursor
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class QueryResultsPanel(
    private val project: Project,
    parent: Disposable,
    private val group: String,
    private val org: () -> String?,
    proportion: Float,
    onProportion: (Float) -> Unit,
) : Disposable {
    val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val tableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(tableModel)
    private val treeRoot = DefaultMutableTreeNode("result")
    private val tree = Tree(DefaultTreeModel(treeRoot))
    private val tabs = JBTabbedPane(SwingConstants.BOTTOM)
    private var records: List<JsonObject> = emptyList()
    private var columns: List<String> = emptyList()
    private var rows: List<Map<String, String>> = emptyList()
    val component: JComponent

    init {
        Disposer.register(parent, this)
        Disposer.register(this, console)
        console.addMessageFilter(SalesforceIdFilter(project, org))
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.cellSelectionEnabled = true
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_INTERVAL_SELECTION
        table.setShowGrid(true)
        table.emptyText.text = "Run a query with Cmd/Ctrl+Enter"
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ResultRenderer()
        tree.emptyText.text = "No results"
        installOpenInSalesforce()

        val treeTab = SfUi.verticalToolbarPanel(
            "SfCloudQueryTree",
            SfUi.group(
                SfUi.action("Expand All", null, AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) },
                SfUi.action("Collapse All", null, AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(tree, 0) },
            ),
            JBScrollPane(tree),
        )
        tabs.tabComponentInsets = JBUI.emptyInsets()
        tabs.addTab("Table", TABLE_ICON, JBScrollPane(table))
        tabs.addTab("Tree", AllIcons.Actions.ShowAsTree, treeTab)

        val splitter = SfUi.splitter(true, proportion, onProportion)
        splitter.firstComponent = tabs
        splitter.secondComponent = console.component
        component = splitter
    }

    val hasResults: Boolean get() = records.isNotEmpty()

    fun print(text: String, type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) = console.print(text, type)

    fun clear() {
        console.clear()
        show(emptyList(), emptyList(), emptyList())
    }

    fun show(records: List<JsonObject>, columns: List<String>, rows: List<Map<String, String>>, typeGroups: Boolean = false) {
        this.records = records
        this.columns = columns
        this.rows = rows
        tableModel.setDataVector(
            rows.map { row -> columns.map { row[it].orEmpty() }.toTypedArray<Any>() }.toTypedArray(),
            columns.toTypedArray<Any>(),
        )
        val metrics = table.getFontMetrics(table.font)
        columns.indices.forEach { index ->
            val header = columns[index]
            val widest = rows.take(200).maxOfOrNull { metrics.stringWidth(it[header].orEmpty()) } ?: 0
            table.columnModel.getColumn(index).preferredWidth = (maxOf(metrics.stringWidth(header), widest) + JBUI.scale(16)).coerceAtMost(JBUI.scale(MAX_COLUMN_WIDTH))
        }
        treeRoot.removeAllChildren()
        if (typeGroups) {
            records.groupBy { it.getAsJsonObject("attributes")?.get("type")?.asString ?: "Unknown" }.forEach { (type, typed) ->
                val node = DefaultMutableTreeNode(TreeItem.Group(type, typed.size))
                typed.forEachIndexed { index, record -> node.add(recordNode(index + 1, record)) }
                treeRoot.add(node)
            }
        } else {
            records.forEachIndexed { index, record -> treeRoot.add(recordNode(index + 1, record)) }
        }
        (tree.model as DefaultTreeModel).reload()
        if (records.size <= LARGE_RESULT) {
            TreeUtil.expandAll(tree)
        } else {
            SfUi.notify(project, group, "Large Result Set", "This is a large result set. The tree view was not auto-expanded.")
        }
    }

    fun copyResults() {
        if (records.isEmpty()) return
        if (tabs.selectedIndex == 1) {
            CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(GsonBuilder().setPrettyPrinting().create().toJson(JsonArray().apply { records.forEach { add(strip(it)) } })))
        } else {
            val tsv = buildString {
                appendLine(columns.joinToString("\t"))
                rows.forEach { row -> appendLine(columns.joinToString("\t") { row[it].orEmpty() }) }
            }
            val html = buildString {
                append("<table><tr>")
                columns.forEach { append("<th>").append(StringUtil.escapeXmlEntities(it)).append("</th>") }
                append("</tr>")
                rows.forEach { row ->
                    append("<tr>")
                    columns.forEach { append("<td>").append(StringUtil.escapeXmlEntities(row[it].orEmpty())).append("</td>") }
                    append("</tr>")
                }
                append("</table>")
            }
            CopyPasteManager.getInstance().setContents(HtmlTransferable(html, tsv))
        }
        SfUi.notify(project, group, "Copy Results", "Query results copied to the system clipboard.")
    }

    fun export(title: String, fileName: String) {
        if (records.isEmpty()) return
        val descriptor = FileSaverDescriptor(title, "Choose the file into which the query results should be exported", "csv")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("$fileName.csv") ?: return
        val csv = buildString {
            appendLine(columns.joinToString(",") { csvCell(it) })
            rows.forEach { row -> appendLine(columns.joinToString(",") { csvCell(row[it].orEmpty()) }) }
        }
        try {
            wrapper.file.writeText(csv)
            SfUi.notify(project, group, "Export Complete", "Exported result set to '${wrapper.file.path}'.")
        } catch (e: Exception) {
            SfUi.notify(project, group, "Export Failed", e.message.orEmpty(), NotificationType.ERROR)
        }
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value

    private fun strip(record: JsonObject): JsonObject = JsonObject().apply {
        record.entrySet().filter { it.key != "attributes" }.forEach { (key, value) ->
            add(key, if (value.isJsonObject) strip(value.asJsonObject) else value)
        }
    }

    private fun recordNode(index: Int, record: JsonObject): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(TreeItem.Record(index, record.getAsJsonObject("attributes")?.get("type")?.asString))
        addFields(node, record)
        return node
    }

    private fun addFields(node: DefaultMutableTreeNode, record: JsonObject) {
        record.entrySet().forEach { (key, value) ->
            if (key == "attributes") return@forEach
            when {
                value.isJsonObject && value.asJsonObject.has("records") -> {
                    val relationship = DefaultMutableTreeNode(TreeItem.Relationship(key))
                    value.asJsonObject.getAsJsonArray("records").filter { it.isJsonObject }.forEachIndexed { index, child ->
                        relationship.add(recordNode(index + 1, child.asJsonObject))
                    }
                    node.add(relationship)
                }
                value.isJsonObject -> {
                    val relationship = DefaultMutableTreeNode(TreeItem.Relationship(key))
                    addFields(relationship, value.asJsonObject)
                    node.add(relationship)
                }
                else -> node.add(DefaultMutableTreeNode(TreeItem.Field(key, primitive(value))))
            }
        }
    }

    private fun installOpenInSalesforce() {
        val openAction = SfUi.action("Open in Salesforce", null, AllIcons.General.Web, { selectedId() != null }) {
            selectedId()?.let { id -> SalesforceIdFilter.openInSalesforce(project, org(), id) }
        }
        val popup = DefaultActionGroup(openAction)
        PopupHandler.installPopupMenu(table, popup, "SfCloudQueryTable")
        PopupHandler.installPopupMenu(tree, popup, "SfCloudQueryTreePopup")
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = table.rowAtPoint(e.point)
                val column = table.columnAtPoint(e.point)
                if (row >= 0 && column >= 0) table.changeSelection(row, column, false, false)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (isPlainClick(e)) idInTable(e)?.let { openRecord(it) }
            }
        })
        table.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                table.cursor = if (idInTable(e) != null) HAND else Cursor.getDefaultCursor()
            }
        })
        table.setDefaultRenderer(Any::class.java, IdCellRenderer())
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (isPlainClick(e)) idInTree(e)?.let { openRecord(it) }
            }
        })
        tree.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                tree.cursor = if (idInTree(e) != null) HAND else Cursor.getDefaultCursor()
            }
        })
    }

    private fun isPlainClick(e: MouseEvent): Boolean =
        SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1 && !e.isShiftDown && !e.isControlDown && !e.isMetaDown && !e.isAltDown

    private fun idInTable(e: MouseEvent): String? {
        val row = table.rowAtPoint(e.point)
        val column = table.columnAtPoint(e.point)
        if (row < 0 || column < 0) return null
        return table.getValueAt(row, column)?.toString()?.takeIf { SalesforceIdFilter.isRecordId(it) }
    }

    private fun idInTree(e: MouseEvent): String? {
        val path = tree.getPathForLocation(e.x, e.y) ?: return null
        val field = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeItem.Field ?: return null
        return field.value?.takeIf { SalesforceIdFilter.isRecordId(it) }
    }

    private fun openRecord(id: String) = SalesforceIdFilter.openInSalesforce(project, org(), id)

    private fun selectedId(): String? {
        val value = if (tabs.selectedIndex == 1) {
            (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject.let { (it as? TreeItem.Field)?.value }
        } else {
            val row = table.selectedRow
            val column = table.selectedColumn
            if (row < 0 || column < 0) null else table.getValueAt(row, column)?.toString()
        }
        return value?.takeIf { SalesforceIdFilter.isRecordId(it) }
    }

    private sealed interface TreeItem {
        data class Group(val type: String, val count: Int) : TreeItem
        data class Record(val index: Int, val type: String?) : TreeItem
        data class Relationship(val name: String) : TreeItem
        data class Field(val name: String, val value: String?) : TreeItem
    }

    private class ResultRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is TreeItem.Group -> {
                    icon = AllIcons.Nodes.Folder
                    append(item.type)
                    append(" (${item.count} ${StringUtil.pluralize("record", item.count)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TreeItem.Record -> {
                    icon = AllIcons.Nodes.DataTables
                    append("Result ${item.index}")
                    item.type?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is TreeItem.Relationship -> {
                    icon = AllIcons.Nodes.Related
                    append(item.name)
                }
                is TreeItem.Field -> {
                    icon = AllIcons.Nodes.Field
                    append("${item.name} = ")
                    val attributes = when {
                        item.value == null -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        SalesforceIdFilter.isRecordId(item.value) -> SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    append(item.value ?: "null", attributes)
                }
                else -> append(value.toString())
            }
        }
    }

    private class IdCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            val component = super.getTableCellRendererComponent(table, value, selected, focus, row, column)
            if (SalesforceIdFilter.isRecordId(value?.toString())) {
                if (!selected) foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                font = font.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
            }
            return component
        }
    }

    private class HtmlTransferable(private val html: String, private val text: String) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.allHtmlFlavor, DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

        override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.stringFlavor) text else html
    }

    override fun dispose() = Unit

    companion object {
        private const val MAX_COLUMN_WIDTH = 300
        private const val LARGE_RESULT = 1000
        private val HAND = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        private val TABLE_ICON = IconLoader.getIcon("/icons/table.svg", QueryResultsPanel::class.java)

        fun primitive(value: JsonElement?): String? = when {
            value == null || value.isJsonNull -> null
            value.isJsonPrimitive -> value.asJsonPrimitive.let { if (it.isString) it.asString else it.toString() }
            else -> value.toString()
        }

        fun flatten(record: JsonObject, prefix: String = ""): Map<String, String> {
            val row = LinkedHashMap<String, String>()
            record.entrySet().forEach { (key, value) ->
                if (key == "attributes") return@forEach
                val name = prefix + key
                when {
                    value == null || value.isJsonNull -> row[name] = ""
                    value.isJsonObject && value.asJsonObject.has("records") ->
                        row[name] = "[${value.asJsonObject.getAsJsonArray("records").size()} rows]"
                    value.isJsonObject -> row.putAll(flatten(value.asJsonObject, "$name."))
                    value.isJsonArray -> row[name] = value.toString()
                    else -> row[name] = primitive(value).orEmpty()
                }
            }
            return row
        }
    }
}
