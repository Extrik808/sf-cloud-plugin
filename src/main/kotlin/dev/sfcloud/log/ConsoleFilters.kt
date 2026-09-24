package dev.sfcloud.log

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.org.OrgService
import dev.sfcloud.tests.ApexTestTarget
import dev.sfcloud.ui.SfUi

class ApexStackTraceFilter(
    private val project: Project,
    private val anonymousNavigator: ((line: Int, column: Int) -> Unit)? = null,
) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val start = entireLength - line.length
        val items = FRAME.findAll(line).mapNotNull { match ->
            val kind = match.groupValues[1]
            val path = match.groupValues[2]
            val lineNumber = match.groupValues[3].toInt()
            val column = match.groupValues[4].toIntOrNull() ?: 1
            val info = if (kind == "AnonymousBlock") {
                anonymousNavigator?.let { navigate -> HyperlinkInfo { navigate(lineNumber, column) } }
            } else {
                classLink(path, lineNumber, column)
            } ?: return@mapNotNull null
            Filter.ResultItem(start + match.range.first, start + match.range.last + 1, info)
        }.toList()
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun classLink(path: String, line: Int, column: Int): HyperlinkInfo? {
        if (DumbService.isDumb(project)) return null
        val segments = path.split('.').filter { it.isNotEmpty() }
        val file = segments.take(2).firstNotNullOfOrNull { ApexTestTarget.findClassFile(project, it) } ?: return null
        return OpenFileHyperlinkInfo(project, file.virtualFile, line - 1, column - 1)
    }

    companion object {
        private val FRAME = Regex("""\b(Class|Trigger|AnonymousBlock)\.?([\w.]*): line (\d+)(?:, column (\d+))?""")
    }
}

class SalesforceIdFilter(private val project: Project, private val org: () -> String?) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (line.length < 15) return null
        val start = entireLength - line.length
        val items = ID.findAll(line)
            .filter { match -> looksLikeId(match.value) }
            .map { match -> Filter.ResultItem(start + match.range.first, start + match.range.last + 1, open(match.value)) }
            .toList()
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun open(id: String) = HyperlinkInfo {
        openInSalesforce(project, org(), id)
    }

    companion object {
        private val ID = Regex("""(?<![\w.])[a-zA-Z0-9]{5}0[a-zA-Z0-9]{9}(?:[a-zA-Z0-9]{3})?(?!\w)""")
        private val EXACT_ID = Regex("""[a-zA-Z0-9]{5}0[a-zA-Z0-9]{9}(?:[a-zA-Z0-9]{3})?""")

        private fun looksLikeId(value: String): Boolean = "000" in value && value.any { it.isLetter() }

        fun isRecordId(value: String?): Boolean = value != null && EXACT_ID.matches(value) && looksLikeId(value)

        fun openInSalesforce(project: Project, org: String?, id: String) {
            val key = OrgService.getInstance(project).orgKey(org)
            SfUi.background(project, "Opening '$id' in '${key ?: "default org"}'", onError = { SfNotifier.error(project, "Cannot open $id", it) }) { indicator ->
                val args = mutableListOf("org", "open", "--path", "/$id")
                if (key != null) args += listOf("--target-org", key)
                val result = SfCli.run(project, args, 120_000, indicator)
                if (!result.success) SfNotifier.error(project, "Cannot open $id", result.message)
            }
        }
    }
}

class ApexConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> =
        arrayOf(ApexStackTraceFilter(project), SalesforceIdFilter(project) { null })
}
