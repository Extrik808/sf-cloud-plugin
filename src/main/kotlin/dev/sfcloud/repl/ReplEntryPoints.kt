package dev.sfcloud.repl

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.lang.AnonymousApexFileType
import dev.sfcloud.lang.ApexLanguage
import dev.sfcloud.lang.ApexTokens
import dev.sfcloud.lang.SfCloudIcons
import dev.sfcloud.lang.SoqlFileType
import dev.sfcloud.lang.SoqlStatements
import dev.sfcloud.lang.SoslFileType

class ReplRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null || element is PsiWhiteSpace || element is PsiComment) return null
        val file = element.containingFile ?: return null
        if (!SfdxProject.isSfdx(file.project)) return null
        return when (file.viewProvider.fileType) {
            AnonymousApexFileType -> whole(element, file, AnonymousApexKind, "Execute Anonymous Apex")
            SoqlFileType -> whole(element, file, SoqlQueryKind, "Execute SOQL Query")
            SoslFileType -> whole(element, file, SoslQueryKind, "Execute SOSL Query")
            else -> if (file.language == ApexLanguage) inline(element) else null
        }
    }

    private fun whole(element: PsiElement, file: PsiFile, kind: ReplKind, text: String): Info? {
        if (firstCode(file) != element) return null
        val source = { ReplSource(kind, file.viewProvider.document?.text ?: file.text, file.virtualFile?.path) }
        return Info(AllIcons.RunConfigurations.TestState.Run, arrayOf(run(text, true, source), run("Open in ${kind.id} tool window", false, source))) { text }
    }

    private fun inline(element: PsiElement): Info? {
        if (element.elementType != ApexTokens.LBRACKET) return null
        val text = element.containingFile.viewProvider.contents
        if (!SoqlStatements.startsQuery(text, element.textRange.endOffset)) return null
        val query = ReplSources.queryAt(text, element.textRange.endOffset, true) ?: return null
        val kind = ReplSources.route(SoqlQueryKind, query)
        val source = { ReplSource(kind, query, null) }
        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            arrayOf(run("Execute in ${kind.id} tool window", true, source), run("Open in ${kind.id} tool window", false, source)),
        ) { "Execute ${kind.id}" }
    }

    private fun firstCode(file: PsiFile): PsiElement? {
        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.textLength == 0)) leaf = PsiTreeUtil.nextLeaf(leaf)
        return leaf
    }

    private fun run(text: String, execute: Boolean, source: () -> ReplSource): AnAction =
        object : DumbAwareAction(text, null, if (execute) AllIcons.Actions.Execute else SfCloudIcons.AnonymousApex) {
            override fun actionPerformed(e: AnActionEvent) {
                ReplSources.run(e.project ?: return, source(), execute)
            }
        }
}

abstract class ReplIntention : IntentionAction, DumbAware {
    override fun getFamilyName(): String = "SF Cloud"

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    protected fun enabled(project: Project, file: PsiFile?): Boolean = file != null && SfdxProject.isSfdx(project)
}

class ExecuteQueryIntention : ReplIntention() {
    private var label = "Execute query in SOQL Query tool window"

    override fun getText(): String = label

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || !enabled(project, file) || editor.selectionModel.hasSelection()) return false
        val query = ReplSources.queryAt(editor.document.charsSequence, editor.caretModel.offset, file?.language == ApexLanguage) ?: return false
        label = "Execute query in ${ReplSources.route(SoqlQueryKind, query).id} tool window"
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        editor ?: return
        val source = ReplSources.of(SoqlQueryKind, editor, null, file) ?: return
        ReplSources.run(project, source, true)
    }
}

class ExecuteSelectionIntention : ReplIntention() {
    override fun getText(): String = "Execute selection as anonymous Apex"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && enabled(project, file) && editor.selectionModel.selectedText?.isNotBlank() == true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val source = ReplSources.of(AnonymousApexKind, editor ?: return, null, file) ?: return
        ReplSources.run(project, source, true)
    }
}
