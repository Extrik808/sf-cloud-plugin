package dev.sfcloud.tests

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.ui.SfUi
import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

object CoverageWindow {
    const val ID = "Code Coverage"

    fun show(project: Project) {
        SfUi.edt(project) {
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.activate(null, false, false)
        }
    }
}

class CoverageWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean = SfdxProject.isSfdx(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = CoverageView(project)
        val content = ContentFactory.getInstance().createContent(view.component, null, false)
        content.setDisposer(view)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            CoverageListener.TOPIC,
            object : CoverageListener {
                override fun coverageChanged() = view.reload()
            },
        )
    }
}

class CoverageView(private val project: Project) : Disposable {
    private var rows: List<CoverageRow> = emptyList()
    private val model = Model()
    private val table = JBTable(model)
    private val summary = JBLabel()
    val component: JComponent

    init {
        table.setShowGrid(false)
        table.autoCreateRowSorter = true
        table.emptyText.text = "Run Apex tests with Coverage to see code coverage"
        table.columnModel.getColumn(1).cellRenderer = PercentRenderer()
        table.columnModel.getColumn(1).maxWidth = JBUI.scale(110)
        table.columnModel.getColumn(2).maxWidth = JBUI.scale(110)
        table.columnModel.getColumn(3).maxWidth = JBUI.scale(90)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                open()
                return true
            }
        }.installOn(table)
        table.registerKeyboardAction({ open() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        summary.border = JBUI.Borders.empty(4, 8)
        val group = SfUi.group(
            reference("SfCloud.ToggleCoverage"),
            reference("SfCloud.ClearCoverage"),
        )
        component = SfUi.verticalToolbarPanel("SfCloudCoverage", group, SfUi.north(summary, JBScrollPane(table)))
        reload()
    }

    fun reload() {
        val report = ApexCoverageService.getInstance(project).report
        SfUi.edt(project) {
            rows = report?.rows.orEmpty()
            model.fireTableDataChanged()
            summary.text = report?.let { summaryText(it) }.orEmpty()
            summary.isVisible = report != null
        }
    }

    override fun dispose() = Unit

    private fun summaryText(report: CoverageReport): String {
        val total = report.percent?.let { "$it%  (${report.covered}/${report.total} lines, ${report.files} files)" }
            ?: "no data"
        val org = report.orgWide?.let { "   ·   Org-wide: $it%" }.orEmpty()
        val missing = report.rows.count { it.percent == null }
        val gap = if (missing > 0) "   ·   $missing file(s) without coverage data" else ""
        return "Total: $total$org$gap"
    }

    private fun open() {
        val row = selected() ?: return
        val path = row.path ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun selected(): CoverageRow? {
        val index = table.selectedRow.takeIf { it >= 0 } ?: return null
        return rows.getOrNull(table.convertRowIndexToModel(index))
    }

    private fun reference(id: String): AnAction? = ActionManager.getInstance().getAction(id)

    private inner class Model : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getColumnClass(column: Int): Class<*> = if (column == 1) Integer::class.java else String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.name
                1 -> row.percent
                2 -> if (row.percent == null) "" else "${row.covered}/${row.total}"
                else -> when (row.source) {
                    CoverageSource.RUN -> "this run"
                    CoverageSource.ORG -> "org"
                    CoverageSource.NONE -> "no data"
                }
            }
        }
    }

    private class PercentRenderer : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.RIGHT
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val percent = value as? Int
            val component = super.getTableCellRendererComponent(table, percent?.let { "$it%" } ?: "—", selected, focus, row, column)
            if (!selected) {
                foreground = when {
                    percent == null -> JBColor.GRAY
                    percent >= CoverageReports.THRESHOLD -> COVERED
                    else -> UNCOVERED
                }
            }
            return component
        }

        companion object {
            private val COVERED = JBColor(0x2E7D32, 0x5FB865)
            private val UNCOVERED = JBColor(0xC62828, 0xE0605D)
        }
    }

    companion object {
        private val COLUMNS = arrayOf("File", "Coverage", "Lines", "Source")
    }
}
