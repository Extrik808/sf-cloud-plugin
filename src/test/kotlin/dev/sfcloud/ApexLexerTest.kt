package dev.sfcloud

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.sfcloud.lang.ApexLexer
import dev.sfcloud.lang.ApexTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexLexerTest {
    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = ApexLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) {
                tokens += lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return tokens
    }

    @Test
    fun keywordsAreCaseInsensitiveAndTypesAreIdentifiersOtherwise() {
        val tokens = lex("PUBLIC With Sharing class Foo")
        assertEquals(
            listOf(ApexTokens.KEYWORD, ApexTokens.KEYWORD, ApexTokens.KEYWORD, ApexTokens.KEYWORD, ApexTokens.IDENTIFIER),
            tokens.map { it.first },
        )
    }

    @Test
    fun inlineSoqlUsesSoqlKeywordsAndBindVariablesUntilTheClosingBracket() {
        val tokens = lex("List<Account> a = [SELECT Id FROM Account WHERE Name = :acc.Name LIMIT 1]; update a;")
        val soql = tokens.filter { it.first == ApexTokens.SOQL_KEYWORD }.map { it.second }
        assertEquals(listOf("SELECT", "FROM", "WHERE", "LIMIT"), soql)
        assertTrue(tokens.contains(ApexTokens.BIND_VARIABLE to ":acc.Name"))
        assertEquals(ApexTokens.KEYWORD, tokens.first { it.second == "update" }.first)
    }

    @Test
    fun arrayIndexBracketsDoNotEnterSoqlMode() {
        val tokens = lex("x = items[0]; select = 1;")
        assertTrue(tokens.none { it.first == ApexTokens.SOQL_KEYWORD })
    }

    @Test
    fun nestedSubqueryKeepsSoqlModeUntilTheOuterBracket() {
        val tokens = lex("[SELECT Id, (SELECT Id FROM Contacts) FROM Account] from")
        assertEquals(ApexTokens.SOQL_KEYWORD, tokens.filter { it.second == "FROM" }.last().first)
        assertEquals(ApexTokens.IDENTIFIER, tokens.last().first)
    }

    @Test
    fun commentsStringsAndAnnotationsAreRecognised() {
        val tokens = lex("/** doc */ /* block */ // line\n@IsTest 'it\\'s' 42")
        assertEquals(
            listOf(
                ApexTokens.DOC_COMMENT,
                ApexTokens.BLOCK_COMMENT,
                ApexTokens.LINE_COMMENT,
                ApexTokens.ANNOTATION,
                ApexTokens.STRING,
                ApexTokens.NUMBER,
            ),
            tokens.map { it.first },
        )
        assertEquals("'it\\'s'", tokens[4].second)
    }

    @Test
    fun unterminatedBlockCommentEndsAtBufferEnd() {
        val text = "/* never closed"
        val tokens = lex(text)
        assertEquals(listOf(ApexTokens.BLOCK_COMMENT to text), tokens)
    }

    @Test
    fun restartingFromSoqlStateContinuesInSoqlMode() {
        val text = "WHERE Id = :recordId]"
        val lexer = ApexLexer()
        lexer.start(text, 0, text.length, 1)
        assertEquals(ApexTokens.SOQL_KEYWORD, lexer.tokenType)
    }
}
