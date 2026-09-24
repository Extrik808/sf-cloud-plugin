package dev.sfcloud.lang

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.sfcloud.lsp.LwcLspServerSupportProvider

object LwcTemplate {
    private val HANDLER_ATTRIBUTE = Regex("""\bon[\w-]*\s*=\s*$""")

    fun propertyAt(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        var start = offset
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        var end = offset
        while (end < text.length && isIdentifierPart(text[end])) end++
        if (start == end || text[start].isDigit()) return null
        val open = start - 1
        if (open < 0 || text[open] != '{') return null
        var close = end
        while (close < text.length && (isIdentifierPart(text[close]) || text[close] == '.')) close++
        if (close >= text.length || text[close] != '}') return null
        val before = text.subSequence(maxOf(0, open - 64), open)
        if (HANDLER_ATTRIBUTE.containsMatchIn(before)) return null
        return text.subSequence(start, end).toString()
    }

    fun memberOffsets(js: CharSequence, name: String): List<Int> {
        val body = Regex("""\bclass\s+[\w$]+[^{]*\{""").find(js)?.range?.last ?: return emptyList()
        val n = Regex.escape(name)
        val accessor = Regex("""(?m)^\s*(?:@[\w$]+(?:\([^)]*\))?\s+)*(?:static\s+)?(?:get|set)\s+($n)\s*\(""")
        val field = Regex("""(?m)^\s*(?:@[\w$]+(?:\([^)]*\))?\s+)*(?:static\s+)?($n)\s*(?:=|;|$)""")
        val method = Regex("""(?m)^\s*(?:@[\w$]+(?:\([^)]*\))?\s+)*(?:static\s+)?(?:async\s+)?($n)\s*\([^)]*\)\s*\{""")
        for (pattern in listOf(accessor, field, method)) {
            val found = pattern.findAll(js, body).mapNotNull { it.groups[1]?.range?.first }.toList()
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}

class LwcGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val file = source?.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (virtualFile.extension?.lowercase() != "html" || !LwcLspServerSupportProvider.isLwcFile(virtualFile)) return null
        val name = LwcTemplate.propertyAt(editor.document.charsSequence, offset) ?: return null
        val script = componentScript(file) ?: return null
        val targets = LwcTemplate.memberOffsets(script.viewProvider.contents, name).mapNotNull { script.findElementAt(it) }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun componentScript(file: PsiFile): PsiFile? {
        val dir = file.containingDirectory ?: return null
        return dir.findFile("${dir.name}.js") ?: dir.findFile("${dir.name}.ts")
    }
}
