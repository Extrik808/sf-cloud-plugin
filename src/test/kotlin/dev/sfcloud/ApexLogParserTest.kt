package dev.sfcloud

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.sfcloud.log.ApexLogLexer
import dev.sfcloud.log.ApexLogParser
import dev.sfcloud.log.ApexLogTokens
import dev.sfcloud.log.LogCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexLogParserTest {
    private val text = javaClass.getResourceAsStream("/sf/apex-debug.log")!!.bufferedReader().readText()
    private val log = ApexLogParser.parse(text)

    @Test
    fun headerGivesApiVersionAndLogLevels() {
        assertEquals("64.0", log.apiVersion)
        assertEquals("FINEST", log.logLevels["APEX_CODE"])
        assertEquals("DEBUG", log.logLevels["SYSTEM"])
    }

    @Test
    fun blocksNestIntoAnExecutionTreeWithDurations() {
        val execution = log.root.children.single { it.event?.type == "EXECUTION_STARTED" }
        val unit = execution.children.single()
        assertEquals("execute_anonymous_apex", unit.event!!.name)
        assertEquals(21_800_000L, unit.durationNanos)
        val methods = unit.children.filter { it.event?.type == "METHOD_ENTRY" }
        assertEquals(listOf("AccountService.load()", "AccountService.load()"), methods.map { it.event!!.name })
        val first = methods.first()
        assertEquals(13_000_000L, first.durationNanos)
        assertEquals(listOf("STATEMENT_EXECUTE", "SOQL_EXECUTE_BEGIN", "USER_DEBUG", "DML_BEGIN"), first.children.map { it.event!!.type })
        assertEquals(5_000_000L, first.children[1].durationNanos)
        assertEquals(3_000_000L, first.selfNanos)
        assertEquals("METHOD_EXIT", first.end!!.type)
    }

    @Test
    fun unclosedBlocksAreClosedByTheirParentsEnd() {
        val unit = log.root.children.single { it.event?.type == "EXECUTION_STARTED" }.children.single()
        val second = unit.children.last { it.event?.type == "METHOD_ENTRY" }
        assertNull(second.end)
        assertEquals(24_000_000L, second.endNanos)
        assertTrue(second.hasError)
    }

    @Test
    fun eventNamesAndMultiLineDetails() {
        val debug = log.events.single { it.type == "USER_DEBUG" }
        assertEquals("Loaded 3 accounts", debug.name)
        assertEquals("second line of the message", debug.details)
        assertEquals(7, debug.sourceLine)
        assertEquals(8, debug.line)
        assertEquals("Insert Contact (2 rows)", log.events.single { it.type == "DML_BEGIN" }.name)
        val fatal = log.events.single { it.type == "FATAL_ERROR" }
        assertTrue(fatal.details, fatal.details.contains("Class.AccountService.load: line 9"))
        assertEquals(LogCategory.ERROR, fatal.category)
    }

    @Test
    fun limitsComeFromTheLimitUsageBlock() {
        val soql = log.limits.single { it.name == "Number of SOQL queries" }
        assertEquals("(default)", soql.namespace)
        assertEquals(2L, soql.used)
        assertEquals(100L, soql.max)
        assertEquals(2, soql.percent)
        assertEquals(4, log.limits.size)
    }

    @Test
    fun hotspotsAggregateRepeatedBlocks() {
        val hotspots = log.hotspots()
        val query = hotspots.single { it.category == LogCategory.SOQL }
        assertEquals(2, query.count)
        assertEquals(7_000_000L, query.totalNanos)
        val method = hotspots.single { it.category == LogCategory.METHOD }
        assertEquals(2, method.count)
        assertEquals(2, log.count(LogCategory.SOQL))
        assertEquals(1, log.count(LogCategory.DML))
    }

    @Test
    fun lexerSplitsEventLinesAndRestartsAtLineStarts() {
        val line = "09:15:02.2 (9500000)|USER_DEBUG|[7]|DEBUG|a|b\nplain\n"
        val tokens = lex(line)
        assertEquals(
            listOf(
                ApexLogTokens.TIMESTAMP, ApexLogTokens.SEPARATOR, ApexLogTokens.EVENT_DEBUG, ApexLogTokens.SEPARATOR,
                ApexLogTokens.LINE_REF, ApexLogTokens.SEPARATOR, ApexLogTokens.TEXT, ApexLogTokens.SEPARATOR,
                ApexLogTokens.DEBUG_MESSAGE, ApexLogTokens.TEXT,
            ),
            tokens.map { it.first },
        )
        assertEquals("a|b", tokens[8].second)
        val lexer = ApexLogLexer()
        lexer.start(text, 0, text.length, ApexLogLexer.LINE_START)
        var restartPoints = 0
        var covered = 0
        while (lexer.tokenType != null) {
            if (lexer.state == ApexLogLexer.LINE_START) restartPoints++
            covered += lexer.tokenEnd - lexer.tokenStart
            lexer.advance()
        }
        assertEquals(text.length, covered)
        assertTrue(restartPoints > 20)
        assertEquals(ApexLogTokens.HEADER, lex(text.lineSequence().first()).single().first)
        assertEquals(ApexLogTokens.EVENT_ERROR, lex("09:15:02.2 (1)|FATAL_ERROR|x")[2].first)
    }

    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = ApexLogLexer()
        lexer.start(text, 0, text.length, ApexLogLexer.LINE_START)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) tokens += lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }
}
