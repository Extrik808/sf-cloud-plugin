package dev.sfcloud.lang

import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.hints.codeVision.ReferencesCodeVisionProvider
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import java.awt.event.MouseEvent

object ApexUsages {
    const val LIMIT = 100

    fun hint(count: Int): String = when {
        count == 0 -> "no usages"
        count == 1 -> "1 usage"
        count >= LIMIT -> "$LIMIT+ usages"
        else -> "$count usages"
    }

    fun count(target: ApexNamedElement): Int {
        var count = 0
        ReferencesSearch.search(target, target.useScope).forEach(
            Processor {
                count++
                count < LIMIT
            },
        )
        return count
    }
}

class ApexUsagesCodeVisionProvider : ReferencesCodeVisionProvider() {
    override val id: String = "sfcloud.apex.references"

    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

    override fun acceptsFile(file: PsiFile): Boolean = file is ApexFile && file.virtualFile != null

    override fun acceptsElement(element: PsiElement): Boolean =
        element is ApexNamedElement && element.member?.kind != ApexMemberKind.TRIGGER

    override fun getHint(element: PsiElement, file: PsiFile): String? {
        val target = element as? ApexNamedElement ?: return null
        return ApexUsages.hint(ApexUsages.count(target))
    }

    override fun handleClick(editor: Editor, element: PsiElement, event: MouseEvent?) {
        editor.caretModel.moveToOffset(element.textRange.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        val action = ActionManager.getInstance().getAction("ShowUsages") ?: return
        ActionUtil.invokeAction(action, editor.component, ActionPlaces.EDITOR_INLAY, event, null)
    }
}
