package dev.sfcloud.log

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

object ApexLogLanguage : Language("ApexLog") {
    private fun readResolve(): Any = ApexLogLanguage

    override fun getDisplayName(): String = "Apex Debug Log"
}

object ApexLogFileType : LanguageFileType(ApexLogLanguage) {
    val ICON: Icon = IconLoader.getIcon("/icons/logs.svg", ApexLogFileType::class.java)

    override fun getName(): String = "Apex Debug Log"

    override fun getDescription(): String = "Salesforce Apex debug log"

    override fun getDefaultExtension(): String = "log"

    override fun getIcon(): Icon = ICON
}

class ApexLogFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        if (file.isDirectory || !file.name.endsWith(".log", ignoreCase = true)) return null
        val path = file.path
        return if (LOG_FOLDERS.any { path.contains(it) }) ApexLogFileType else null
    }

    companion object {
        private val LOG_FOLDERS = listOf("/.sfdx/tools/debug/logs/", "/.sf/tools/debug/logs/", "/.sfdx/tools/logs/")
    }
}

class ApexLogFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ApexLogLanguage) {
    override fun getFileType(): FileType = ApexLogFileType

    override fun toString(): String = "Apex Debug Log"
}

class ApexLogTokenType(debugName: String) : IElementType(debugName, ApexLogLanguage)

object ApexLogTokens {
    val FILE = IFileElementType(ApexLogLanguage)
    val HEADER = ApexLogTokenType("HEADER")
    val TIMESTAMP = ApexLogTokenType("TIMESTAMP")
    val SEPARATOR = ApexLogTokenType("SEPARATOR")
    val LINE_REF = ApexLogTokenType("LINE_REF")
    val TEXT = ApexLogTokenType("TEXT")
    val DEBUG_MESSAGE = ApexLogTokenType("DEBUG_MESSAGE")
    val EVENT_UNIT = ApexLogTokenType("EVENT_UNIT")
    val EVENT_METHOD = ApexLogTokenType("EVENT_METHOD")
    val EVENT_QUERY = ApexLogTokenType("EVENT_QUERY")
    val EVENT_DEBUG = ApexLogTokenType("EVENT_DEBUG")
    val EVENT_ERROR = ApexLogTokenType("EVENT_ERROR")
    val EVENT_LIMITS = ApexLogTokenType("EVENT_LIMITS")
    val EVENT_OTHER = ApexLogTokenType("EVENT_OTHER")

    fun eventToken(type: String): IElementType = when (ApexLogParser.categoryOf(type)) {
        LogCategory.EXECUTION, LogCategory.CODE_UNIT -> EVENT_UNIT
        LogCategory.METHOD -> EVENT_METHOD
        LogCategory.SOQL, LogCategory.DML, LogCategory.CALLOUT -> EVENT_QUERY
        LogCategory.DEBUG -> EVENT_DEBUG
        LogCategory.ERROR -> EVENT_ERROR
        LogCategory.LIMITS -> EVENT_LIMITS
        else -> EVENT_OTHER
    }
}

class ApexLogLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenState = LINE_START
    private var nextState = LINE_START
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenEnd = startOffset
        nextState = initialState
        advance()
    }

    override fun getState(): Int = tokenState

    override fun getTokenType(): IElementType? = type

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        tokenState = nextState
        if (tokenStart >= bufferEnd) {
            type = null
            return
        }
        val c = buffer[tokenStart]
        if (c == '\n' || c == '\r') {
            var end = tokenStart
            while (end < bufferEnd && (buffer[end] == '\n' || buffer[end] == '\r')) end++
            finish(TokenType.WHITE_SPACE, end, LINE_START)
            return
        }
        when {
            tokenState == LINE_START -> lineStart()
            tokenState == AFTER_TIMESTAMP -> {
                if (c == '|') finish(ApexLogTokens.SEPARATOR, tokenStart + 1, EVENT) else restOfLine(ApexLogTokens.TEXT)
            }
            tokenState == EVENT -> {
                var end = tokenStart
                while (end < bufferEnd && (buffer[end].isUpperCase() || buffer[end].isDigit() || buffer[end] == '_')) end++
                if (end == tokenStart) {
                    restOfLine(ApexLogTokens.TEXT)
                } else {
                    val eventType = buffer.subSequence(tokenStart, end).toString()
                    val debug = if (eventType == "USER_DEBUG") DEBUG_FLAG else 0
                    finish(ApexLogTokens.eventToken(eventType), end, FIELD_BASE + debug)
                }
            }
            tokenState >= FIELD_BASE -> field(c)
            else -> restOfLine(ApexLogTokens.TEXT)
        }
    }

    private fun field(c: Char) {
        val debug = tokenState and DEBUG_FLAG
        val index = (tokenState - FIELD_BASE) and FIELD_MASK
        if (c == '|') {
            finish(ApexLogTokens.SEPARATOR, tokenStart + 1, FIELD_BASE + debug + minOf(index + 1, FIELD_MASK))
            return
        }
        if (debug != 0 && index >= DEBUG_MESSAGE_FIELD) {
            restOfLine(ApexLogTokens.DEBUG_MESSAGE)
            return
        }
        var end = tokenStart
        while (end < bufferEnd && buffer[end] != '|' && buffer[end] != '\n' && buffer[end] != '\r') end++
        val text = buffer.subSequence(tokenStart, end).toString()
        val token = if (ApexLogParser.isLineRef(text)) ApexLogTokens.LINE_REF else ApexLogTokens.TEXT
        finish(token, end, tokenState)
    }

    private fun lineStart() {
        val lineEnd = lineEnd(tokenStart)
        val timestamp = TIMESTAMP.toPattern().matcher(buffer).region(tokenStart, lineEnd)
        if (timestamp.lookingAt()) {
            finish(ApexLogTokens.TIMESTAMP, timestamp.end(), AFTER_TIMESTAMP)
            return
        }
        val header = tokenStart == 0 && HEADER.toPattern().matcher(buffer).region(tokenStart, lineEnd).matches()
        restOfLine(if (header) ApexLogTokens.HEADER else ApexLogTokens.TEXT)
    }

    private fun restOfLine(token: IElementType) = finish(token, lineEnd(tokenStart), REST_OF_LINE)

    private fun lineEnd(from: Int): Int {
        var end = from
        while (end < bufferEnd && buffer[end] != '\n' && buffer[end] != '\r') end++
        return end
    }

    private fun finish(token: IElementType, end: Int, state: Int) {
        type = token
        tokenEnd = end
        nextState = state
    }

    companion object {
        const val LINE_START = 0
        private const val AFTER_TIMESTAMP = 1
        private const val EVENT = 2
        private const val REST_OF_LINE = 3
        private const val FIELD_BASE = 16
        private const val FIELD_MASK = 0xFF
        private const val DEBUG_FLAG = 0x100
        private const val DEBUG_MESSAGE_FIELD = 3
        private val TIMESTAMP = Regex("""\d{1,2}:\d{2}:\d{2}\.\d+ \(\d+\)""")
        private val HEADER = Regex("""\d+\.\d+ \S+""")
    }
}

class ApexLogParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = ApexLogLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = ApexLogTokens.FILE

    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY

    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = ApexLogFile(viewProvider)
}

object ApexLogColors {
    val HEADER = key("APEX_LOG_HEADER", DefaultLanguageHighlighterColors.METADATA)
    val TIMESTAMP = key("APEX_LOG_TIMESTAMP", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val SEPARATOR = key("APEX_LOG_SEPARATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val LINE_REF = key("APEX_LOG_LINE_REF", DefaultLanguageHighlighterColors.NUMBER)
    val DEBUG_MESSAGE = key("APEX_LOG_DEBUG_MESSAGE", DefaultLanguageHighlighterColors.STRING)
    val EVENT_UNIT = key("APEX_LOG_EVENT_UNIT", DefaultLanguageHighlighterColors.KEYWORD)
    val EVENT_METHOD = key("APEX_LOG_EVENT_METHOD", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val EVENT_QUERY = key("APEX_LOG_EVENT_QUERY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val EVENT_DEBUG = key("APEX_LOG_EVENT_DEBUG", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val EVENT_ERROR = key("APEX_LOG_EVENT_ERROR", CodeInsightColors.ERRORS_ATTRIBUTES)
    val EVENT_LIMITS = key("APEX_LOG_EVENT_LIMITS", DefaultLanguageHighlighterColors.CONSTANT)
    val EVENT_OTHER = key("APEX_LOG_EVENT_OTHER", DefaultLanguageHighlighterColors.IDENTIFIER)

    private fun key(name: String, fallback: TextAttributesKey) = TextAttributesKey.createTextAttributesKey(name, fallback)
}

class ApexLogSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ApexLogLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(ATTRIBUTES[tokenType])

    companion object {
        private val ATTRIBUTES: Map<IElementType, TextAttributesKey> = mapOf(
            ApexLogTokens.HEADER to ApexLogColors.HEADER,
            ApexLogTokens.TIMESTAMP to ApexLogColors.TIMESTAMP,
            ApexLogTokens.SEPARATOR to ApexLogColors.SEPARATOR,
            ApexLogTokens.LINE_REF to ApexLogColors.LINE_REF,
            ApexLogTokens.DEBUG_MESSAGE to ApexLogColors.DEBUG_MESSAGE,
            ApexLogTokens.EVENT_UNIT to ApexLogColors.EVENT_UNIT,
            ApexLogTokens.EVENT_METHOD to ApexLogColors.EVENT_METHOD,
            ApexLogTokens.EVENT_QUERY to ApexLogColors.EVENT_QUERY,
            ApexLogTokens.EVENT_DEBUG to ApexLogColors.EVENT_DEBUG,
            ApexLogTokens.EVENT_ERROR to ApexLogColors.EVENT_ERROR,
            ApexLogTokens.EVENT_LIMITS to ApexLogColors.EVENT_LIMITS,
            ApexLogTokens.EVENT_OTHER to ApexLogColors.EVENT_OTHER,
        )
    }
}

class ApexLogSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = ApexLogSyntaxHighlighter()
}

class ApexLogColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = ApexLogFileType.ICON

    override fun getHighlighter(): SyntaxHighlighter = ApexLogSyntaxHighlighter()

    override fun getDemoText(): String =
        """
        64.0 APEX_CODE,FINEST;APEX_PROFILING,INFO;DB,INFO;SYSTEM,DEBUG
        09:15:02.12 (12345678)|EXECUTION_STARTED
        09:15:02.12 (12400000)|CODE_UNIT_STARTED|[EXTERNAL]|execute_anonymous_apex
        09:15:02.13 (13000000)|METHOD_ENTRY|[1]|01p000000000001|AccountService.load()
        09:15:02.14 (14000000)|SOQL_EXECUTE_BEGIN|[5]|Aggregations:0|SELECT Id FROM Account
        09:15:02.15 (15000000)|USER_DEBUG|[7]|DEBUG|Loaded 3 accounts
        09:15:02.16 (16000000)|EXCEPTION_THROWN|[9]|System.NullPointerException: Attempt to de-reference a null object
        09:15:02.17 (17000000)|LIMIT_USAGE_FOR_NS|(default)|
          Number of SOQL queries: 1 out of 100
        09:15:02.18 (18000000)|HEAP_ALLOCATE|[72]|Bytes:3
        """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Header", ApexLogColors.HEADER),
        AttributesDescriptor("Timestamp", ApexLogColors.TIMESTAMP),
        AttributesDescriptor("Separator", ApexLogColors.SEPARATOR),
        AttributesDescriptor("Line reference", ApexLogColors.LINE_REF),
        AttributesDescriptor("Debug message", ApexLogColors.DEBUG_MESSAGE),
        AttributesDescriptor("Events//Execution and code unit", ApexLogColors.EVENT_UNIT),
        AttributesDescriptor("Events//Method", ApexLogColors.EVENT_METHOD),
        AttributesDescriptor("Events//SOQL, DML and callout", ApexLogColors.EVENT_QUERY),
        AttributesDescriptor("Events//User debug", ApexLogColors.EVENT_DEBUG),
        AttributesDescriptor("Events//Error", ApexLogColors.EVENT_ERROR),
        AttributesDescriptor("Events//Limits", ApexLogColors.EVENT_LIMITS),
        AttributesDescriptor("Events//Other", ApexLogColors.EVENT_OTHER),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Apex Debug Log"
}
