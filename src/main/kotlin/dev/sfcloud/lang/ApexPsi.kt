package dev.sfcloud.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.elementType
import com.intellij.util.IncorrectOperationException

object ApexElements {
    val DECLARATION = ApexTokenType("APEX_DECLARATION")
    val REFERENCE = ApexTokenType("APEX_REFERENCE")
}

class ApexNamedElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    val member: ApexMember?
        get() {
            val offset = textRange.startOffset
            return ApexStructure.of(containingFile).asSequence()
                .flatMap { it.flatten() }
                .firstOrNull { it.nameOffset == offset }
        }

    override fun getName(): String = text

    override fun getNameIdentifier(): PsiElement? = firstChild

    override fun setName(name: String): PsiElement =
        throw IncorrectOperationException("Apex declarations are renamed by the Apex language server")

    override fun getPresentation(): ItemPresentation? =
        member?.let { ApexMemberPresentation(it.presentableText, ApexMemberIcons.of(it)) }

    override fun getUseScope(): SearchScope {
        val member = member ?: return super.getUseScope()
        if (member.visibility == "private" && !member.kind.isType) return LocalSearchScope(containingFile)
        return super.getUseScope().intersectWith(GlobalSearchScope.projectScope(project))
    }

    override fun toString(): String = "ApexNamedElement:$text"
}

class ApexReferenceElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    val nameRange: TextRange
        get() {
            val text = text
            if (firstChild.elementType != ApexTokens.BIND_VARIABLE) return TextRange(0, text.length)
            val end = text.indexOf('.').takeIf { it > 1 } ?: text.length
            return TextRange(1, end)
        }

    val referenceName: String get() = nameRange.substring(text)

    override fun getReference(): PsiReference = ApexReference(this)

    override fun getReferences(): Array<PsiReference> = arrayOf(reference)

    override fun toString(): String = "ApexReferenceElement:$text"
}

class ApexReference(element: ApexReferenceElement) :
    PsiPolyVariantReferenceBase<ApexReferenceElement>(element, element.nameRange, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ApexResolver.targets(element).map { PsiElementResolveResult(it) }.toTypedArray()

    override fun getCanonicalText(): String = element.referenceName

    override fun handleElementRename(newElementName: String): PsiElement =
        throw IncorrectOperationException("Apex references are renamed by the Apex language server")
}
