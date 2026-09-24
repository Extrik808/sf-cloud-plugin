package dev.sfcloud.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.UIUtil
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.settings.SfCloudSettings
import org.jdom.Element
import java.awt.BorderLayout
import java.awt.Insets
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

object SfBundle {
    const val META_SUFFIX = "-meta.xml"

    private val GROUPS = listOf(
        setOf("cmp", "app", "evt", "intf", "tokens", "page", "component", "html"),
        setOf("cls", "trigger", "js", "ts", "mjs"),
        setOf("css"),
        setOf("auradoc", "design", "svg"),
    )

    fun files(file: VirtualFile): List<VirtualFile> {
        if (file.isDirectory) return emptyList()
        val directory = file.parent ?: return emptyList()
        val bundle = when (directory.parent?.name?.lowercase()) {
            "lwc", "aura" -> directory.children.filter { !it.isDirectory && !it.name.startsWith(".") }.sortedWith(ORDER)
            else -> metadataPair(file)
        }
        return if (bundle.size > 1 && bundle.any { it == file }) bundle else emptyList()
    }

    fun label(file: VirtualFile): String = file.name

    private fun metadataPair(file: VirtualFile): List<VirtualFile> {
        val directory = file.parent ?: return emptyList()
        if (file.name.endsWith(META_SUFFIX)) {
            val source = directory.findChild(file.name.removeSuffix(META_SUFFIX)) ?: return emptyList()
            return listOf(source, file)
        }
        val meta = directory.findChild(file.name + META_SUFFIX) ?: return emptyList()
        return listOf(file, meta)
    }

    private val ORDER = compareBy<VirtualFile>({ group(it) }, { it.name.lowercase() })

    private fun group(file: VirtualFile): Int {
        if (file.name.endsWith(META_SUFFIX)) return GROUPS.size + 1
        val extension = file.extension?.lowercase() ?: return GROUPS.size
        return GROUPS.indexOfFirst { extension in it }.takeIf { it >= 0 } ?: GROUPS.size
    }
}

class BundleEditorState(val selected: String, val delegate: FileEditorState?) : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
        if (otherState !is BundleEditorState || otherState.selected != selected) return false
        val other = otherState.delegate ?: return true
        return delegate?.canBeMergedWith(other, level) ?: true
    }
}

class BundleFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!SfCloudSettings.getInstance().state.bundleEditorTabs) return false
        if (!SfdxProject.isSfdx(project)) return false
        return SfBundle.files(file).size > 1
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        BundleFileEditor(project, file, SfBundle.files(file).filter { it.isValid })

    override fun disposeEditor(editor: FileEditor) = Disposer.dispose(editor)

    override fun getEditorTypeId(): String = TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun readState(element: Element, project: Project, file: VirtualFile): FileEditorState {
        val delegate = element.getChild(DELEGATE)?.let { TextEditorProvider.getInstance().readState(it, project, file) }
        return BundleEditorState(element.getAttributeValue(SELECTED).orEmpty(), delegate)
    }

    override fun writeState(state: FileEditorState, project: Project, element: Element) {
        if (state !is BundleEditorState) return
        element.setAttribute(SELECTED, state.selected)
        val delegate = state.delegate ?: return
        val child = Element(DELEGATE)
        TextEditorProvider.getInstance().writeState(delegate, project, child)
        element.addContent(child)
    }

    companion object {
        private const val TYPE_ID = "sf-cloud-bundle"
        private const val SELECTED = "selected"
        private const val DELEGATE = "text-editor"
    }
}

class BundleFileEditor(
    private val project: Project,
    private val anchor: VirtualFile,
    private val bundle: List<VirtualFile>,
) : UserDataHolderBase(), TextEditor {

    private val tabs = JBTabbedPane(SwingConstants.BOTTOM)
    private val holders = bundle.map { JPanel(BorderLayout()) }
    private val delegates = arrayOfNulls<TextEditor>(bundle.size)
    private val changes = PropertyChangeSupport(this)
    private val anchorIndex = bundle.indexOf(anchor).coerceAtLeast(0)
    private var shown = anchorIndex
    private var active = false

    init {
        tabs.tabComponentInsets = Insets(0, 0, 0, 0)
        bundle.forEachIndexed { index, file -> tabs.addTab(SfBundle.label(file), file.fileType.icon, holders[index]) }
        tabs.selectedIndex = anchorIndex
        mount(anchorIndex)
        tabs.addChangeListener { show(tabs.selectedIndex) }
    }

    override fun getComponent(): JComponent = tabs

    override fun getPreferredFocusedComponent(): JComponent? = delegate().preferredFocusedComponent

    override fun getEditor(): Editor = delegate().editor

    override fun getFile(): VirtualFile = anchor

    override fun getName(): String = "Text"

    override fun getState(level: FileEditorStateLevel): FileEditorState =
        BundleEditorState(bundle[shown].name, delegates[shown]?.getState(level))

    override fun setState(state: FileEditorState) {
        if (state !is BundleEditorState) return
        val index = bundle.indexOfFirst { it.name == state.selected }
        if (index >= 0 && index != shown) tabs.selectedIndex = index
        state.delegate?.let { delegate().setState(it) }
    }

    override fun isModified(): Boolean = delegates.any { it?.isModified == true }

    override fun isValid(): Boolean = delegate().isValid

    override fun selectNotify() {
        active = true
        delegates[shown]?.selectNotify()
    }

    override fun deselectNotify() {
        active = false
        delegates[shown]?.deselectNotify()
    }

    override fun getCurrentLocation(): FileEditorLocation? = delegates[shown]?.currentLocation

    override fun getStructureViewBuilder() = delegates[shown]?.structureViewBuilder

    override fun addPropertyChangeListener(listener: PropertyChangeListener) =
        changes.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        changes.removePropertyChangeListener(listener)

    override fun canNavigateTo(navigatable: Navigatable): Boolean =
        editorFor(navigatable)?.canNavigateTo(navigatable) == true

    override fun navigateTo(navigatable: Navigatable) {
        val index = indexOf(navigatable)
        if (index >= 0 && index != shown) tabs.selectedIndex = index
        delegate().navigateTo(navigatable)
    }

    override fun dispose() {
        delegates.filterNotNull().forEach { Disposer.dispose(it) }
    }

    private fun show(index: Int) {
        if (index < 0 || index >= bundle.size || index == shown) return
        val grabFocus = UIUtil.isFocusAncestor(tabs)
        val previous = delegates[shown]
        val editor = mount(index)
        if (editor == null) {
            tabs.selectedIndex = shown
            return
        }
        shown = index
        if (active) {
            previous?.deselectNotify()
            editor.selectNotify()
        }
        if (grabFocus) {
            ApplicationManager.getApplication().invokeLater {
                if (shown == index) editor.preferredFocusedComponent?.requestFocusInWindow()
            }
        }
    }

    private fun mount(index: Int): TextEditor? {
        delegates[index]?.let { return it }
        val file = bundle.getOrNull(index)?.takeIf { it.isValid } ?: return null
        val editor = runCatching {
            WriteIntentReadAction.compute<TextEditor?> {
                TextEditorProvider.getInstance().createEditor(project, file) as? TextEditor
            }
        }.getOrNull() ?: return null
        delegates[index] = editor
        editor.addPropertyChangeListener { event ->
            if (delegates[shown] === editor) {
                changes.firePropertyChange(event.propertyName, event.oldValue, event.newValue)
            }
        }
        holders[index].add(editor.component, BorderLayout.CENTER)
        holders[index].revalidate()
        return editor
    }

    private fun delegate(): TextEditor =
        delegates[shown] ?: mount(shown) ?: delegates[anchorIndex] ?: mount(anchorIndex)!!

    private fun indexOf(navigatable: Navigatable): Int {
        val file = (navigatable as? OpenFileDescriptor)?.file ?: return -1
        return bundle.indexOf(file)
    }

    private fun editorFor(navigatable: Navigatable): TextEditor? {
        val index = indexOf(navigatable)
        return if (index >= 0) mount(index) else delegates[shown]
    }
}
