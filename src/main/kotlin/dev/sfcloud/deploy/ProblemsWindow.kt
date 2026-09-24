package dev.sfcloud.deploy

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.settings.ToolWindowSettings
import dev.sfcloud.ui.SfUi
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

object ProblemsWindow {
    const val ID = "SF Cloud"

    fun show(project: Project, connection: String) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        window.show {
            ProblemsWindowFactory.sync(project, window)
            window.contentManager.findContent(title(connection))?.let { window.contentManager.setSelectedContent(it) }
        }
    }

    fun title(connection: String) = "Problems for $connection"
}

class ProblemsWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean = SfdxProject.isSfdx(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        sync(project, toolWindow)
        project.messageBus.connect(toolWindow.disposable).subscribe(DeployListener.TOPIC, object : DeployListener {
            override fun problemsChanged() = sync(project, toolWindow)
        })
    }

    companion object {
        fun sync(project: Project, window: ToolWindow) {
            val manager = window.contentManager
            val service = DeployService.getInstance(project)
            val connections = service.connections.ifEmpty { listOf(dev.sfcloud.org.OrgService.getInstance(project).orgLabel(null)) }
            connections.forEach { connection ->
                val title = ProblemsWindow.title(connection)
                if (manager.findContent(title) != null) return@forEach
                val view = ProblemsView(project, connection)
                val content = ContentFactory.getInstance().createContent(view.component, title, false)
                content.icon = AllIcons.Toolwindows.Problems
                content.putUserData(com.intellij.openapi.wm.ToolWindow.SHOW_CONTENT_ICON, true)
                content.setDisposer(view)
                content.isCloseable = true
                manager.addContent(content)
            }
            manager.contents.forEach { content ->
                (content.disposer as? ProblemsView)?.reload()
            }
            if (manager.contents.size > 1) {
                manager.contents.filter { content ->
                    val view = content.disposer as? ProblemsView ?: return@filter false
                    view.isEmpty && view.connection !in service.connections
                }.forEach { manager.removeContent(it, true) }
            }
        }
    }
}

private sealed interface ProblemNode {
    data class Group(val kind: ProblemKind, val problems: Int, val files: Int) : ProblemNode
    data class File(val path: String, val relative: String, val problems: Int) : ProblemNode
    data class Item(val problem: DeployProblem) : ProblemNode
}

class ProblemsView(private val project: Project, val connection: String) : Disposable {
    private val settings = ToolWindowSettings.getInstance(project).state
    private val root = DefaultMutableTreeNode("Problems")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    val component: JComponent

    val isEmpty: Boolean get() = DeployService.getInstance(project).problems(connection).isEmpty()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "No problems"
        tree.cellRenderer = Renderer()
        TreeSpeedSearch.installOn(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                navigate()
                return true
            }
        }.installOn(tree)
        tree.registerKeyboardAction({ navigate() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        SfUi.action("Edit Source", null, null) { navigate() }
            .registerCustomShortcutSet(ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE)?.shortcutSet ?: CommonShortcuts.getEditSource(), tree)

        val group = SfUi.group(
            SfUi.action("Previous Problem", "Navigate to the previous problem", AllIcons.Actions.PreviousOccurence) { step(-1) },
            SfUi.action("Next Problem", "Navigate to the next problem", AllIcons.Actions.NextOccurence) { step(1) },
            null,
            SfUi.action("Expand All", null, AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) },
            SfUi.action("Collapse All", null, AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(tree, 0) },
            SfUi.action("Expand Actionable", "Expand nodes with actionable problems", AllIcons.Actions.ShowAsTree) { expandActionable() },
            null,
            SfUi.toggle("Show Transitive Failures", "When enabled, transitive failures are included in the Problems View", AllIcons.Nodes.Related, { settings.showTransitiveFailures }) {
                settings.showTransitiveFailures = it
                reload()
            },
            SfUi.toggle("Show Unexpected Errors", "When enabled, unexpected errors are included in the Problems View", AllIcons.General.ExclMark, { settings.showUnexpectedErrors }) {
                settings.showUnexpectedErrors = it
                reload()
            },
            null,
            SfUi.action("Clear Problems", "Removes all problems for this connection", AllIcons.Actions.GC) {
                DeployService.getInstance(project).clearProblems(connection)
            },
        )
        component = com.intellij.openapi.ui.SimpleToolWindowPanel(false, true).apply {
            val toolbar = ActionManager.getInstance().createActionToolbar("SfCloudProblems", group, false)
            toolbar.targetComponent = tree
            this.toolbar = toolbar.component
            setContent(JBScrollPane(tree))
        }
        reload()
    }

    fun reload() {
        val problems = DeployService.getInstance(project).problems(connection).filter { problem ->
            when (problem.kind) {
                ProblemKind.TRANSITIVE -> settings.showTransitiveFailures
                ProblemKind.UNEXPECTED -> settings.showUnexpectedErrors
                ProblemKind.FAILURE -> true
            }
        }
        val base = SfdxProject.root(project)?.path
        root.removeAllChildren()
        problems.groupBy { it.kind }.toSortedMap().forEach { (kind, kindProblems) ->
            val byFile = kindProblems.groupBy { it.path }.toSortedMap()
            val group = DefaultMutableTreeNode(ProblemNode.Group(kind, kindProblems.size, byFile.size))
            byFile.forEach { (path, fileProblems) ->
                val relative = if (base != null && path.startsWith(base)) path.removePrefix(base).trimStart('/') else path
                val file = DefaultMutableTreeNode(ProblemNode.File(path, relative, fileProblems.size))
                fileProblems.sortedWith(compareBy({ it.line }, { it.column })).forEach { file.add(DefaultMutableTreeNode(ProblemNode.Item(it))) }
                group.add(file)
            }
            root.add(group)
        }
        model.reload()
        expandActionable()
    }

    private fun expandActionable() {
        TreeUtil.collapseAll(tree, 0)
        (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }
            .filter { (it.userObject as? ProblemNode.Group)?.kind == ProblemKind.FAILURE }
            .forEach { group -> SfUi.expandSubtree(tree, TreePath(group.path)) }
    }

    private fun step(direction: Int) {
        val items = TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java)
            .filter { it.userObject is ProblemNode.Item }.toList()
        if (items.isEmpty()) return
        val current = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
        val index = items.indexOf(current)
        val next = items[((if (index < 0) (if (direction > 0) -1 else 0) else index) + direction).mod(items.size)]
        val path = TreePath(next.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        navigate(focus = false)
    }

    private fun navigate(focus: Boolean = true) {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val (path, line, column) = when (val value = node.userObject) {
            is ProblemNode.Item -> Triple(value.problem.path, value.problem.line, value.problem.column)
            is ProblemNode.File -> Triple(value.path, 0, 0)
            else -> return
        }
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), (column - 1).coerceAtLeast(0)).navigate(focus)
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is ProblemNode.Group -> {
                    icon = when (node.kind) {
                        ProblemKind.FAILURE -> AllIcons.General.Error
                        ProblemKind.TRANSITIVE -> AllIcons.Nodes.Related
                        ProblemKind.UNEXPECTED -> AllIcons.General.ExclMark
                    }
                    append(node.kind.plural, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(
                        " (${node.problems} ${StringUtil.pluralize("problem", node.problems)} in ${node.files} ${StringUtil.pluralize("file", node.files)})",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
                is ProblemNode.File -> {
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(node.path.substringAfterLast('/'))
                        .takeUnless { it is UnknownFileType }?.icon ?: AllIcons.FileTypes.Unknown
                    append(node.relative)
                    append(" (${node.problems} ${StringUtil.pluralize("problem", node.problems)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ProblemNode.Item -> {
                    val problem = node.problem
                    icon = if (problem.isError) AllIcons.General.Error else AllIcons.General.Warning
                    append(problem.message)
                    if (problem.line > 0) append(" :(${problem.line}, ${problem.column})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(" ${problem.kind.label}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                else -> append(value.toString())
            }
        }
    }

    override fun dispose() = Unit
}
