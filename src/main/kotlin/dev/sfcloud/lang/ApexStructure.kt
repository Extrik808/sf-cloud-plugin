package dev.sfcloud.lang

import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

enum class ApexMemberKind(val isType: Boolean = false, val isCallable: Boolean = false) {
    CLASS(isType = true),
    INTERFACE(isType = true),
    ENUM(isType = true),
    TRIGGER(isType = true),
    CONSTRUCTOR(isCallable = true),
    METHOD(isCallable = true),
    PROPERTY,
    FIELD,
    ENUM_CONSTANT,
}

class ApexMember(
    val kind: ApexMemberKind,
    val name: String,
    val startOffset: Int,
    val nameOffset: Int,
    val modifiers: Set<String>,
    val annotations: List<String>,
    val type: String,
    val parameters: String?,
) {
    var endOffset: Int = nameOffset + name.length
    val children = mutableListOf<ApexMember>()

    val visibility: String?
        get() = VISIBILITIES.firstOrNull { it in modifiers }

    val isStatic: Boolean get() = "static" in modifiers

    val isTest: Boolean
        get() = annotations.any { it.equals("@istest", true) } || "testmethod" in modifiers

    val presentableText: String
        get() = when {
            kind.isCallable && kind == ApexMemberKind.METHOD && type.isNotEmpty() -> "$name(${parameters.orEmpty()}): $type"
            kind.isCallable -> "$name(${parameters.orEmpty()})"
            kind == ApexMemberKind.PROPERTY || kind == ApexMemberKind.FIELD -> if (type.isEmpty()) name else "$name: $type"
            else -> name
        }

    fun contains(offset: Int): Boolean = offset in startOffset..endOffset

    fun flatten(): Sequence<ApexMember> = sequenceOf(this) + children.asSequence().flatMap { it.flatten() }

    override fun toString(): String = "$kind $presentableText"

    companion object {
        val VISIBILITIES = listOf("global", "public", "protected", "private")
    }
}

object ApexStructure {
    fun of(file: PsiFile): List<ApexMember> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(parse(file.viewProvider.contents), file)
    }

    fun memberAt(members: List<ApexMember>, offset: Int): ApexMember? {
        var found: ApexMember? = null
        var level = members
        while (true) {
            val next = level.firstOrNull { it.contains(offset) } ?: return found
            found = next
            level = next.children
        }
    }

    fun parse(text: CharSequence): List<ApexMember> = Scanner(text, tokenize(text)).run()

    private fun tokenize(text: CharSequence): List<Token> {
        val lexer = ApexLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<Token>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in ApexTokens.COMMENTS) {
                tokens += Token(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return tokens
    }

    private class Token(val type: IElementType, val text: String, val start: Int, val end: Int) {
        val lower: String = text.lowercase()

        fun isWord(): Boolean = type == ApexTokens.IDENTIFIER || (type == ApexTokens.KEYWORD && lower in SOFT_NAMES)

        fun isKeyword(word: String): Boolean = type == ApexTokens.KEYWORD && lower == word
    }

    private class Step(val next: Int, val declared: Boolean)

    private class Scanner(private val text: CharSequence, private val tokens: List<Token>) {
        private val roots = mutableListOf<ApexMember>()

        fun run(): List<ApexMember> {
            body(0, null)
            return roots
        }

        private fun body(from: Int, container: ApexMember?): Int {
            var i = from
            var header = i
            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token.type == ApexTokens.RBRACE -> {
                        if (container != null) {
                            container.endOffset = token.end
                            return i + 1
                        }
                        i++
                        header = i
                    }
                    token.type == ApexTokens.ANNOTATION -> {
                        i = if (next(i)?.type == ApexTokens.LPAREN) close(i + 1, ApexTokens.LPAREN, ApexTokens.RPAREN) + 1 else i + 1
                    }
                    isTypeKeyword(token, container) && next(i)?.isWord() == true && previous(i)?.type != ApexTokens.DOT -> {
                        i = typeDeclaration(header, i, container)
                        header = i
                    }
                    token.type == ApexTokens.LPAREN -> {
                        val step = parenthesis(header, i, container)
                        i = step.next
                        if (step.declared) header = i
                    }
                    token.type == ApexTokens.LBRACE -> {
                        val end = close(i, ApexTokens.LBRACE, ApexTokens.RBRACE)
                        declaration(header, i)?.takeIf { container?.kind == ApexMemberKind.CLASS || container?.kind == ApexMemberKind.INTERFACE }?.let { decl ->
                            add(container, member(ApexMemberKind.PROPERTY, header, decl, null).also { it.endOffset = tokens[end].end })
                        }
                        i = end + 1
                        header = i
                    }
                    token.type == ApexTokens.SEMICOLON -> {
                        if (container?.kind == ApexMemberKind.CLASS) field(header, i, container)
                        i++
                        header = i
                    }
                    else -> i++
                }
            }
            container?.endOffset = text.length
            return tokens.size
        }

        private fun typeDeclaration(header: Int, keyword: Int, container: ApexMember?): Int {
            val token = tokens[keyword]
            val kind = when (token.lower) {
                "interface" -> ApexMemberKind.INTERFACE
                "enum" -> ApexMemberKind.ENUM
                "trigger" -> ApexMemberKind.TRIGGER
                else -> ApexMemberKind.CLASS
            }
            val nameIndex = keyword + 1
            val (modifiers, annotations) = prefix(header, keyword)
            val type = if (kind == ApexMemberKind.TRIGGER) triggerTarget(nameIndex) else ""
            val member = ApexMember(kind, tokens[nameIndex].text, tokens[header].start, tokens[nameIndex].start, modifiers, annotations, type, null)
            add(container, member)
            var open = nameIndex + 1
            while (open < tokens.size && tokens[open].type != ApexTokens.LBRACE) {
                if (tokens[open].type == ApexTokens.LPAREN) open = close(open, ApexTokens.LPAREN, ApexTokens.RPAREN)
                if (tokens[open].type == ApexTokens.SEMICOLON) {
                    member.endOffset = tokens[open].end
                    return open + 1
                }
                open++
            }
            if (open >= tokens.size) return tokens.size
            if (kind == ApexMemberKind.ENUM) {
                val end = close(open, ApexTokens.LBRACE, ApexTokens.RBRACE)
                for (j in open + 1 until end) {
                    val before = tokens[j - 1].type
                    if (tokens[j].isWord() && (before == ApexTokens.LBRACE || before == ApexTokens.COMMA)) {
                        member.children += ApexMember(ApexMemberKind.ENUM_CONSTANT, tokens[j].text, tokens[j].start, tokens[j].start, emptySet(), emptyList(), "", null)
                    }
                }
                member.endOffset = tokens[end].end
                return end + 1
            }
            return body(open + 1, member)
        }

        private fun triggerTarget(nameIndex: Int): String {
            val on = tokens.getOrNull(nameIndex + 1)
            val target = tokens.getOrNull(nameIndex + 2)
            return if (on?.isKeyword("on") == true && target != null) target.text else ""
        }

        private fun parenthesis(header: Int, open: Int, container: ApexMember?): Step {
            val close = close(open, ApexTokens.LPAREN, ApexTokens.RPAREN)
            val after = tokens.getOrNull(close + 1)
            val nameIndex = open - 1
            val decl = declaration(header, open)
            val (modifiers, _) = prefix(header, nameIndex.coerceAtLeast(header))
            val hasBody = after?.type == ApexTokens.LBRACE
            val isAbstract = after?.type == ApexTokens.SEMICOLON &&
                (container?.kind == ApexMemberKind.INTERFACE || "abstract" in modifiers)
            val isConstructor = decl == null && nameIndex >= header && tokens[nameIndex].isWord() &&
                container?.kind == ApexMemberKind.CLASS && tokens[nameIndex].lower == container.name.lowercase() &&
                typeTokens(header, nameIndex).isEmpty() && previous(nameIndex)?.type != ApexTokens.DOT
            if ((decl == null && !isConstructor) || (!hasBody && !isAbstract)) return Step(close + 1, false)
            val kind = if (isConstructor) ApexMemberKind.CONSTRUCTOR else ApexMemberKind.METHOD
            val parameters = text.subSequence(tokens[open].end, tokens[close].start).toString().replace(WHITESPACE, " ").trim()
            val member = member(kind, header, nameIndex, parameters)
            add(container, member)
            return if (hasBody) {
                val end = close(close + 1, ApexTokens.LBRACE, ApexTokens.RBRACE)
                member.endOffset = tokens[end].end
                Step(end + 1, true)
            } else {
                member.endOffset = after!!.end
                Step(close + 2, true)
            }
        }

        private fun field(header: Int, semicolon: Int, container: ApexMember) {
            val assignment = (header until semicolon).firstOrNull { tokens[it].type == ApexTokens.OPERATOR && tokens[it].text == "=" } ?: semicolon
            val nameIndex = assignment - 1
            if (nameIndex <= header || !tokens[nameIndex].isWord()) return
            if (declaration(header, assignment) == null) return
            add(container, member(ApexMemberKind.FIELD, header, nameIndex, null).also { it.endOffset = tokens[semicolon].end })
        }

        private fun declaration(header: Int, end: Int): Int? {
            val nameIndex = end - 1
            if (nameIndex <= header || !tokens[nameIndex].isWord()) return null
            if (previous(nameIndex)?.type == ApexTokens.DOT) return null
            var depth = 0
            for (j in header until nameIndex) {
                val token = tokens[j]
                when {
                    token.type == ApexTokens.ANNOTATION -> Unit
                    token.type == ApexTokens.LPAREN -> depth++
                    token.type == ApexTokens.RPAREN -> depth--
                    depth > 0 -> Unit
                    token.type == ApexTokens.KEYWORD && token.lower in STATEMENT_KEYWORDS -> return null
                    token.type == ApexTokens.OPERATOR && token.text !in GENERIC_OPERATORS -> return null
                    token.type == ApexTokens.STRING || token.type == ApexTokens.NUMBER -> return null
                    token.type == ApexTokens.SEMICOLON || token.type == ApexTokens.LBRACE || token.type == ApexTokens.RBRACE -> return null
                }
            }
            val type = typeTokens(header, nameIndex)
            if (type.isEmpty()) return null
            if (type.any { it.type == ApexTokens.COMMA } && type.none { it.type == ApexTokens.OPERATOR }) return null
            return nameIndex
        }

        private fun member(kind: ApexMemberKind, header: Int, nameIndex: Int, parameters: String?): ApexMember {
            val (modifiers, annotations) = prefix(header, nameIndex)
            val type = typeTokens(header, nameIndex).joinToString("") { if (it.type == ApexTokens.COMMA) ", " else it.text }
            return ApexMember(kind, tokens[nameIndex].text, tokens[header].start, tokens[nameIndex].start, modifiers, annotations, type, parameters)
        }

        private fun prefix(header: Int, end: Int): Pair<Set<String>, List<String>> {
            val modifiers = mutableSetOf<String>()
            val annotations = mutableListOf<String>()
            var j = header
            while (j < end) {
                val token = tokens[j]
                when {
                    token.type == ApexTokens.ANNOTATION -> {
                        annotations += token.text
                        if (next(j)?.type == ApexTokens.LPAREN) j = close(j + 1, ApexTokens.LPAREN, ApexTokens.RPAREN)
                    }
                    token.lower in MODIFIERS -> modifiers += token.lower
                }
                j++
            }
            return modifiers to annotations
        }

        private fun typeTokens(header: Int, end: Int): List<Token> {
            val result = mutableListOf<Token>()
            var j = header
            while (j < end) {
                val token = tokens[j]
                when {
                    token.type == ApexTokens.ANNOTATION -> {
                        if (next(j)?.type == ApexTokens.LPAREN) j = close(j + 1, ApexTokens.LPAREN, ApexTokens.RPAREN)
                    }
                    token.lower in MODIFIERS -> Unit
                    else -> result += token
                }
                j++
            }
            return result
        }

        private fun isTypeKeyword(token: Token, container: ApexMember?): Boolean = when {
            token.isKeyword("class") || token.isKeyword("interface") || token.isKeyword("enum") -> true
            token.isKeyword("trigger") -> container == null
            else -> false
        }

        private fun add(container: ApexMember?, member: ApexMember) {
            if (container == null) roots += member else container.children += member
        }

        private fun next(i: Int): Token? = tokens.getOrNull(i + 1)

        private fun previous(i: Int): Token? = tokens.getOrNull(i - 1)

        private fun close(open: Int, opening: IElementType, closing: IElementType): Int {
            var depth = 0
            for (j in open until tokens.size) {
                when (tokens[j].type) {
                    opening -> depth++
                    closing -> if (--depth == 0) return j
                }
            }
            return tokens.size - 1
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val SOFT_NAMES = setOf(
        "get", "set", "after", "before", "on", "sharing", "inherited", "merge", "first", "last",
        "insert", "update", "upsert", "delete", "undelete", "new",
        "boolean", "integer", "long", "double", "decimal", "string", "id", "blob", "date", "datetime", "time",
        "object", "sobject", "list", "map", "trigger",
    )
    private val MODIFIERS = setOf(
        "global", "public", "protected", "private", "static", "final", "abstract", "virtual", "override",
        "transient", "webservice", "testmethod", "with", "without", "inherited", "sharing",
    )
    private val STATEMENT_KEYWORDS = setOf(
        "return", "new", "if", "else", "for", "while", "do", "throw", "insert", "update", "delete", "upsert",
        "merge", "undelete", "switch", "when", "try", "catch", "finally", "break", "continue", "this", "super",
        "true", "false", "null", "class", "interface", "enum", "trigger",
    )
    private val GENERIC_OPERATORS = setOf("<", ">", ">>", ">>>")
}
