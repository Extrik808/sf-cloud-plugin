package dev.sfcloud.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.FileViewProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

object SfCloudIcons {
    @JvmField
    val Apex: Icon = IconLoader.getIcon("/icons/apex.svg", SfCloudIcons::class.java)

    @JvmField
    val Trigger: Icon = IconLoader.getIcon("/icons/trigger.svg", SfCloudIcons::class.java)

    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/toolWindow.svg", SfCloudIcons::class.java)

    @JvmField
    val ScratchOrg: Icon = IconLoader.getIcon("/icons/scratchOrg.svg", SfCloudIcons::class.java)

    @JvmField
    val AnonymousApex: Icon = IconLoader.getIcon("/icons/anonymousApex.svg", SfCloudIcons::class.java)
}

object ApexLanguage : Language("Apex") {
    private fun readResolve(): Any = ApexLanguage

    override fun isCaseSensitive(): Boolean = false

    override fun getDisplayName(): String = "Apex"
}

object ApexFileType : LanguageFileType(ApexLanguage) {
    override fun getName(): String = "Apex"

    override fun getDescription(): String = "Salesforce Apex"

    override fun getDefaultExtension(): String = "cls"

    override fun getIcon(): Icon = SfCloudIcons.Apex
}

object ApexTriggerFileType : LanguageFileType(ApexLanguage, true) {
    override fun getName(): String = "Apex Trigger"

    override fun getDescription(): String = "Salesforce Apex trigger"

    override fun getDefaultExtension(): String = "trigger"

    override fun getIcon(): Icon = SfCloudIcons.Trigger
}

object AnonymousApexFileType : LanguageFileType(ApexLanguage, true) {
    override fun getName(): String = "Anonymous Apex"

    override fun getDescription(): String = "Salesforce anonymous Apex script"

    override fun getDefaultExtension(): String = "apex"

    override fun getIcon(): Icon = SfCloudIcons.Apex
}

class ApexFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ApexLanguage) {
    override fun getFileType(): FileType = viewProvider.fileType

    override fun toString(): String = "Apex File"
}

class ApexTokenType(debugName: String) : IElementType(debugName, ApexLanguage)

object ApexTokens {
    val FILE = IFileElementType(ApexLanguage)
    val LINE_COMMENT = ApexTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = ApexTokenType("BLOCK_COMMENT")
    val DOC_COMMENT = ApexTokenType("DOC_COMMENT")
    val STRING = ApexTokenType("STRING")
    val NUMBER = ApexTokenType("NUMBER")
    val IDENTIFIER = ApexTokenType("IDENTIFIER")
    val KEYWORD = ApexTokenType("KEYWORD")
    val SOQL_KEYWORD = ApexTokenType("SOQL_KEYWORD")
    val BIND_VARIABLE = ApexTokenType("BIND_VARIABLE")
    val ANNOTATION = ApexTokenType("ANNOTATION")
    val LBRACE = ApexTokenType("LBRACE")
    val RBRACE = ApexTokenType("RBRACE")
    val LPAREN = ApexTokenType("LPAREN")
    val RPAREN = ApexTokenType("RPAREN")
    val LBRACKET = ApexTokenType("LBRACKET")
    val RBRACKET = ApexTokenType("RBRACKET")
    val SEMICOLON = ApexTokenType("SEMICOLON")
    val COMMA = ApexTokenType("COMMA")
    val DOT = ApexTokenType("DOT")
    val OPERATOR = ApexTokenType("OPERATOR")

    val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    val STRINGS = TokenSet.create(STRING)

    val KEYWORDS: Set<String> = setOf(
        "abstract", "after", "before", "break", "catch", "class", "continue", "delete", "do", "else", "enum",
        "extends", "false", "final", "finally", "for", "get", "global", "if", "implements", "insert", "instanceof",
        "interface", "merge", "new", "null", "on", "override", "private", "protected", "public", "return",
        "set", "static", "super", "switch", "testmethod", "this", "throw", "transient", "trigger", "true", "try",
        "undelete", "update", "upsert", "virtual", "void", "webservice", "when", "while", "with", "without",
        "sharing", "inherited", "boolean", "integer", "long", "double", "decimal", "string", "id", "blob",
        "date", "datetime", "time", "object", "sobject", "list", "map",
    )

    val SOQL_KEYWORDS: Set<String> = setOf(
        "select", "from", "where", "and", "or", "not", "in", "includes", "excludes", "like", "limit", "offset",
        "order", "by", "asc", "desc", "nulls", "first", "last", "group", "having", "rollup", "cube", "typeof",
        "when", "then", "else", "end", "using", "scope", "for", "view", "reference", "update", "tracking",
        "viewstat", "with", "security_enforced", "user_mode", "system_mode", "find", "returning", "all", "fields",
        "rows", "true", "false", "null", "today", "yesterday", "tomorrow", "count", "sum", "avg", "min", "max",
        "count_distinct", "format", "tolabel", "convertcurrency", "calendar_year", "calendar_month", "day_only",
    )
}
