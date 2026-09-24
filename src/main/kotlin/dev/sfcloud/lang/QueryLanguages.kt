package dev.sfcloud.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.DelegateLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

class QueryLexer : DelegateLexer(ApexLexer()) {
    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        super.start(buffer, startOffset, endOffset, maxOf(initialState, 1))
    }
}

object SoqlLanguage : Language("SOQL") {
    private fun readResolve(): Any = SoqlLanguage
}

object SoslLanguage : Language("SOSL") {
    private fun readResolve(): Any = SoslLanguage
}

object SoqlFileType : LanguageFileType(SoqlLanguage) {
    override fun getName(): String = "SOQL"

    override fun getDescription(): String = "Salesforce SOQL query"

    override fun getDefaultExtension(): String = "soql"

    override fun getIcon(): Icon = AllIcons.Nodes.DataSchema
}

object SoslFileType : LanguageFileType(SoslLanguage) {
    override fun getName(): String = "SOSL"

    override fun getDescription(): String = "Salesforce SOSL search"

    override fun getDefaultExtension(): String = "sosl"

    override fun getIcon(): Icon = AllIcons.Actions.Search
}

class QueryFile(viewProvider: FileViewProvider, language: Language, private val type: FileType) : PsiFileBase(viewProvider, language) {
    override fun getFileType(): FileType = type
}

abstract class QueryParserDefinition(private val language: Language, private val type: FileType) : ParserDefinition {
    private val fileElement = IFileElementType(language)

    override fun createLexer(project: Project?): Lexer = QueryLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = fileElement

    override fun getCommentTokens(): TokenSet = ApexTokens.COMMENTS

    override fun getStringLiteralElements(): TokenSet = ApexTokens.STRINGS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = QueryFile(viewProvider, language, type)
}

class SoqlParserDefinition : QueryParserDefinition(SoqlLanguage, SoqlFileType)

class SoslParserDefinition : QueryParserDefinition(SoslLanguage, SoslFileType)

class QuerySyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = object : ApexSyntaxHighlighter() {
        override fun getHighlightingLexer(): Lexer = QueryLexer()
    }
}
