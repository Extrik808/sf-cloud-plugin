package dev.sfcloud.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.project.DumbAwareAction
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

class ScriptEditor(
    project: Project,
    fileName: String,
    fileType: FileType,
    initialText: String,
    parent: Disposable,
    onChange: (String) -> Unit,
) {
    private val file = LightVirtualFile(fileName, fileType, initialText)
    private val document: Document = WriteIntentReadAction.compute<Document> {
        FileDocumentManager.getInstance().getDocument(file) ?: EditorFactory.getInstance().createDocument(initialText)
    }
    val editor: EditorEx = WriteIntentReadAction.compute<EditorEx> {
        EditorFactory.getInstance().createEditor(document, project, file, false) as EditorEx
    }

    init {
        editor.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            additionalLinesCount = 1
            isLineMarkerAreaShown = false
        }
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = onChange(document.text)
        }, parent)
        Disposer.register(parent) { EditorFactory.getInstance().releaseEditor(editor) }
    }

    val component: JComponent get() = editor.component

    val text: String get() = document.text

    val selectedOrAllText: String
        get() = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: document.text

    fun bindRunShortcut(action: () -> Unit) {
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
        val metaStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK)
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = action()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyboardShortcut(stroke, null), KeyboardShortcut(metaStroke, null)), editor.contentComponent)
    }
}
