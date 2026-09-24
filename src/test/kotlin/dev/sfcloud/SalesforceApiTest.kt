package dev.sfcloud

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sfcloud.api.AnonymousApexExecutor
import dev.sfcloud.api.SfApi
import dev.sfcloud.log.LogLevelPreset
import dev.sfcloud.log.LogLevels
import dev.sfcloud.log.LoggingConfig
import dev.sfcloud.settings.SfCloudSettings
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SalesforceApiTest : BasePlatformTestCase() {
    private lateinit var server: HttpServer
    private lateinit var workDir: Path
    private var previousSfPath: String? = null
    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val tokenCalls = AtomicInteger()
    private val handlers = LinkedHashMap<String, (HttpExchange, String) -> Pair<Int, String>>()

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val key = "${exchange.requestMethod} ${exchange.requestURI}"
            requests += key
            val requestBody = exchange.requestBody.readAllBytes().decodeToString()
            bodies += requestBody
            val handler = handlers.entries.firstOrNull { key.startsWith(it.key) }?.value
            val (status, body) = handler?.invoke(exchange, requestBody) ?: (404 to """[{"message":"not found","errorCode":"NOT_FOUND"}]""")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        workDir = Files.createTempDirectory("sf-cloud-api")
        previousSfPath = SfCloudSettings.getInstance().state.sfPath
        installFakeSf()
        SfApi.getInstance(project).invalidate()
    }

    override fun tearDown() {
        try {
            server.stop(0)
            SfCloudSettings.getInstance().state.sfPath = previousSfPath
            SfApi.getInstance(project).invalidate()
            workDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun installFakeSf(redacted: Boolean = false) {
        val counter = workDir.resolve("count")
        val calls = workDir.resolve("calls")
        val script = workDir.resolve("sf")
        val url = "http://127.0.0.1:${server.address.port}"
        val displayToken = if (redacted) "[REDACTED]" else "token-%s"
        Files.writeString(
            script,
            """
            #!/bin/sh
            echo "${'$'}*" >> '$calls'
            if [ "${'$'}1 ${'$'}2 ${'$'}3" = "org auth show-access-token" ]; then
              printf '{"status":0,"result":{"accessToken":"token-%s"}}' "${'$'}(wc -l < '$counter' | tr -d ' ')"
              exit 0
            fi
            echo x >> '$counter'
            n=${'$'}(wc -l < '$counter' | tr -d ' ')
            printf '{"status":0,"result":{"accessToken":"$displayToken","instanceUrl":"$url","apiVersion":"64.0","username":"dev@example.com","alias":"Dev"},"warnings":["Secrets are now hidden"]}' "${'$'}n"
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        SfCloudSettings.getInstance().state.sfPath = script.toString()
    }

    private fun on(prefix: String, handler: (HttpExchange, String) -> Pair<Int, String>) {
        handlers[prefix] = handler
    }

    private fun on(prefix: String, response: Pair<Int, String>) {
        handlers[prefix] = { _, _ -> response }
    }

    fun testQueryFollowsNextRecordsUrlAndRetriesExpiredSessions() {
        on("GET /services/data/v64.0/query/?q=") { exchange, _ ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            tokenCalls.incrementAndGet()
            if (auth == "Bearer token-1") {
                401 to """[{"message":"Session expired or invalid","errorCode":"INVALID_SESSION_ID"}]"""
            } else {
                200 to """{"totalSize":3,"done":false,"nextRecordsUrl":"/services/data/v64.0/query/01g-2000","records":[{"Id":"001A"},{"Id":"001B"}]}"""
            }
        }
        on("GET /services/data/v64.0/query/01g-2000", 200 to """{"totalSize":3,"done":true,"records":[{"Id":"001C"}]}""")

        val result = SfApi.getInstance(project).query("Dev", "SELECT Id FROM Account")

        assertEquals(3, result.totalSize)
        assertEquals(listOf("001A", "001B", "001C"), result.records.map { it.get("Id").asString })
        assertEquals(2, tokenCalls.get())
        assertTrue(requests.first(), requests.first().contains("q=SELECT%20Id%20FROM%20Account"))
    }

    fun testRedactedDisplayTokenFallsBackToShowAccessToken() {
        installFakeSf(redacted = true)
        on("GET /services/data/v64.0/query/?q=") { exchange, _ ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            tokenCalls.incrementAndGet()
            when (auth) {
                "Bearer token-1" -> 401 to """[{"message":"Session expired or invalid","errorCode":"INVALID_SESSION_ID"}]"""
                "Bearer token-2" -> 200 to """{"totalSize":1,"done":true,"records":[{"Id":"001A"}]}"""
                else -> 400 to """[{"message":"unexpected $auth","errorCode":"BAD"}]"""
            }
        }

        val result = SfApi.getInstance(project).query("Dev", "SELECT Id FROM Account")

        assertEquals(listOf("001A"), result.records.map { it.get("Id").asString })
        assertEquals(2, tokenCalls.get())
        val calls = Files.readAllLines(workDir.resolve("calls"))
        assertEquals(
            "The token is read with show-access-token for the resolved username after each org display",
            listOf(
                "org display --target-org Dev --json",
                "org auth show-access-token --no-prompt --target-org dev@example.com --json",
                "org display --target-org Dev --json",
                "org auth show-access-token --no-prompt --target-org dev@example.com --json",
            ),
            calls,
        )
    }

    fun testUsableTokenRejectsRedactedValues() {
        assertNull(SfApi.usableToken(null))
        assertNull(SfApi.usableToken(" "))
        assertNull(SfApi.usableToken("[REDACTED]"))
        assertNull(SfApi.usableToken("<redacted>"))
        assertNull(SfApi.usableToken("Hidden. Use 'sf org auth show-access-token' to view it"))
        assertEquals("00D000000000001!AQ", SfApi.usableToken("00D000000000001!AQ"))
    }

    fun testDeleteRecordsReportsPartialFailures() {
        on("DELETE /services/data/v64.0/composite/sobjects?allOrNone=false&ids=07L1,07L2", 200 to """[{"id":"07L1","success":true,"errors":[]},{"id":"07L2","success":false,"errors":[{"message":"entity is deleted"}]}]""")

        val failures = SfApi.getInstance(project).deleteRecords("Dev", listOf("07L1", "07L2"))

        assertEquals(listOf("07L2: entity is deleted"), failures)
    }

    fun testSavingLevelsUpdatesTheDevConsoleLevelAndTheUserTraceFlag() {
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20DeveloperName", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"7dl1","DeveloperName":"SFDC_DevConsole","ApexCode":"DEBUG","System":"INFO"}]}""")
        on("PATCH /services/data/v64.0/tooling/sobjects/DebugLevel/7dl1", 204 to "")
        on("GET /services/data/v64.0/query/?q=SELECT%20Id%20FROM%20User", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"005U"}]}""")
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20ExpirationDate", 200 to """{"totalSize":0,"done":true,"records":[]}""")
        on("POST /services/data/v64.0/tooling/sobjects/TraceFlag", 201 to """{"id":"7tf1","success":true}""")

        LoggingConfig(project).saveLevels("Dev", LogLevelPreset.FULL_DEBUGGING.levels)

        val patch = JsonParser.parseString(bodies[requests.indexOfFirst { it.startsWith("PATCH") }]).asJsonObject
        assertEquals("FINEST", patch.get("ApexCode").asString)
        assertEquals("FINE", patch.get("Visualforce").asString)
        val flag = JsonParser.parseString(bodies[requests.indexOfFirst { it.startsWith("POST") }]).asJsonObject
        assertEquals("005U", flag.get("TracedEntityId").asString)
        assertEquals("DEVELOPER_LOG", flag.get("LogType").asString)
        assertEquals("7dl1", flag.get("DebugLevelId").asString)
    }

    fun testOpeningLogsCreatesTheUserTraceFlagWhenNoneExists() {
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20DeveloperName", 200 to """{"totalSize":0,"done":true,"records":[]}""")
        on("POST /services/data/v64.0/tooling/sobjects/DebugLevel", 201 to """{"id":"7dl9","success":true}""")
        on("GET /services/data/v64.0/query/?q=SELECT%20Id%20FROM%20User", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"005U"}]}""")
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20ExpirationDate", 200 to """{"totalSize":0,"done":true,"records":[]}""")
        on("POST /services/data/v64.0/tooling/sobjects/TraceFlag", 201 to """{"id":"7tf1","success":true}""")

        val levels = LoggingConfig(project).enableUserTracing("Dev", LogLevelPreset.FULL_DEBUGGING.levels)

        assertEquals(LogLevelPreset.FULL_DEBUGGING.levels, levels)
        val flag = JsonParser.parseString(bodies[requests.indexOfFirst { it.startsWith("POST /services/data/v64.0/tooling/sobjects/TraceFlag") }]).asJsonObject
        assertEquals("005U", flag.get("TracedEntityId").asString)
        assertEquals("DEVELOPER_LOG", flag.get("LogType").asString)
        assertEquals("7dl9", flag.get("DebugLevelId").asString)
    }

    fun testOpeningLogsExtendsAnExpiredFlagAndKeepsItsDebugLevel() {
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20DeveloperName", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"7dl1","DeveloperName":"SFDC_DevConsole","ApexCode":"DEBUG"}]}""")
        on("GET /services/data/v64.0/query/?q=SELECT%20Id%20FROM%20User", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"005U"}]}""")
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20ExpirationDate", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"7tf1","ExpirationDate":"2020-01-01T00:00:00.000+0000","DebugLevelId":"7dlCustom"}]}""")
        on("PATCH /services/data/v64.0/tooling/sobjects/TraceFlag/7tf1", 204 to "")

        LoggingConfig(project).enableUserTracing("Dev", LogLevels.DEFAULT)

        val patch = JsonParser.parseString(bodies[requests.indexOfFirst { it.startsWith("PATCH") }]).asJsonObject
        assertEquals("7dlCustom", patch.get("DebugLevelId").asString)
        assertTrue(LoggingConfig.parseDate(patch.get("ExpirationDate").asString)!!.isAfter(java.time.Instant.now().plusSeconds(23 * 3600)))
    }

    fun testOpeningLogsLeavesAnActiveFlagAlone() {
        val expiration = LoggingConfig.format(java.time.Instant.now().plusSeconds(5 * 3600))
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20DeveloperName", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"7dl1","DeveloperName":"SFDC_DevConsole","ApexCode":"DEBUG"}]}""")
        on("GET /services/data/v64.0/query/?q=SELECT%20Id%20FROM%20User", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"005U"}]}""")
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20ExpirationDate", 200 to """{"totalSize":1,"done":true,"records":[{"Id":"7tf1","ExpirationDate":"$expiration","DebugLevelId":"7dl1"}]}""")

        LoggingConfig(project).enableUserTracing("Dev", LogLevels.DEFAULT)

        assertTrue(requests.toString(), requests.none { it.startsWith("POST /services/data") || it.startsWith("PATCH") })
    }

    fun testLevelsLoadFallsBackToDefaultsWithoutADevConsoleLevel() {
        on("GET /services/data/v64.0/tooling/query/?q=SELECT%20Id%2C%20DeveloperName", 200 to """{"totalSize":0,"done":true,"records":[]}""")
        assertEquals(LogLevels.DEFAULT, LoggingConfig(project).loadLevels("Dev"))
    }

    fun testAnonymousApexIsExecutedOverSoapWithDebuggingCategories() {
        on("POST /services/Soap/s/64.0", 200 to """<?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="http://soap.sforce.com/2006/08/apex" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <soapenv:Header><DebuggingInfo><debugLog>64.0 APEX_CODE,FINEST
09:00:00.1 (100)|USER_DEBUG|[1]|DEBUG|hi</debugLog></DebuggingInfo></soapenv:Header>
                <soapenv:Body><executeAnonymousResponse><result>
                <column>-1</column><compileProblem xsi:nil="true"/><compiled>true</compiled>
                <exceptionMessage>System.MathException: Divide by 0</exceptionMessage>
                <exceptionStackTrace>AnonymousBlock: line 2, column 1</exceptionStackTrace>
                <line>2</line><success>false</success>
                </result></executeAnonymousResponse></soapenv:Body></soapenv:Envelope>""")

        val result = AnonymousApexExecutor(project).execute("Dev", "Integer i = 1 / 0; // <&>", LogLevelPreset.DEFAULT.levels)

        assertTrue(result.compiled)
        assertFalse(result.success)
        assertEquals(2, result.line)
        assertNull(result.column)
        assertEquals("System.MathException: Divide by 0", result.exceptionMessage)
        assertTrue(result.log!!.contains("|USER_DEBUG|"))
        val envelope = bodies.single()
        assertTrue(envelope, envelope.contains("<apex:sessionId>token-1</apex:sessionId>"))
        assertTrue(envelope, envelope.contains("<apex:category>Apex_code</apex:category><apex:level>Debug</apex:level>"))
        assertTrue(envelope, envelope.contains("Integer i = 1 / 0; // &lt;&amp;&gt;"))
    }

    fun testMetadataListingIsParsedFromSoap() {
        on("POST /services/Soap/m/64.0") { exchange, body ->
            if (body.contains("describeMetadata")) {
                200 to """<?xml version="1.0"?><soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="http://soap.sforce.com/2006/04/metadata"><soapenv:Body><describeMetadataResponse><result>
                    <metadataObjects><childXmlNames>CustomField</childXmlNames><directoryName>objects</directoryName><inFolder>false</inFolder><metaFile>false</metaFile><suffix>object</suffix><xmlName>CustomObject</xmlName></metadataObjects>
                    <metadataObjects><directoryName>classes</directoryName><inFolder>false</inFolder><metaFile>true</metaFile><suffix>cls</suffix><xmlName>ApexClass</xmlName></metadataObjects>
                    <metadataObjects><directoryName>reports</directoryName><inFolder>true</inFolder><metaFile>false</metaFile><suffix>report</suffix><xmlName>Report</xmlName></metadataObjects>
                    </result></describeMetadataResponse></soapenv:Body></soapenv:Envelope>"""
            } else {
                val items = buildString {
                    if (body.contains("<met:type>ApexClass</met:type>")) append(item("ApexClass", "AccountService"))
                    if (body.contains("<met:type>CustomField</met:type>")) append(item("CustomField", "Account.New_Field__c"))
                    if (body.contains("<met:type>CustomObject</met:type>")) append(item("CustomObject", "Account"))
                    if (body.contains("<met:type>ReportFolder</met:type>")) append(item("ReportFolder", "Sales"))
                    if (body.contains("<met:folder>Sales</met:folder>")) append(item("Report", "Sales/Pipeline"))
                    if (body.contains("<met:type>ApexClass</met:type>")) append(item("ApexClass", "Installed", "installed"))
                }
                exchange.responseHeaders.add("Content-Type", "text/xml")
                200 to """<?xml version="1.0"?><soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="http://soap.sforce.com/2006/04/metadata"><soapenv:Body><listMetadataResponse>$items</listMetadataResponse></soapenv:Body></soapenv:Envelope>"""
            }
        }

        val metadata = dev.sfcloud.metadata.MetadataApi(project).loadAll("Dev", null)

        assertEquals(listOf("CustomObject", "ApexClass", "Report"), metadata.types.map { it.xmlName })
        assertEquals(listOf("CustomField"), metadata.types.first().childXmlNames)
        assertEquals(
            listOf("ApexClass:AccountService", "CustomField:Account.New_Field__c", "CustomObject:Account", "Report:Sales/Pipeline", "ReportFolder:Sales"),
            metadata.components.map { it.ref.member },
        )
        assertEquals("Admin User", metadata.components.first().lastModifiedBy)
    }

    private fun item(type: String, name: String, state: String = "unmanaged") =
        "<result><fullName>$name</fullName><lastModifiedByName>Admin User</lastModifiedByName><lastModifiedDate>2026-09-01T10:00:00.000Z</lastModifiedDate><manageableState>$state</manageableState><type>$type</type></result>"
}
