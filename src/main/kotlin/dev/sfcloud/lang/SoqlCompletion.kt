package dev.sfcloud.lang

import com.intellij.codeInsight.completion.AddSpaceInsertHandler
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.PlatformIcons
import dev.sfcloud.settings.SfCloudSettings

sealed interface SoqlCaret {
    val prefix: String

    class Objects(override val prefix: String) : SoqlCaret

    class ChildRelationships(override val prefix: String, val scope: List<String>) : SoqlCaret

    class Fields(
        override val prefix: String,
        val scope: List<String>,
        val path: List<String>,
        val keywords: List<String> = emptyList(),
    ) : SoqlCaret

    class Keywords(override val prefix: String, val keywords: List<String> = emptyList()) : SoqlCaret

    class Bind(override val prefix: String) : SoqlCaret
}

object SoqlContext {
    private val SOQL_SECTIONS = setOf("select", "from", "using", "where", "with", "group", "having", "order", "limit", "offset", "for")
    private val SOSL_SECTIONS = setOf("find", "in", "returning", "with", "limit", "update")
    private val STAGES = listOf(
        "using" to listOf("USING SCOPE"),
        "where" to listOf("WHERE"),
        "with" to listOf("WITH"),
        "group" to listOf("GROUP BY"),
        "having" to listOf("HAVING"),
        "order" to listOf("ORDER BY"),
        "limit" to listOf("LIMIT"),
        "offset" to listOf("OFFSET"),
        "for" to listOf("FOR VIEW", "FOR REFERENCE", "FOR UPDATE"),
        "all" to listOf("ALL ROWS"),
    )
    private val RETURNING_STAGES = setOf("where", "order", "limit", "offset")
    private val SELECT_FUNCTIONS = listOf(
        "COUNT()", "COUNT_DISTINCT()", "SUM()", "AVG()", "MIN()", "MAX()", "FIELDS()", "TYPEOF", "TOLABEL()",
        "FORMAT()", "CONVERTCURRENCY()", "CALENDAR_YEAR()", "CALENDAR_MONTH()", "CALENDAR_QUARTER()",
        "DAY_ONLY()", "DAY_IN_MONTH()", "DAY_IN_WEEK()", "HOUR_IN_DAY()", "WEEK_IN_YEAR()", "FISCAL_YEAR()",
        "GROUPING()",
    )
    private val AGGREGATES = listOf("COUNT()", "COUNT_DISTINCT()", "SUM()", "AVG()", "MIN()", "MAX()")
    private val OPERATORS = listOf("LIKE", "IN", "NOT IN", "INCLUDES", "EXCLUDES")
    private val DATE_LITERALS = listOf(
        "TODAY", "YESTERDAY", "TOMORROW", "LAST_WEEK", "THIS_WEEK", "NEXT_WEEK", "LAST_MONTH", "THIS_MONTH",
        "NEXT_MONTH", "LAST_90_DAYS", "NEXT_90_DAYS", "THIS_QUARTER", "LAST_QUARTER", "NEXT_QUARTER", "THIS_YEAR",
        "LAST_YEAR", "NEXT_YEAR", "THIS_FISCAL_QUARTER", "LAST_FISCAL_QUARTER", "NEXT_FISCAL_QUARTER",
        "THIS_FISCAL_YEAR", "LAST_FISCAL_YEAR", "NEXT_FISCAL_YEAR", "LAST_N_DAYS:", "NEXT_N_DAYS:", "N_DAYS_AGO:",
        "LAST_N_WEEKS:", "NEXT_N_WEEKS:", "N_WEEKS_AGO:", "LAST_N_MONTHS:", "NEXT_N_MONTHS:", "N_MONTHS_AGO:",
        "LAST_N_QUARTERS:", "NEXT_N_QUARTERS:", "N_QUARTERS_AGO:", "LAST_N_YEARS:", "NEXT_N_YEARS:", "N_YEARS_AGO:",
    )
    private val VALUES = listOf("NULL", "TRUE", "FALSE") + DATE_LITERALS
    private val VALUE_WORDS = VALUES.map { it.trimEnd(':').lowercase() }.toSet()
    private val SCOPES = listOf("DELEGATED", "EVERYTHING", "MINE", "MINE_AND_MY_GROUPS", "MY_TERRITORY", "MY_TEAM_TERRITORY", "TEAM")
    private val SOQL_WITH = listOf("SECURITY_ENFORCED", "USER_MODE", "SYSTEM_MODE", "DATA CATEGORY")
    private val SOSL_WITH = listOf(
        "DATA CATEGORY", "DIVISION", "HIGHLIGHT", "METADATA", "NETWORK", "PRICEBOOKID", "SNIPPET",
        "SPELL_CORRECTION", "SECURITY_ENFORCED", "USER_MODE", "SYSTEM_MODE",
    )
    private val SEARCH_GROUPS = listOf("ALL FIELDS", "NAME FIELDS", "EMAIL FIELDS", "PHONE FIELDS", "SIDEBAR FIELDS")
    private val SOSL_TAIL = listOf("RETURNING", "WITH", "LIMIT", "UPDATE TRACKING", "UPDATE VIEWSTAT")
    private val CONDITION_STARTS = setOf("where", "having", "and", "or")
    private val COMPARISONS = setOf("=", "<", ">", "like")
    private val LIST_OPERATORS = setOf("in", "includes", "excludes")
    private val RESERVED = ApexTokens.SOQL_KEYWORDS + VALUE_WORDS

    private class Token(val text: String, val start: Int) {
        val lower: String = text.lowercase()
        val isWord: Boolean get() = text.first().isLetter() || text.first() == '_'
    }

    private class Level(val kind: Kind, val owner: Token?, val sosl: Boolean = false, val nested: Boolean = false, val open: Int = -1) {
        var section: String? = null
        var polymorphic = false
        val seen = ArrayList<Token>()
    }

    private enum class Kind { ROOT, SUBQUERY, RETURNING, GROUPING, VALUES }

    private class Position(val prefix: String, val path: List<String>, val tokens: List<Token>, val stack: List<Level>) {
        val level: Level get() = stack.last()
        val section: String? get() = stack.lastOrNull { it.section != null }?.section
        val last: Token? get() = level.seen.lastOrNull()
        val previous: Token? get() = level.seen.getOrNull(level.seen.size - 2)
    }

    fun at(text: CharSequence, offset: Int): SoqlCaret {
        val safe = offset.coerceIn(0, text.length)
        var start = safe
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        val prefix = text.subSequence(start, safe).toString()
        val path = ArrayList<String>()
        var head = start
        while (head > 1 && text[head - 1] == '.' && isIdentifierPart(text[head - 2])) {
            var segment = head - 1
            while (segment > 0 && isIdentifierPart(text[segment - 1])) segment--
            path.add(0, text.subSequence(segment, head - 1).toString())
            head = segment
        }
        if (head > 0 && text[head - 1] == ':') return SoqlCaret.Bind(prefix)
        val tokens = tokenize(text)
        val sosl = tokens.firstOrNull()?.lower == "find"
        val stack = ArrayList<Level>().apply { add(Level(Kind.ROOT, null, sosl)) }
        tokens.forEachIndexed { index, token ->
            if (token.start >= head) return@forEachIndexed
            val level = stack.last()
            when {
                token.text == "(" -> {
                    val next = tokens.getOrNull(index + 1)?.lower
                    val previous = level.seen.lastOrNull()
                    val kind = when {
                        next == "select" -> Kind.SUBQUERY
                        level.sosl && level.section == "returning" && previous?.isWord == true -> Kind.RETURNING
                        previous?.lower in LIST_OPERATORS -> Kind.VALUES
                        else -> Kind.GROUPING
                    }
                    level.seen += token
                    val nested = kind == Kind.SUBQUERY && level.section == "select"
                    stack += Level(kind, previous, nested = nested, open = index)
                }
                token.text == ")" -> {
                    if (stack.size > 1) stack.removeAt(stack.lastIndex)
                    stack.last().seen += token
                }
                else -> {
                    val sections = if (level.sosl) SOSL_SECTIONS else SOQL_SECTIONS
                    if (token.isWord && token.lower in sections && !(token.lower == "update" && level.section == "for")) {
                        level.section = token.lower
                    }
                    if (token.lower == "typeof") level.polymorphic = true
                    if (token.lower == "end") level.polymorphic = false
                    level.seen += token
                }
            }
        }
        val position = Position(prefix, path, tokens, stack)
        if (path.isNotEmpty()) return pathCaret(position)
        return when (position.level.kind) {
            Kind.VALUES -> SoqlCaret.Keywords(prefix, if (position.last == null && position.level.owner?.lower == "in") listOf("SELECT") else emptyList())
            Kind.RETURNING -> returning(position)
            else -> if (position.level.sosl) sosl(position) else soql(position)
        }
    }

    private fun pathCaret(position: Position): SoqlCaret = when (position.section) {
        "from", "using", "limit", "offset", "for", "with", "returning", "find", "in", "update" -> SoqlCaret.Keywords(position.prefix)
        else -> fields(position)
    }

    private fun sosl(position: Position): SoqlCaret {
        val last = position.last?.lower
        val keywords = when (position.section) {
            "find" -> if (last == "find") emptyList() else SEARCH_GROUPS.map { "IN $it" } + SOSL_TAIL
            "in" -> when (last) {
                "in" -> SEARCH_GROUPS
                "fields" -> SOSL_TAIL
                else -> listOf("FIELDS")
            }
            "returning" -> return if (last == "returning" || last == ",") {
                SoqlCaret.Objects(position.prefix)
            } else {
                SoqlCaret.Keywords(position.prefix, SOSL_TAIL.drop(1))
            }
            "with" -> if (last == "with") SOSL_WITH else SOSL_TAIL.drop(1)
            "limit" -> if (last == "limit") emptyList() else listOf("UPDATE TRACKING", "UPDATE VIEWSTAT")
            "update" -> if (last == "update") listOf("TRACKING", "VIEWSTAT") else emptyList()
            else -> listOf("FIND")
        }
        return SoqlCaret.Keywords(position.prefix, keywords)
    }

    private fun returning(position: Position): SoqlCaret {
        val last = position.last
        if (position.level.section != null) return soql(position, RETURNING_STAGES)
        return if (last == null || last.text == ",") {
            fields(position)
        } else {
            SoqlCaret.Keywords(position.prefix, tail(null, RETURNING_STAGES))
        }
    }

    private fun soql(position: Position, allowed: Set<String>? = null): SoqlCaret {
        val prefix = position.prefix
        val last = position.last
        val lower = last?.lower
        val grouping = position.level.kind == Kind.GROUPING
        val section = position.section
        if (grouping && position.last == null && position.level.owner?.lower == "fields") {
            return SoqlCaret.Keywords(prefix, listOf("ALL", "STANDARD", "CUSTOM"))
        }
        if (lower == "group" || lower == "order") return SoqlCaret.Keywords(prefix, listOf("BY"))
        if (lower == "all" && section != "select") return SoqlCaret.Keywords(prefix, listOf("ROWS"))
        return when (section) {
            null -> SoqlCaret.Keywords(prefix, if (last == null && position.level.kind == Kind.ROOT) listOf("SELECT", "FIND") else emptyList())
            "select" -> select(position)
            "from" -> when {
                lower == "from" -> if (position.chain().last().nested) {
                    SoqlCaret.ChildRelationships(prefix, scope(position.tokens, position.chain().dropLast(1)))
                } else {
                    SoqlCaret.Objects(prefix)
                }
                else -> SoqlCaret.Keywords(prefix, tail("from", allowed))
            }
            "using" -> SoqlCaret.Keywords(
                prefix,
                when (lower) {
                    "using" -> listOf("SCOPE")
                    "scope" -> SCOPES
                    else -> tail("using", allowed)
                },
            )
            "where", "having" -> condition(position, section, allowed)
            "group" -> when {
                lower == "by" || lower == "," -> fields(position, listOf("ROLLUP()", "CUBE()"))
                grouping -> fields(position)
                else -> SoqlCaret.Keywords(prefix, tail("group", allowed, having = true))
            }
            "order" -> when (lower) {
                "by", "," -> fields(position, AGGREGATES)
                "nulls" -> SoqlCaret.Keywords(prefix, listOf("FIRST", "LAST"))
                "asc", "desc" -> SoqlCaret.Keywords(prefix, listOf("NULLS FIRST", "NULLS LAST") + tail("order", allowed))
                "first", "last" -> SoqlCaret.Keywords(prefix, tail("order", allowed))
                else -> if (grouping) fields(position) else SoqlCaret.Keywords(prefix, listOf("ASC", "DESC", "NULLS FIRST", "NULLS LAST") + tail("order", allowed))
            }
            "with" -> SoqlCaret.Keywords(prefix, if (lower == "with") SOQL_WITH else tail("with", allowed))
            "limit", "offset" -> SoqlCaret.Keywords(prefix, if (lower == section) emptyList() else tail(section, allowed))
            "for" -> SoqlCaret.Keywords(prefix, if (lower == "for") listOf("VIEW", "REFERENCE", "UPDATE") else emptyList())
            else -> SoqlCaret.Keywords(prefix)
        }
    }

    private fun select(position: Position): SoqlCaret {
        val prefix = position.prefix
        val last = position.last
        val lower = last?.lower
        val previous = position.previous?.lower
        val polymorphic = position.level.polymorphic
        return when {
            last == null || lower == "select" || lower == "," && !polymorphic -> fields(position, SELECT_FUNCTIONS)
            lower == "typeof" || lower == "then" || lower == "else" || lower == "," -> fields(position)
            lower == "when" -> SoqlCaret.Objects(prefix)
            previous == "typeof" -> SoqlCaret.Keywords(prefix, listOf("WHEN"))
            previous == "when" -> SoqlCaret.Keywords(prefix, listOf("THEN"))
            polymorphic -> SoqlCaret.Keywords(prefix, listOf("WHEN", "ELSE", "END"))
            position.level.kind == Kind.GROUPING -> fields(position)
            else -> SoqlCaret.Keywords(prefix, listOf("FROM"))
        }
    }

    private fun condition(position: Position, section: String, allowed: Set<String>?): SoqlCaret {
        val prefix = position.prefix
        val last = position.last
        val lower = last?.lower
        val starts = if (section == "having") AGGREGATES + "NOT" else listOf("NOT")
        return when {
            last == null || lower in CONDITION_STARTS -> fields(position, starts)
            lower == "not" -> if (isField(position.previous, position)) SoqlCaret.Keywords(prefix, listOf("IN")) else fields(position, starts)
            lower in COMPARISONS -> SoqlCaret.Keywords(prefix, VALUES)
            lower in LIST_OPERATORS -> SoqlCaret.Keywords(prefix)
            last.text == "!" -> SoqlCaret.Keywords(prefix)
            isField(last, position) -> SoqlCaret.Keywords(prefix, OPERATORS)
            position.level.kind == Kind.GROUPING -> SoqlCaret.Keywords(prefix, listOf("AND", "OR"))
            else -> SoqlCaret.Keywords(prefix, listOf("AND", "OR") + tail(section, allowed))
        }
    }

    private fun isField(token: Token?, position: Position): Boolean {
        if (token == null || !token.isWord || token.lower in RESERVED) return false
        val seen = position.level.seen
        val index = seen.indexOf(token)
        return seen.getOrNull(index - 1)?.text != ":"
    }

    private fun tail(section: String?, allowed: Set<String>?, having: Boolean = false): List<String> {
        val from = STAGES.indexOfFirst { it.first == section }
        return STAGES.drop(from + 1)
            .filter { (stage, _) -> stage != "having" || having }
            .filter { (stage, _) -> allowed == null || stage in allowed }
            .flatMap { it.second }
    }

    private fun Position.chain(): List<Level> {
        val queries = stack.filter { it.kind == Kind.ROOT || it.kind == Kind.SUBQUERY }
        return queries.drop(queries.indexOfLast { !it.nested }.coerceAtLeast(0))
    }

    private fun fields(position: Position, keywords: List<String> = emptyList()): SoqlCaret {
        val level = position.stack.lastOrNull { it.kind != Kind.GROUPING }
        val scope = if (level?.kind == Kind.RETURNING) listOfNotNull(level.owner?.text) else scope(position.tokens, position.chain())
        val offered = if (position.path.isEmpty()) keywords else emptyList()
        return if (scope.isEmpty()) SoqlCaret.Keywords(position.prefix, offered) else SoqlCaret.Fields(position.prefix, scope, position.path, offered)
    }

    private fun scope(tokens: List<Token>, levels: List<Level>): List<String> {
        val names = levels.map { fromOf(tokens, it.open + 1) }
        return if (names.any { it == null }) emptyList() else names.filterNotNull()
    }

    private fun fromOf(tokens: List<Token>, from: Int): String? {
        var depth = 0
        var i = from.coerceAtLeast(0)
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token.text == "(" -> depth++
                token.text == ")" -> if (depth == 0) return null else depth--
                depth == 0 && token.lower == "from" -> return tokens.getOrNull(i + 1)?.takeIf { it.isWord }?.text
            }
            i++
        }
        return null
    }

    private fun tokenize(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '\'' || c == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != c) {
                        if (text[j] == '\\') j++
                        j++
                    }
                    tokens += Token("$c$c", i)
                    i = j + 1
                }
                c == '{' -> {
                    val end = text.indexOf('}', i)
                    tokens += Token("{}", i)
                    i = if (end < 0) text.length else end + 1
                }
                isIdentifierPart(c) -> {
                    var j = i
                    while (j < text.length && isIdentifierPart(text[j])) j++
                    tokens += Token(text.subSequence(i, j).toString(), i)
                    i = j
                }
                else -> {
                    tokens += Token(c.toString(), i)
                    i++
                }
            }
        }
        return tokens
    }

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}

object SoqlCompletion {
    fun variants(project: Project, caret: SoqlCaret): List<LookupElement> {
        return when (caret) {
            is SoqlCaret.Objects -> objects(project)
            is SoqlCaret.ChildRelationships -> children(project, caret.scope)
            is SoqlCaret.Fields -> fields(project, caret.scope, caret.path) + keywords(caret.keywords)
            is SoqlCaret.Keywords -> keywords(caret.keywords)
            is SoqlCaret.Bind -> emptyList()
        }
    }

    fun queryObject(project: Project, scope: List<String>): SObjectSchema? {
        var current = SalesforceSchema.sObject(project, scope.firstOrNull() ?: return null) ?: return null
        for (relationship in scope.drop(1)) {
            val child = current.child(relationship) ?: return null
            current = SalesforceSchema.sObject(project, child.target) ?: return null
        }
        return current
    }

    private fun objects(project: Project): List<LookupElement> =
        SalesforceSchema.objectNames(project).map { LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.DataTables).withTypeText("SObject") }

    private fun children(project: Project, scope: List<String>): List<LookupElement> {
        val parent = queryObject(project, scope) ?: return emptyList()
        return parent.children.map {
            LookupElementBuilder.create(it.name).withIcon(AllIcons.Nodes.DataTables).withTypeText("List<${it.target}>")
        }
    }

    private fun fields(project: Project, scope: List<String>, path: List<String>): List<LookupElement> {
        val root = queryObject(project, scope) ?: return emptyList()
        val target = SalesforceSchema.traverse(project, root.name, path) ?: return emptyList()
        val fields = target.fields.map {
            LookupElementBuilder.create(it.name).withIcon(PlatformIcons.FIELD_ICON).withTypeText(it.type)
        }
        val parents = target.parents.map {
            LookupElementBuilder.create(it.name).withIcon(PlatformIcons.PROPERTY_ICON).withTypeText(it.target)
        }
        return fields + parents
    }

    private fun keywords(keywords: List<String>): List<LookupElement> = keywords.map { keyword ->
        when {
            keyword.endsWith("()") -> {
                val name = keyword.removeSuffix("()")
                LookupElementBuilder.create(name).withLookupString(name.lowercase()).withTailText("()", true).bold()
                    .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
            }
            keyword.endsWith(":") -> LookupElementBuilder.create(keyword).withLookupString(keyword.lowercase()).bold()
            else -> LookupElementBuilder.create(keyword).withLookupString(keyword.lowercase()).bold()
                .withInsertHandler(AddSpaceInsertHandler(true))
        }
    }
}

class SoqlCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!SfCloudSettings.getInstance().state.apexCompletion) return
        val language = parameters.originalFile.language
        if (language != SoqlLanguage && language != SoslLanguage) return
        val text = parameters.editor.document.charsSequence
        val (start, end) = SoqlStatements.around(text, parameters.offset)
        val caret = SoqlContext.at(text.subSequence(start, end), parameters.offset - start)
        val variants = SoqlCompletion.variants(parameters.originalFile.project, caret)
        if (variants.isNotEmpty()) result.withPrefixMatcher(caret.prefix).addAllElements(variants)
    }
}

object SoqlStatements {
    fun around(text: CharSequence, offset: Int): Pair<Int, Int> {
        val safe = offset.coerceIn(0, text.length)
        var start = safe
        while (start > 0 && text[start - 1] != ';') start--
        var end = safe
        while (end < text.length && text[end] != ';') end++
        return start to end
    }

    fun inlineAt(text: CharSequence, offset: Int): IntRange? {
        val safe = offset.coerceIn(0, text.length)
        var depth = 0
        var i = safe - 1
        var open = -1
        while (i >= 0) {
            when (text[i]) {
                ']' -> depth++
                '[' -> if (depth == 0) {
                    open = i
                    break
                } else {
                    depth--
                }
                ';', '{', '}' -> return null
            }
            i--
        }
        if (open < 0 || !startsQuery(text, open + 1)) return null
        depth = 0
        var close = safe
        while (close < text.length) {
            when (text[close]) {
                '[' -> depth++
                ']' -> if (depth == 0) return (open + 1) until close else depth--
                ';', '{', '}' -> return (open + 1) until close
            }
            close++
        }
        return (open + 1) until close
    }

    fun startsQuery(text: CharSequence, from: Int): Boolean {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        val start = i
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        val word = text.subSequence(start, i).toString().lowercase()
        return word == "select" || word == "find"
    }
}
