package dev.sfcloud.lang

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.RequestResultProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.tree.TokenSet
import com.intellij.util.Processor

class ApexFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        ApexLexer(),
        TokenSet.create(ApexTokens.IDENTIFIER),
        ApexTokens.COMMENTS,
        TokenSet.create(ApexTokens.STRING, ApexTokens.BIND_VARIABLE),
    )

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean = psiElement is ApexNamedElement

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when ((element as? ApexNamedElement)?.member?.kind) {
        ApexMemberKind.CLASS -> "class"
        ApexMemberKind.INTERFACE -> "interface"
        ApexMemberKind.ENUM -> "enum"
        ApexMemberKind.TRIGGER -> "trigger"
        ApexMemberKind.CONSTRUCTOR -> "constructor"
        ApexMemberKind.METHOD -> "method"
        ApexMemberKind.PROPERTY -> "property"
        ApexMemberKind.FIELD -> "field"
        ApexMemberKind.ENUM_CONSTANT -> "enum constant"
        null -> "declaration"
    }

    override fun getDescriptiveName(element: PsiElement): String {
        val named = element as? ApexNamedElement ?: return ""
        val owner = named.containingFile?.virtualFile?.nameWithoutExtension
        val member = named.member ?: return named.name
        return if (owner == null || member.kind.isType) member.name else "$owner.${member.presentableText}"
    }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        (element as? ApexNamedElement)?.member?.presentableText ?: getDescriptiveName(element)
}

class ApexReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = queryParameters.elementToSearch as? ApexNamedElement ?: return
        val name = target.name.takeIf { it.isNotEmpty() } ?: return
        val scope = queryParameters.effectiveSearchScope
        val apexContext = (UsageSearchContext.IN_CODE.toInt() or UsageSearchContext.IN_STRINGS.toInt()).toShort()
        queryParameters.optimizer.searchWord(name, scope, apexContext, false, target)
        val member = target.member ?: return
        if (!member.kind.isType && !(member.isStatic && member.kind.isCallable)) return
        queryParameters.optimizer.searchWord(name, scope, UsageSearchContext.ANY, false, target, ApexExternalUsages(target, member))
    }
}

class ApexExternalUsages(private val target: ApexNamedElement, private val member: ApexMember) : RequestResultProcessor(target, member.name) {
    override fun processTextOccurrence(element: PsiElement, offsetInElement: Int, consumer: Processor<in PsiReference>): Boolean {
        if (element.firstChild != null) return true
        val file = element.containingFile ?: return true
        if (file is ApexFile) return true
        val text = element.text
        val end = offsetInElement + member.name.length
        if (end > text.length || !text.regionMatches(offsetInElement, member.name, 0, member.name.length, true)) return true
        if (!matches(file, text, offsetInElement, end)) return true
        return consumer.process(ApexTextReference(element, TextRange(offsetInElement, end), target))
    }

    private fun matches(file: PsiFile, text: String, start: Int, end: Int): Boolean {
        val extension = file.virtualFile?.extension?.lowercase() ?: file.name.substringAfterLast('.', "").lowercase()
        return if (extension in SCRIPTS) isApexImport(text, start) else isWholeValue(text, start, end)
    }

    private fun isApexImport(text: String, start: Int): Boolean {
        val prefix = text.substring(0, start)
        val owner = target.containingFile?.virtualFile?.nameWithoutExtension
        return IMPORTS.any { import ->
            when {
                member.kind.isType -> prefix.endsWith(import, true) || prefix.endsWith("$import$NAMESPACE_SEPARATOR", true)
                owner == null -> false
                else -> prefix.endsWith("$import$owner.", true) || prefix.endsWith("$import$NAMESPACE_SEPARATOR$owner.", true)
            }
        }
    }

    private fun isWholeValue(text: String, start: Int, end: Int): Boolean {
        if (!member.kind.isType) return false
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        return (before == null || before in VALUE_BOUNDARY) && (after == null || after in VALUE_BOUNDARY)
    }

    companion object {
        private const val NAMESPACE_SEPARATOR = "__"
        private val SCRIPTS = setOf("js", "ts", "mjs", "cjs")
        private val IMPORTS = listOf("@salesforce/apex/", "@salesforce/apexContinuation/")
        private const val VALUE_BOUNDARY = "\"'<>, \t\n:/"
    }
}

class ApexTextReference(element: PsiElement, range: TextRange, private val target: PsiElement) :
    PsiReferenceBase<PsiElement>(element, range, true) {

    override fun resolve(): PsiElement = target

    override fun handleElementRename(newElementName: String): PsiElement = element
}

class ApexRenameVeto : Condition<PsiElement> {
    override fun value(element: PsiElement): Boolean = element is ApexNamedElement || element is ApexReferenceElement
}
