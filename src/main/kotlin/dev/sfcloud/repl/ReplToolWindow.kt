package dev.sfcloud.repl

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.org.ConnectionComboBox
import dev.sfcloud.settings.ReplKindState
import dev.sfcloud.settings.ReplTabState
import dev.sfcloud.settings.ToolWindowSettings
import dev.sfcloud.tools.ScriptEditor
import dev.sfcloud.ui.SfUi
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

abstract class ReplKind(
    val id: String,
    val fileType: FileType,
    val defaultBody: String,
) {
    val extension: String get() = fileType.defaultExtension
    val notificationGroup: String get() = "SF Cloud $id"

    abstract fun createTab(window: ReplWindow, state: ReplTabState): ReplTab

    abstract fun configure(project: Project, settings: ReplKindState): DialogWrapper
}

abstract class ReplToolWindowFactory(private val kind: ReplKind) : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean = SfdxProject.isSfdx(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        ReplWindow.create(project, toolWindow, kind)
    }
}

class ReplWindow private constructor(val project: Project, val toolWindow: ToolWindow, val kind: ReplKind) {
    val settings: ReplKindState = ToolWindowSettings.getInstance(project).repl(kind.id)
    private val tabs = LinkedHashMap<Content, ReplTab>()
    private var restoring = false

    val selected: ReplTab? get() = toolWindow.contentManager.selectedContent?.let { tabs[it] }

    private fun restore() {
        restoring = true
        try {
            settings.tabs.toList().forEach { add(it) }
            if (tabs.isEmpty()) newTab()
            toolWindow.contentManager.contents.getOrNull(settings.selectedTab)?.let { toolWindow.contentManager.setSelectedContent(it) }
        } finally {
            restoring = false
        }
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                val tab = tabs.remove(event.content) ?: return
                settings.tabs.remove(tab.state)
                if (tabs.isEmpty() && !project.isDisposed) newTab()
            }

            override fun selectionChanged(event: ContentManagerEvent) {
                val index = toolWindow.contentManager.getIndexOfContent(toolWindow.contentManager.selectedContent ?: return)
                if (index >= 0) settings.selectedTab = index
            }
        })
    }

    fun newTab(body: String = kind.defaultBody, name: String = uniqueName(kind.id), filePath: String? = null): ReplTab {
        val state = ReplTabState().apply {
            this.name = name
            this.body = body
            this.filePath = filePath.orEmpty()
            connection = selected?.connection?.selectedKey.orEmpty()
        }
        settings.tabs.add(state)
        return add(state)
    }

    private fun add(state: ReplTabState): ReplTab {
        val tab = kind.createTab(this, state)
        val content = ContentFactory.getInstance().createContent(tab.component, tab.title(), false)
        content.isCloseable = true
        content.setDisposer(tab)
        content.setPreferredFocusedComponent { tab.editor.editor.contentComponent }
        tabs[content] = tab
        toolWindow.contentManager.addContent(content)
        if (!restoring) toolWindow.contentManager.setSelectedContent(content)
        return tab
    }

    fun close(tab: ReplTab) {
        val content = tabs.entries.firstOrNull { it.value == tab }?.key ?: return
        if (settings.preventLoss && tab.hasUnsavedChanges()) {
            val message = if (tab.state.filePath.isNullOrBlank()) {
                "Close tab `${tab.state.name}`? All contents will be lost."
            } else {
                "Close tab `${tab.state.name}`? Unsaved changes to `${tab.state.filePath}` will be lost."
            }
            if (Messages.showOkCancelDialog(project, message, SfUi.TITLE, "Close", Messages.getCancelButton(), Messages.getQuestionIcon()) != Messages.OK) return
        }
        toolWindow.contentManager.removeContent(content, true)
    }

    fun rename(tab: ReplTab) {
        val validator = object : InputValidator {
            override fun checkInput(inputString: String?): Boolean =
                !inputString.isNullOrBlank() && tabs.values.none { it != tab && it.state.name == inputString.trim() }

            override fun canClose(inputString: String?): Boolean = checkInput(inputString)
        }
        val name = Messages.showInputDialog(project, "Rename ${kind.id} tab `${tab.state.name}` to:", "Rename", null, tab.state.name, validator) ?: return
        tab.state.name = name.trim()
        refreshTitle(tab)
    }

    fun refreshTitle(tab: ReplTab) {
        tabs.entries.firstOrNull { it.value == tab }?.key?.displayName = tab.title()
    }

    fun load(tab: ReplTab) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Load ${kind.id}")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        if (!file.extension.equals(kind.extension, true)) {
            Messages.showErrorDialog(project, "Invalid or missing file. Please choose a file with the `.${kind.extension}` extension.", SfUi.TITLE)
            return
        }
        val choice = Messages.showDialog(
            project,
            "Would you like to load file `${file.path}` in a new tab or replace the current tab?",
            SfUi.TITLE,
            arrayOf("New Tab", "Current Tab", Messages.getCancelButton()),
            0,
            Messages.getQuestionIcon(),
        )
        val text = VfsUtilCore.loadText(file)
        when (choice) {
            0 -> newTab(text, file.nameWithoutExtension, file.path)
            1 -> {
                tab.state.name = file.nameWithoutExtension
                tab.state.filePath = file.path
                tab.replaceBody(text)
                refreshTitle(tab)
            }
        }
    }

    fun save(tab: ReplTab) {
        val descriptor = FileSaverDescriptor("Save ${kind.id}", "", kind.extension)
        val base = tab.state.filePath?.takeIf { it.isNotBlank() }?.let { File(it).parentFile }
            ?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(it) }
            ?: SfdxProject.root(project)
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(base, "${tab.state.name}.${kind.extension}") ?: return
        wrapper.file.writeText(tab.editor.text)
        wrapper.getVirtualFile(true)
        tab.state.filePath = wrapper.file.path
        tab.state.name = wrapper.file.nameWithoutExtension
        tab.markSaved()
        refreshTitle(tab)
        SfUi.notify(project, kind.notificationGroup, "Saved", "Saved `${wrapper.file.path}`.")
    }

    fun configure() {
        kind.configure(project, settings).show()
    }

    private fun uniqueName(base: String): String {
        val names = tabs.values.map { it.state.name }.toSet()
        var index = 1
        while ("$base $index" in names) index++
        return "$base $index"
    }

    companion object {
        private val windows = java.util.concurrent.ConcurrentHashMap<Pair<Project, String>, ReplWindow>()

        fun create(project: Project, toolWindow: ToolWindow, kind: ReplKind): ReplWindow {
            val window = ReplWindow(project, toolWindow, kind)
            windows[project to kind.id] = window
            Disposer.register(toolWindow.disposable) { windows.remove(project to kind.id) }
            window.restore()
            return window
        }

        fun open(project: Project, kindId: String, body: String?, name: String? = null, filePath: String? = null, then: (ReplTab) -> Unit = {}) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(kindId) ?: return
            toolWindow.show {
                val window = windows[project to kindId] ?: return@show
                val tab = when {
                    body == null -> window.selected ?: return@show
                    filePath != null -> window.tabs.values.firstOrNull { it.state.filePath == filePath }?.also { it.replaceBody(body) }
                        ?: window.newTab(body, name ?: File(filePath).nameWithoutExtension, filePath)
                    else -> window.newTab(body)
                }
                window.tabs.entries.firstOrNull { it.value == tab }?.key?.let { toolWindow.contentManager.setSelectedContent(it, true) }
                then(tab)
            }
        }
    }
}

abstract class ReplTab(val project: Project, val window: ReplWindow, val state: ReplTabState) : Disposable {
    private var savedBody: String? = state.body.takeIf { !state.filePath.isNullOrBlank() }
    val connection = ConnectionComboBox(project, this, state.connection) { state.connection = it.orEmpty() }
    val editor = ScriptEditor(
        project,
        "${state.name.orEmpty().replace(Regex("[^A-Za-z0-9_]"), "_")}.${window.kind.extension}",
        window.kind.fileType,
        state.body.orEmpty(),
        this,
    ) { state.body = it }
    private val splitter = SfUi.splitter(false, state.splitterProportion) { state.splitterProportion = it }

    @Volatile
    var running = false
        protected set

    val executeAction: AnAction = SfUi.action("Execute", "Execute", AllIcons.Actions.Execute, { !running }) { execute(editor.selectedOrAllText) }
    val newTabAction: AnAction = SfUi.action("New Tab", "New tab", AllIcons.General.Add) { window.newTab() }
    val closeTabAction: AnAction = SfUi.action("Close Tab", "Close tab", AllIcons.General.Remove) { window.close(this) }
    val loadAction: AnAction = SfUi.action("Load", "Load", AllIcons.Actions.MenuOpen) { window.load(this) }
    val saveAction: AnAction = SfUi.action("Save", "Save", AllIcons.Actions.MenuSaveall) { window.save(this) }
    val renameAction: AnAction = SfUi.action("Rename", "Rename", AllIcons.Actions.EditSource) { window.rename(this) }

    fun configureAction(description: String): AnAction =
        SfUi.action("Configure", description, AllIcons.General.GearPlain) { window.configure() }

    val component: JComponent by lazy {
        splitter.firstComponent = editor.component
        splitter.secondComponent = results()
        val north = listOfNotNull(connection, header()).toTypedArray()
        val panel = SfUi.verticalToolbarPanel("SfCloud${window.kind.id.replace(" ", "")}", toolbar(), SfUi.north(SfUi.stack(*north), splitter))
        SfUi.shortcut(executeAction, editor.editor.contentComponent, "alt X", "meta ENTER", "control ENTER")
        SfUi.shortcut(newTabAction, panel, "alt INSERT")
        SfUi.shortcut(closeTabAction, panel, "control shift F4")
        SfUi.shortcut(loadAction, panel, "alt L")
        SfUi.shortcut(saveAction, panel, "alt S")
        SfUi.shortcut(renameAction, panel, "alt R")
        panel
    }

    protected open fun header(): JComponent? = null

    protected abstract fun results(): JComponent

    protected abstract fun toolbar(): ActionGroup

    abstract fun execute(text: String)

    fun title(): String = state.filePath?.takeIf { it.isNotBlank() }?.let { "${state.name} [$it]" } ?: state.name.orEmpty()

    fun hasUnsavedChanges(): Boolean = editor.text.isNotBlank() && editor.text != savedBody

    fun markSaved() {
        savedBody = editor.text
    }

    fun replaceBody(text: String) {
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.editor.document.setText(text)
        }
        savedBody = text
    }

    fun moveCaret(line: Int, column: Int) {
        val document = editor.editor.document
        val index = (line - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        if (document.lineCount == 0) return
        val offset = (document.getLineStartOffset(index) + (column - 1).coerceAtLeast(0)).coerceAtMost(document.getLineEndOffset(index))
        editor.editor.caretModel.moveToOffset(offset)
        editor.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(editor.editor.contentComponent, true)
    }

    protected fun error(title: String): (String) -> Unit = { message ->
        running = false
        SfUi.notify(project, window.kind.notificationGroup, title, message, NotificationType.ERROR)
    }

    override fun dispose() = Unit

    companion object {
        val VALIDATOR_ICON = IconLoader.getIcon("/icons/validator.svg", ReplTab::class.java)
    }
}

abstract class ReplConfigDialog(project: Project, title: String) : DialogWrapper(project, true) {
    init {
        this.title = title
    }

    protected fun check(text: String, tooltip: String, value: Boolean): JBCheckBox = JBCheckBox(text, value).apply { toolTipText = tooltip }

    protected fun indented(component: JComponent): JComponent = JPanel(java.awt.BorderLayout()).apply {
        border = JBUI.Borders.emptyLeft(20)
        add(component)
    }
}
