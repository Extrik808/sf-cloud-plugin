package dev.sfcloud.lang

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import dev.sfcloud.ost.OfflineSymbolTable

object ApexSignatures {
    fun owner(file: PsiFile, member: ApexMember): String? =
        ApexResolver.memberPath(ApexStructure.of(file), member.nameOffset)
            .filter { it.kind.isType && it != member }
            .joinToString(".") { it.name }
            .takeIf { it.isNotEmpty() }

    fun declaration(member: ApexMember): String {
        val modifiers = member.modifiers.filter { it != "global" || member.kind.isType }.joinToString(" ")
        val prefix = if (modifiers.isEmpty()) "" else "$modifiers "
        return when {
            member.kind == ApexMemberKind.CONSTRUCTOR -> "$prefix${member.name}(${member.parameters.orEmpty()})"
            member.kind.isCallable -> "$prefix${member.type.ifEmpty { "void" }} ${member.name}(${member.parameters.orEmpty()})"
            member.kind.isType -> "$prefix${member.kind.name.lowercase()} ${member.name}"
            member.kind == ApexMemberKind.ENUM_CONSTANT -> member.name
            else -> "$prefix${member.type} ${member.name}".trim()
        }
    }

    fun parameters(parameters: String?): List<String> {
        val text = parameters.orEmpty().trim()
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { index, c ->
            when (c) {
                '<' -> depth++
                '>' -> depth--
                ',' -> if (depth == 0) {
                    result += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += text.substring(start).trim()
        return result
    }

    fun docComment(file: PsiFile, member: ApexMember): String? {
        var leaf: PsiElement? = PsiTreeUtil.prevLeaf(file.findElementAt(member.startOffset) ?: return null)
        while (leaf is PsiWhiteSpace) leaf = PsiTreeUtil.prevLeaf(leaf)
        if (leaf !is PsiComment || leaf.elementType != ApexTokens.DOC_COMMENT) return null
        return leaf.text.removePrefix("/**").removeSuffix("*/").lines()
            .joinToString("\n") { it.trim().removePrefix("*").trim() }
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}

class ApexDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val (file, member) = target(element, originalElement) ?: return null
        val owner = ApexSignatures.owner(file, member)
        val source = if (isOffline(file)) "${OfflineSymbolTable.LIBRARY_NAME} (${file.virtualFile?.parent?.name.orEmpty()})" else file.name
        return buildString {
            append(DocumentationMarkup.DEFINITION_START)
            owner?.let { append(StringUtil.escapeXmlEntities(it)).append("<br>") }
            append("<b>").append(StringUtil.escapeXmlEntities(ApexSignatures.declaration(member))).append("</b>")
            append(DocumentationMarkup.DEFINITION_END)
            ApexSignatures.docComment(file, member)?.let {
                append(DocumentationMarkup.CONTENT_START)
                append(StringUtil.escapeXmlEntities(it).replace("\n", "<br>"))
                append(DocumentationMarkup.CONTENT_END)
            }
            append(DocumentationMarkup.SECTIONS_START)
            append(DocumentationMarkup.SECTION_HEADER_START).append("Source:").append(DocumentationMarkup.SECTION_SEPARATOR)
            append(StringUtil.escapeXmlEntities(source))
            append(DocumentationMarkup.SECTION_END)
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val (file, member) = target(element, originalElement) ?: return null
        val owner = ApexSignatures.owner(file, member)?.let { "$it\n" }.orEmpty()
        return StringUtil.escapeXmlEntities(owner + ApexSignatures.declaration(member)).replace("\n", "<br>")
    }

    private fun target(element: PsiElement?, originalElement: PsiElement?): Pair<PsiFile, ApexMember>? {
        val named = element as? ApexNamedElement ?: return null
        val origin = originalElement?.containingFile
        if (origin != null && ApexLspStatus.serves(origin)) return null
        val member = named.member ?: return null
        return named.containingFile to member
    }

    private fun isOffline(file: PsiFile): Boolean {
        val root = OfflineSymbolTable.getInstance(file.project).root() ?: return false
        return file.virtualFile?.parent == root
    }
}

class ApexParameterInfoHandler : ParameterInfoHandler<PsiElement, ApexMember> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val file = context.file as? ApexFile ?: return null
        if (ApexLspStatus.serves(file)) return null
        val open = openParenthesis(file, context.offset) ?: return null
        val overloads = overloads(open).takeIf { it.isNotEmpty() } ?: return null
        context.itemsToShow = overloads.toTypedArray()
        return open
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        val file = context.file as? ApexFile ?: return null
        return openParenthesis(file, context.offset)
    }

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        if (context.parameterOwner != null && context.parameterOwner != parameterOwner) {
            context.removeHint()
            return
        }
        context.parameterOwner = parameterOwner
        context.setCurrentParameter(argumentIndex(parameterOwner, context.offset))
    }

    override fun updateUI(member: ApexMember, context: ParameterInfoUIContext) {
        val parameters = ApexSignatures.parameters(member.parameters)
        if (parameters.isEmpty()) {
            context.setupUIComponentPresentation("<no parameters>", -1, -1, !context.isUIComponentEnabled, false, false, context.defaultParameterColor)
            return
        }
        val current = context.currentParameterIndex
        val text = StringBuilder()
        var start = -1
        var end = -1
        parameters.forEachIndexed { index, parameter ->
            if (index > 0) text.append(", ")
            if (index == current) start = text.length
            text.append(parameter)
            if (index == current) end = text.length
        }
        context.setupUIComponentPresentation(text.toString(), start, end, !context.isUIComponentEnabled, false, false, context.defaultParameterColor)
    }

    fun overloadsAt(file: PsiFile, offset: Int): List<ApexMember> = openParenthesis(file, offset)?.let { overloads(it) }.orEmpty()

    fun argumentAt(file: PsiFile, offset: Int): Int = openParenthesis(file, offset)?.let { argumentIndex(it, offset) } ?: -1

    private fun openParenthesis(file: PsiFile, offset: Int): PsiElement? {
        val text = file.viewProvider.contents
        var depth = 0
        var i = minOf(offset, text.length) - 1
        while (i >= 0) {
            when (text[i]) {
                ')', '}' -> depth++
                '(' -> if (depth == 0) {
                    return file.findElementAt(i)?.takeIf { it.elementType == ApexTokens.LPAREN }
                } else {
                    depth--
                }
                '{' -> if (depth == 0) return null else depth--
                ';' -> if (depth == 0) return null
            }
            i--
        }
        return null
    }

    private fun overloads(open: PsiElement): List<ApexMember> {
        var leaf = PsiTreeUtil.prevLeaf(open)
        while (leaf is PsiWhiteSpace || leaf is PsiComment) leaf = PsiTreeUtil.prevLeaf(leaf)
        val reference = leaf?.parent as? ApexReferenceElement ?: return emptyList()
        val targets = ApexResolver.targets(reference).mapNotNull { target -> target.member?.let { target.containingFile to it } }
        val callables = targets.filter { it.second.kind.isCallable }.map { it.second }
        if (callables.isNotEmpty()) return callables.distinctBy { it.parameters.orEmpty().lowercase() }
        return targets.filter { it.second.kind.isType }
            .flatMap { (_, type) -> type.children.filter { it.kind == ApexMemberKind.CONSTRUCTOR } }
            .distinctBy { it.parameters.orEmpty().lowercase() }
    }

    private fun argumentIndex(open: PsiElement, offset: Int): Int {
        val text = open.containingFile.viewProvider.contents
        var depth = 0
        var index = 0
        var i = open.textRange.endOffset
        val end = minOf(offset, text.length)
        var quoted = false
        while (i < end) {
            val c = text[i]
            when {
                c == '\'' -> quoted = !quoted
                quoted -> Unit
                c == '<' && i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_') -> i = typeArgumentsEnd(text, i, end)
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> index++
            }
            i++
        }
        return index
    }

    private fun typeArgumentsEnd(text: CharSequence, open: Int, end: Int): Int {
        var depth = 0
        var i = open
        while (i < end) {
            when (val c = text[i]) {
                '<' -> depth++
                '>' -> if (--depth == 0) return i
                else -> if (!(c.isLetterOrDigit() || c == '_' || c == '.' || c == ',' || c.isWhitespace())) return open
            }
            i++
        }
        return open
    }
}
