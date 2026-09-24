package dev.sfcloud.log

import com.intellij.codeInsight.navigation.MethodNavigationOffsetProvider
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.sfcloud.lang.ApexMemberPresentation
import javax.swing.Icon

object ApexLogCache {
    fun of(file: PsiFile): ApexLog = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(ApexLogParser.parse(file.viewProvider.contents), file)
    }

    fun blocks(node: LogNode): List<LogNode> = node.children.filter { it.isBlock || it.event?.category == LogCategory.ERROR }

    fun nodeAtLine(root: LogNode, line: Int): LogNode? {
        var found: LogNode? = null
        var level = blocks(root)
        while (true) {
            val next = level.lastOrNull { node ->
                val event = node.event ?: return@lastOrNull false
                line >= event.line && line <= (node.end?.line ?: event.line)
            } ?: return found
            found = next
            level = blocks(next)
        }
    }

    fun icon(category: LogCategory): Icon = when (category) {
        LogCategory.EXECUTION -> AllIcons.Actions.Execute
        LogCategory.CODE_UNIT -> AllIcons.Nodes.Module
        LogCategory.METHOD -> AllIcons.Nodes.Method
        LogCategory.SYSTEM -> AllIcons.Nodes.Function
        LogCategory.SOQL -> AllIcons.Nodes.DataTables
        LogCategory.DML -> AllIcons.Actions.Edit
        LogCategory.CALLOUT -> AllIcons.General.Web
        LogCategory.DEBUG -> AllIcons.Debugger.Console
        LogCategory.ERROR -> AllIcons.General.Error
        LogCategory.LIMITS -> AllIcons.General.Gear
        LogCategory.AUTOMATION -> AllIcons.Nodes.Related
        LogCategory.STATEMENT -> AllIcons.Nodes.Variable
        LogCategory.OTHER -> AllIcons.Nodes.Tag
    }

    fun label(node: LogNode): String {
        val event = node.event ?: return ""
        val name = event.name.lineSequence().firstOrNull().orEmpty().ifEmpty { event.type }
        val millis = node.durationNanos / 1_000_000.0
        return if (node.isBlock) "$name  (%.2f ms)".format(millis) else name
    }
}

class ApexLogStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder = object : TreeBasedStructureViewBuilder() {
        override fun createStructureViewModel(editor: Editor?): StructureViewModel = ApexLogStructureViewModel(psiFile, editor)

        override fun isRootNodeShown(): Boolean = false
    }
}

class ApexLogStructureViewModel(private val file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, ApexLogNodeElement(file, null)),
    StructureViewModel.ElementInfoProvider {

    override fun getCurrentEditorElement(): Any? {
        val editor = editor ?: return null
        val line = editor.document.getLineNumber(editor.caretModel.offset)
        return ApexLogCache.nodeAtLine(ApexLogCache.of(file).root, line)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        (element.value as? LogNode)?.let { ApexLogCache.blocks(it).isEmpty() } ?: false
}

class ApexLogNodeElement(private val file: PsiFile, private val node: LogNode?) : StructureViewTreeElement {
    override fun getValue(): Any = node ?: file

    override fun getPresentation(): ItemPresentation {
        val event = node?.event ?: return ApexMemberPresentation(file.name, ApexLogFileType.ICON)
        return ApexMemberPresentation(ApexLogCache.label(node), ApexLogCache.icon(event.category))
    }

    override fun getChildren(): Array<StructureViewTreeElement> =
        ApexLogCache.blocks(node ?: ApexLogCache.of(file).root).map { ApexLogNodeElement(file, it) }.toTypedArray()

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        val line = node?.event?.line ?: return file.navigate(requestFocus)
        OpenFileDescriptor(file.project, virtualFile, line, 0).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()
}

class ApexLogMethodNavigationOffsetProvider : MethodNavigationOffsetProvider {
    override fun getMethodNavigationOffsets(file: PsiFile, caretOffset: Int): IntArray? {
        if (file !is ApexLogFile) return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        return ApexLogCache.of(file).events.asSequence()
            .filter { it.type in NAVIGABLE || it.category == LogCategory.ERROR || it.category == LogCategory.DEBUG }
            .map { it.line }
            .filter { it < document.lineCount }
            .map { document.getLineStartOffset(it) }
            .toList()
            .toIntArray()
    }

    companion object {
        private val NAVIGABLE = setOf("CODE_UNIT_STARTED", "METHOD_ENTRY", "CONSTRUCTOR_ENTRY", "SOQL_EXECUTE_BEGIN", "DML_BEGIN", "CALLOUT_REQUEST")
    }
}
