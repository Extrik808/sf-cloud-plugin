package dev.sfcloud.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

class ApexParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = ApexLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val declarations = ApexStructure.parse(builder.originalText).asSequence()
            .flatMap { it.flatten() }
            .map { it.nameOffset }
            .toHashSet()
        val marker = builder.mark()
        while (!builder.eof()) {
            val type = builder.tokenType
            if (type == ApexTokens.IDENTIFIER || type == ApexTokens.BIND_VARIABLE) {
                val declaration = type == ApexTokens.IDENTIFIER && builder.currentOffset in declarations
                val token = builder.mark()
                builder.advanceLexer()
                token.done(if (declaration) ApexElements.DECLARATION else ApexElements.REFERENCE)
            } else {
                builder.advanceLexer()
            }
        }
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = ApexTokens.FILE

    override fun getCommentTokens(): TokenSet = ApexTokens.COMMENTS

    override fun getStringLiteralElements(): TokenSet = ApexTokens.STRINGS

    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        ApexElements.DECLARATION -> ApexNamedElement(node)
        ApexElements.REFERENCE -> ApexReferenceElement(node)
        else -> ASTWrapperPsiElement(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = ApexFile(viewProvider)
}

object ApexColors {
    val KEYWORD = key("APEX_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val SOQL_KEYWORD = key("APEX_SOQL_KEYWORD", DefaultLanguageHighlighterColors.METADATA)
    val BIND_VARIABLE = key("APEX_BIND_VARIABLE", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val STRING = key("APEX_STRING", DefaultLanguageHighlighterColors.STRING)
    val NUMBER = key("APEX_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val LINE_COMMENT = key("APEX_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val BLOCK_COMMENT = key("APEX_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    val DOC_COMMENT = key("APEX_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val ANNOTATION = key("APEX_ANNOTATION", DefaultLanguageHighlighterColors.METADATA)
    val BRACES = key("APEX_BRACES", DefaultLanguageHighlighterColors.BRACES)
    val PARENTHESES = key("APEX_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    val BRACKETS = key("APEX_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    val SEMICOLON = key("APEX_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
    val COMMA = key("APEX_COMMA", DefaultLanguageHighlighterColors.COMMA)
    val DOT = key("APEX_DOT", DefaultLanguageHighlighterColors.DOT)
    val OPERATOR = key("APEX_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val IDENTIFIER = key("APEX_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)

    private fun key(name: String, fallback: TextAttributesKey) = TextAttributesKey.createTextAttributesKey(name, fallback)
}

open class ApexSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ApexLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        pack(ATTRIBUTES[tokenType])

    companion object {
        private val ATTRIBUTES: Map<IElementType, TextAttributesKey> = mapOf(
            ApexTokens.KEYWORD to ApexColors.KEYWORD,
            ApexTokens.SOQL_KEYWORD to ApexColors.SOQL_KEYWORD,
            ApexTokens.BIND_VARIABLE to ApexColors.BIND_VARIABLE,
            ApexTokens.STRING to ApexColors.STRING,
            ApexTokens.NUMBER to ApexColors.NUMBER,
            ApexTokens.LINE_COMMENT to ApexColors.LINE_COMMENT,
            ApexTokens.BLOCK_COMMENT to ApexColors.BLOCK_COMMENT,
            ApexTokens.DOC_COMMENT to ApexColors.DOC_COMMENT,
            ApexTokens.ANNOTATION to ApexColors.ANNOTATION,
            ApexTokens.LBRACE to ApexColors.BRACES,
            ApexTokens.RBRACE to ApexColors.BRACES,
            ApexTokens.LPAREN to ApexColors.PARENTHESES,
            ApexTokens.RPAREN to ApexColors.PARENTHESES,
            ApexTokens.LBRACKET to ApexColors.BRACKETS,
            ApexTokens.RBRACKET to ApexColors.BRACKETS,
            ApexTokens.SEMICOLON to ApexColors.SEMICOLON,
            ApexTokens.COMMA to ApexColors.COMMA,
            ApexTokens.DOT to ApexColors.DOT,
            ApexTokens.OPERATOR to ApexColors.OPERATOR,
            ApexTokens.IDENTIFIER to ApexColors.IDENTIFIER,
            TokenType.BAD_CHARACTER to HighlighterColors.BAD_CHARACTER,
        )
    }
}

class ApexSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = ApexSyntaxHighlighter()
}

class ApexColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = SfCloudIcons.Apex

    override fun getHighlighter(): SyntaxHighlighter = ApexSyntaxHighlighter()

    override fun getDemoText(): String =
        """
        /**
         * Account service
         */
        @IsTest
        public with sharing class AccountService {
            private static final Integer LIMIT_SIZE = 10;

            // Loads accounts
            public static List<Account> load(String name) {
                return [SELECT Id, Name FROM Account WHERE Name = :name LIMIT 10];
            }
        }
        """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Keyword", ApexColors.KEYWORD),
        AttributesDescriptor("SOQL keyword", ApexColors.SOQL_KEYWORD),
        AttributesDescriptor("SOQL bind variable", ApexColors.BIND_VARIABLE),
        AttributesDescriptor("String", ApexColors.STRING),
        AttributesDescriptor("Number", ApexColors.NUMBER),
        AttributesDescriptor("Comments//Line comment", ApexColors.LINE_COMMENT),
        AttributesDescriptor("Comments//Block comment", ApexColors.BLOCK_COMMENT),
        AttributesDescriptor("Comments//Doc comment", ApexColors.DOC_COMMENT),
        AttributesDescriptor("Annotation", ApexColors.ANNOTATION),
        AttributesDescriptor("Braces and Operators//Braces", ApexColors.BRACES),
        AttributesDescriptor("Braces and Operators//Parentheses", ApexColors.PARENTHESES),
        AttributesDescriptor("Braces and Operators//Brackets", ApexColors.BRACKETS),
        AttributesDescriptor("Braces and Operators//Semicolon", ApexColors.SEMICOLON),
        AttributesDescriptor("Braces and Operators//Comma", ApexColors.COMMA),
        AttributesDescriptor("Braces and Operators//Dot", ApexColors.DOT),
        AttributesDescriptor("Braces and Operators//Operator", ApexColors.OPERATOR),
        AttributesDescriptor("Identifier", ApexColors.IDENTIFIER),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Apex"
}

class ApexCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun getLineCommentTokenType(): IElementType = ApexTokens.LINE_COMMENT

    override fun getBlockCommentTokenType(): IElementType = ApexTokens.BLOCK_COMMENT

    override fun getDocumentationCommentTokenType(): IElementType = ApexTokens.DOC_COMMENT

    override fun getDocumentationCommentPrefix(): String = "/**"

    override fun getDocumentationCommentLinePrefix(): String = "*"

    override fun getDocumentationCommentSuffix(): String = "*/"

    override fun isDocumentationComment(element: com.intellij.psi.PsiComment?): Boolean =
        element?.tokenType == ApexTokens.DOC_COMMENT
}

class ApexBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(ApexTokens.LBRACE, ApexTokens.RBRACE, true),
            BracePair(ApexTokens.LPAREN, ApexTokens.RPAREN, false),
            BracePair(ApexTokens.LBRACKET, ApexTokens.RBRACKET, false),
        )
    }
}
