package dev.sfcloud.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import dev.sfcloud.api.SfApiException
import dev.sfcloud.core.SfCliException
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.KeyStroke

object SfUi {
    const val TITLE = "SF Cloud"

    fun verticalToolbarPanel(place: String, group: ActionGroup, content: JComponent): SimpleToolWindowPanel {
        val panel = SimpleToolWindowPanel(false, true)
        panel.border = JBUI.Borders.empty()
        val toolbar = ActionManager.getInstance().createActionToolbar(place, group, false)
        toolbar.targetComponent = panel
        panel.toolbar = toolbar.component
        panel.setContent(content)
        return panel
    }

    fun splitter(vertical: Boolean, proportion: Float, onChange: (Float) -> Unit): OnePixelSplitter {
        val splitter = OnePixelSplitter(vertical, proportion)
        splitter.addPropertyChangeListener("proportion") { onChange(splitter.proportion) }
        return splitter
    }

    fun action(text: String, description: String?, icon: Icon?, enabled: () -> Boolean = { true }, perform: (AnActionEvent) -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform(e)
        }

    fun toggle(text: String, description: String?, icon: Icon?, get: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun isSelected(e: AnActionEvent) = get()

            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
        }

    fun group(vararg actions: AnAction?): DefaultActionGroup = DefaultActionGroup().apply {
        actions.forEach { if (it == null) addSeparator() else add(it) }
    }

    fun shortcut(action: AnAction, component: JComponent, vararg strokes: String) {
        val shortcuts = strokes.mapNotNull { KeyStroke.getKeyStroke(it) }.map { KeyboardShortcut(it, null) }
        action.registerCustomShortcutSet(CustomShortcutSet(*shortcuts.toTypedArray()), component)
    }

    fun notify(project: Project, group: String, title: String, content: String = "", type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup(group).createNotification(title, content, type).notify(project)
    }

    fun background(project: Project, title: String, cancellable: Boolean = true, onError: (String) -> Unit, work: (ProgressIndicator) -> Unit) {
        object : Task.Backgroundable(project, title, cancellable) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    work(indicator)
                } catch (e: SfApiException) {
                    if (e.message != "Cancelled") onError(e.message.orEmpty())
                } catch (e: SfCliException) {
                    if (e.message != "Cancelled") onError(e.message.orEmpty())
                } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    onError(e.message ?: e.javaClass.simpleName)
                }
            }
        }.queue()
    }

    fun edt(project: Project, block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, project.disposed)
    }

    fun section(title: String): JComponent {
        val panel = JPanel(GridBagLayout())
        val label = JLabel(title)
        label.font = label.font.deriveFont(java.awt.Font.BOLD)
        val c = GridBagConstraints()
        c.gridx = 0
        panel.add(label, c)
        c.gridx = 1
        c.weightx = 1.0
        c.fill = GridBagConstraints.HORIZONTAL
        c.insets = JBUI.insetsLeft(6)
        panel.add(JSeparator(), c)
        return panel
    }

    fun stack(vararg components: JComponent): JPanel {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.gridx = 0
        c.weightx = 1.0
        c.fill = GridBagConstraints.HORIZONTAL
        c.insets = JBUI.insets(2, 0)
        components.forEachIndexed { index, component ->
            c.gridy = index
            panel.add(component, c)
        }
        return panel
    }

    fun north(north: JComponent, center: JComponent): JPanel = JPanel(BorderLayout()).apply {
        add(north, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
    }

    fun expandSubtree(tree: javax.swing.JTree, path: javax.swing.tree.TreePath) {
        tree.expandPath(path)
        val node = path.lastPathComponent as? javax.swing.tree.TreeNode ?: return
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index)
            if (!child.isLeaf) expandSubtree(tree, path.pathByAddingChild(child))
        }
    }

    private val DATE = ThreadLocal.withInitial { SimpleDateFormat("yyyy/MM/dd, HH:mm:ss") }

    fun formatDate(instant: Instant?): String = instant?.let { DATE.get().format(Date.from(it)) } ?: "<none>"
}
