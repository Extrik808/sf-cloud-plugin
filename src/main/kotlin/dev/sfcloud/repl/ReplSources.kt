package dev.sfcloud.repl

import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.sfcloud.lang.ApexLanguage
import dev.sfcloud.lang.SoqlStatements
import dev.sfcloud.ui.SfUi

class ReplSource(val kind: ReplKind, val body: String, val filePath: String?)

object ReplSources {
    private const val QUOTES = "'\"`"

    fun of(kind: ReplKind, editor: Editor?, file: VirtualFile?, psi: PsiFile?): ReplSource? {
        val selection = editor?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }
        if (selection != null) return ReplSource(route(kind, selection), selection, null)
        if (file != null && file.fileType == kind.fileType) {
            val text = editor?.document?.text ?: VfsUtilCore.loadText(file)
            return ReplSource(kind, text, file.path)
        }
        if (kind == AnonymousApexKind || editor == null) return null
        val query = queryAt(editor.document.charsSequence, editor.caretModel.offset, psi?.language == ApexLanguage) ?: return null
        return ReplSource(route(kind, query), query, null)
    }

    fun queryAt(text: CharSequence, offset: Int, apex: Boolean): String? {
        if (apex) {
            SoqlStatements.inlineAt(text, offset)?.let { range ->
                return text.subSequence(range.first, range.last + 1).toString().trim().takeIf { it.isNotEmpty() }
            }
        }
        return literalAt(text, offset)
    }

    fun route(kind: ReplKind, body: String): ReplKind = when {
        kind == AnonymousApexKind -> kind
        SEARCH.containsMatchIn(body) -> SoslQueryKind
        else -> SoqlQueryKind
    }

    fun run(project: Project, source: ReplSource, execute: Boolean) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val binds = execute && source.kind != AnonymousApexKind && hasBinds(source.body)
        ReplWindow.open(project, source.kind.id, source.body, filePath = source.filePath) { tab ->
            when {
                binds -> SfUi.notify(
                    project,
                    source.kind.notificationGroup,
                    "Query not executed",
                    "The query uses Apex bind variables. Replace them with values and execute it from the ${source.kind.id} tool window.",
                    NotificationType.WARNING,
                )
                execute -> tab.execute(source.body)
            }
        }
    }

    fun hasBinds(query: String): Boolean {
        var quoted = false
        query.forEachIndexed { index, c ->
            when {
                c == '\'' && query.getOrNull(index - 1) != '\\' -> quoted = !quoted
                !quoted && c == ':' && query.getOrNull(index + 1)?.let { it.isLetter() || it == '_' } == true -> return true
            }
        }
        return false
    }

    private fun literalAt(text: CharSequence, offset: Int): String? {
        val safe = offset.coerceIn(0, text.length)
        var start = safe
        while (start > 0 && text[start - 1] !in QUOTES && text[start - 1] != '\n') start--
        if (start == 0 || text[start - 1] !in QUOTES) return null
        val quote = text[start - 1]
        var end = safe
        while (end < text.length && text[end] != quote && (text[end] != '\n' || quote == '`')) end++
        if (end >= text.length || text[end] != quote) return null
        val body = text.subSequence(start, end).toString().trim()
        return body.takeIf { SoqlStatements.startsQuery(it, 0) }
    }

    private val SEARCH = Regex("""^\s*FIND\s""", RegexOption.IGNORE_CASE)
}
