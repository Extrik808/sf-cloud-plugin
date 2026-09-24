package dev.sfcloud

import dev.sfcloud.api.AnonymousApexExecutor
import dev.sfcloud.log.AggregateKey
import dev.sfcloud.log.ApexLogLevel
import dev.sfcloud.log.ApexLogParser
import dev.sfcloud.log.ContextKind
import dev.sfcloud.log.DebugLevelRecord
import dev.sfcloud.log.LogCategory
import dev.sfcloud.log.LogCategoryKey
import dev.sfcloud.log.LogLevelPreset
import dev.sfcloud.log.LogLevels
import dev.sfcloud.log.LogTreeView
import dev.sfcloud.log.SalesforceIdFilter
import dev.sfcloud.metadata.ComponentRef
import dev.sfcloud.metadata.MetadataXml
import dev.sfcloud.org.Connections
import dev.sfcloud.repl.QueryResultsPanel
import dev.sfcloud.repl.SoqlText
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLogicTest {
    private val log = ApexLogParser.parse(javaClass.getResourceAsStream("/sf/apex-debug.log")!!.bufferedReader().readText())

    @Test
    fun presetsMatchOnTheirKeyCategories() {
        assertEquals(LogLevelPreset.DEFAULT, LogLevelPreset.matching(LogLevels.DEFAULT))
        val tweaked = LogLevelPreset.FULL_DEBUGGING.levels.with(LogCategoryKey.WORKFLOW, ApexLogLevel.FINEST)
        assertEquals(LogLevelPreset.FULL_DEBUGGING, LogLevelPreset.matching(tweaked))
        assertNull(LogLevelPreset.matching(LogLevels.DEFAULT.with(LogCategoryKey.APEX_CODE, ApexLogLevel.WARN).with(LogCategoryKey.SYSTEM, ApexLogLevel.WARN)))
        assertEquals("Full Debugging (Debug)", LogLevelPreset.FULL_DEBUGGING.displayName)
    }

    @Test
    fun levelsSurviveEncoding() {
        val levels = LogLevelPreset.SAMPLING.levels
        assertEquals(levels, LogLevels.decode(levels.encode()))
        assertNull(LogLevels.decode(""))
        assertEquals(ApexLogLevel.DEBUG, LogLevels.decode("APEX_CODE=bogus")!![LogCategoryKey.APEX_CODE])
    }

    @Test
    fun debugLevelNamesFollowSalesforceRules() {
        assertTrue(DebugLevelRecord.isValidName("My_Level1"))
        assertFalse(DebugLevelRecord.isValidName("1Level"))
        assertFalse(DebugLevelRecord.isValidName("Bad__Name"))
        assertFalse(DebugLevelRecord.isValidName("Trailing_"))
        assertFalse(DebugLevelRecord.isValidName("With Space"))
    }

    @Test
    fun soapEnvelopeCarriesEveryApiCategory() {
        val envelope = AnonymousApexExecutor.envelope("abc", "x", LogLevelPreset.TRACING.levels)
        listOf("Apex_code", "Apex_profiling", "Callout", "Db", "Nba", "System", "Validation", "Visualforce", "Wave", "Workflow").forEach {
            assertTrue(it, envelope.contains("<apex:category>$it</apex:category><apex:level>Finest</apex:level>"))
        }
        assertFalse(envelope.contains("Data"))
    }

    @Test
    fun soqlValidationHelpers() {
        val query = "SELECT Id, Name, (SELECT Id FROM Contacts) FROM Account WHERE Name LIKE 'A%' ORDER BY Name LIMIT 5"
        assertTrue(SoqlText.isValid(query))
        assertFalse(SoqlText.isValid("SELEC Id FROM Account"))
        assertFalse(SoqlText.isValid("SELECT Id, (SELECT Id FROM Contacts FROM Account"))
        assertEquals("Account", SoqlText.sobject(query))
        assertTrue(SoqlText.isConstrained(query))
        assertTrue(SoqlText.hasUnconstrainedSubquery(query))
        assertFalse(SoqlText.isConstrained("SELECT Id, (SELECT Id FROM Contacts WHERE Email != null) FROM Account"))
        assertEquals("SELECT COUNT() FROM Account WHERE Name LIKE 'A%'", SoqlText.countQuery(query))
        assertEquals(5, SoqlText.limit(query))
        assertEquals(5, SoqlText.effectiveCount(query, 103305))
        assertEquals(10, SoqlText.effectiveCount("SELECT Id, (SELECT Id FROM Contacts LIMIT 2000) FROM Account LIMIT 10", 103305))
        assertEquals(103305, SoqlText.effectiveCount("SELECT Id, (SELECT Id FROM Contacts LIMIT 2000) FROM Account", 103305))
        assertEquals(3, SoqlText.effectiveCount("SELECT Id FROM Account LIMIT 50 OFFSET 7", 10))
        assertEquals(listOf("Id", "Name"), SoqlText.selectedFields(query))
        assertTrue(SoqlText.isToolingOnly("select Id from ApexCodeCoverageAggregate"))
        assertEquals("SELECT Id FROM Account", SoqlText.normalize("  SELECT Id FROM Account; "))
    }

    @Test
    fun queryRowsFlattenRelationshipsInSelectOrder() {
        val record = JsonParser.parseString(
            """{"attributes":{"type":"Contact"},"Name":"Ann","Account":{"attributes":{"type":"Account"},"Name":"Acme","Owner":null},"Id":"003A","Cases":{"totalSize":2,"records":[{},{}]}}""",
        ).asJsonObject
        val row = QueryResultsPanel.flatten(record)
        assertEquals(mapOf("Name" to "Ann", "Account.Name" to "Acme", "Account.Owner" to "", "Id" to "003A", "Cases" to "[2 rows]"), row)
        assertEquals(
            listOf("Id", "Name", "Account.Name", "Account.Owner", "Cases"),
            SoqlText.columns("SELECT Id, Name, Account.Name, Account.Owner, (SELECT Id FROM Cases) FROM Contact", listOf(row)),
        )
    }

    @Test
    fun packageXmlRoundTrip() {
        val refs = listOf(ComponentRef("CustomField", "Account.A__c"), ComponentRef("ApexClass", "B"), ComponentRef("ApexClass", "A&B"))
        val xml = MetadataXml.packageXml(refs, "64.0")
        assertTrue(xml, xml.contains("<members>A&amp;B</members>"))
        assertTrue(xml.indexOf("<name>ApexClass</name>") < xml.indexOf("<name>CustomField</name>"))
        assertEquals(refs.sorted(), MetadataXml.readPackageXml(xml).sorted())
    }

    @Test
    fun usernamesAreShortenedAroundTheDomain() {
        assertEquals("dev@example.com", Connections.shortenUsername("dev@example.com"))
        val long = Connections.shortenUsername("jane.doe.developer@acme-payments.com.fullcopy.sandbox")
        assertEquals(40, long.length)
        assertTrue(long, long.endsWith(".sandbox"))
        assertTrue(long.contains('…'))
    }

    @Test
    fun treeViewsFilterAndPruneEvents() {
        val debug = LogTreeView.DEBUG_ONLY.root(log)
        assertEquals(listOf("USER_DEBUG"), debug.children.map { it.event!!.type })
        val database = LogTreeView.DATABASE.root(log)
        val types = mutableListOf<String>()
        fun visit(node: dev.sfcloud.log.LogNode) {
            node.event?.let { types += it.type }
            node.children.forEach { visit(it) }
        }
        visit(database)
        assertFalse(types.contains("STATEMENT_EXECUTE"))
        assertFalse(types.contains("USER_DEBUG"))
        assertTrue(types.contains("SOQL_EXECUTE_BEGIN"))
        assertTrue(types.contains("DML_BEGIN"))
    }

    @Test
    fun nodeMetricsCountQueriesAndRows() {
        val unit = log.root.children.single { it.event?.type == "EXECUTION_STARTED" }.children.single()
        val first = unit.children.first { it.event?.type == "METHOD_ENTRY" }
        assertEquals(1L, first.selfMetrics.soqlQueries)
        assertEquals(3L, first.selfMetrics.soqlRows)
        assertEquals(1L, first.selfMetrics.dmlStatements)
        assertEquals(2L, first.selfMetrics.dmlRows)
        assertEquals(2L, unit.totalMetrics.soqlQueries)
        assertEquals(0L, unit.selfMetrics.soqlQueries)
        assertEquals(100L, log.maxSoqlQueries)
        assertEquals(10000L, log.maxDmlRows)
    }

    @Test
    fun callersAndCalleesAggregateInvocations() {
        val key = AggregateKey(LogCategory.METHOD, "AccountService.load()")
        val invocations = log.invocations(key)
        assertEquals(2, invocations.size)

        val callers = ContextKind.CALLERS.build(invocations)
        assertEquals(2, callers.count)
        val unit = callers.children.values.single()
        assertEquals("execute_anonymous_apex", unit.key.name)
        assertEquals(2, unit.count)

        val callees = ContextKind.CALLEES.build(invocations)
        val soql = callees.children.values.single { it.key.category == LogCategory.SOQL }
        assertEquals(2, soql.count)
        assertEquals(7_000_000L, soql.totalNanos)
        assertEquals(20_000_000L, callees.totalNanos)

        val merged = ContextKind.MERGED_CALLEES.build(invocations)
        assertEquals(setOf(LogCategory.SOQL, LogCategory.DML, LogCategory.LIMITS), merged.children.values.map { it.key.category }.toSet())
    }

    @Test
    fun recordIdRecognisesOnlySalesforceIds() {
        assertTrue(SalesforceIdFilter.isRecordId("0015g00000AbCdE"))
        assertTrue(SalesforceIdFilter.isRecordId("0015g00000AbCdEAAV"))
        assertFalse("A word of Id length is not an Id", SalesforceIdFilter.isRecordId("AccountServices"))
        assertFalse("A 16-character value is not an Id", SalesforceIdFilter.isRecordId("0015g00000AbCdEA"))
        assertFalse("A number is not an Id", SalesforceIdFilter.isRecordId("100000000000000"))
        assertFalse(SalesforceIdFilter.isRecordId(null))
    }
}
