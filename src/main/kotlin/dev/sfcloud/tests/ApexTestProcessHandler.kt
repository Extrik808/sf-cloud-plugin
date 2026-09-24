package dev.sfcloud.tests

import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Key
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfCliException
import dev.sfcloud.core.int
import dev.sfcloud.core.obj
import dev.sfcloud.core.objects
import dev.sfcloud.core.str
import dev.sfcloud.org.OrgService
import dev.sfcloud.settings.SfCloudProjectSettings
import java.io.OutputStream

class ApexTestProcessHandler(
    private val configuration: ApexTestRunConfiguration,
    private val collectCoverage: Boolean = false,
) : ProcessHandler() {
    private val indicator = EmptyProgressIndicator()
    private val project = configuration.project
    private val testLogs = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    var coveragePublished = false
        private set

    @Volatile
    var debugOnly = false

    val org: String? = configuration.options.targetOrg?.trim()?.takeIf { it.isNotEmpty() }

    val logs: Map<String, String> get() = testLogs.toSortedMap()

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread { runTests() }
    }

    private fun runTests() {
        val exitCode = try {
            execute()
        } catch (e: SfCliException) {
            if (!indicator.isCanceled) error("${e.message}")
            1
        } catch (e: Exception) {
            error("Unexpected error: ${e.message}")
            1
        }
        notifyProcessTerminated(exitCode)
    }

    private fun execute(): Int {
        val options = configuration.options
        var classes = if (configuration.method != null) emptyList() else configuration.classes
        val methods = configuration.methods
        if (options.changedOnly && !options.allTests && classes.isEmpty() && methods.isEmpty()) {
            classes = com.intellij.openapi.application.runReadAction { ApexTests.changed(project) }
            if (classes.isEmpty()) {
                info("No changed tests found.")
                return 0
            }
        }
        val args = mutableListOf("apex", "run", "test", "--wait", SfCloudProjectSettings.getInstance(project).state.testWaitMinutes.toString())
        when {
            methods.isNotEmpty() -> (classes + methods).forEach { args += listOf("--tests", it) }
            classes.isEmpty() -> args += listOf("--test-level", "RunLocalTests")
            else -> args += listOf("--class-names", classes.joinToString(","))
        }
        if (collectCoverage) args += "--code-coverage"
        args += OrgService.getInstance(project).orgArgs(org)
        val orgLabel = OrgService.getInstance(project).orgLabel(org)
        info("Testing started at ${java.time.LocalTime.now().withNano(0)} ...")
        info("Using connection '$orgLabel'.")
        val levels = configuration.levels
        val started = java.time.Instant.now().minusSeconds(LOG_CLOCK_SKEW_SECONDS)
        if (levels != null) configureLogging(levels)

        val result = SfCli.run(project, args, (SfCloudProjectSettings.getInstance(project).state.testWaitMinutes + 5) * 60 * 1000, indicator)
        val payload = result.payload?.takeIf { it.isJsonObject }?.asJsonObject
        val tests = payload?.get("tests").objects()
        if (tests.isEmpty()) {
            error(result.message)
            payload?.obj("summary")?.str("testRunId")?.let { info("Test run id: $it") }
            return 1
        }

        if (levels != null) collectLogs(started, tests)
        reportTests(tests)
        payload?.obj("summary")?.let { reportSummary(it) }
        if (collectCoverage) {
            val coverage = payload?.obj("coverage")?.get("coverage").objects()
            SfCloudProjectSettings.getInstance(project).state.showCoverage = true
            ApexCoverageService.getInstance(project).update(coverage)
            reportCoverage(coverage, payload?.obj("summary"))
        }
        return if (tests.all { it.str("Outcome") == "Pass" || it.str("Outcome") == "Skip" }) 0 else 1
    }

    private fun configureLogging(levels: dev.sfcloud.log.LogLevels) {
        try {
            dev.sfcloud.log.LoggingConfig(project).saveLevels(org, levels, indicator)
        } catch (e: Exception) {
            error("Warning: Cannot configure trace logging: ${e.message}")
        }
    }

    private fun collectLogs(started: java.time.Instant, tests: List<JsonObject>) {
        val names = tests.mapNotNull { test ->
            val className = test.obj("ApexClass")?.str("Name") ?: test.str("FullName")?.substringBefore('.') ?: return@mapNotNull null
            test.str("MethodName")?.let { "$className.$it" }
        }.toSet()
        try {
            val api = dev.sfcloud.api.SfApi.getInstance(project)
            val userId = api.userId(org, indicator)
            val since = dev.sfcloud.log.LoggingConfig.format(started)
            val records = api.query(
                org,
                "SELECT Id FROM ApexLog WHERE LogUserId = '$userId' AND StartTime >= $since ORDER BY StartTime LIMIT $MAX_TEST_LOGS",
                indicator = indicator,
            ).records
            records.mapNotNull { it.str("Id") }.forEach { id ->
                val body = api.logBody(org, id, indicator)
                val name = testNameOf(body, names) ?: return@forEach
                testLogs.merge(name, body) { old, new -> old + "\n" + new }
            }
        } catch (e: Exception) {
            error("Warning: Cannot retrieve Apex logs: ${e.message}")
        }
    }

    private fun reportTests(tests: List<JsonObject>) {
        tests.groupBy { it.obj("ApexClass")?.str("Name") ?: it.str("FullName")?.substringBefore('.') ?: "Unknown" }
            .forEach { (className, classTests) ->
                service("testSuiteStarted", "name" to className, "locationHint" to "${ApexTestLocator.PROTOCOL}://$className")
                classTests.sortedBy { it.str("MethodName") }.forEach { test ->
                    val method = test.str("MethodName") ?: "unknown"
                    service("testStarted", "name" to method, "locationHint" to "${ApexTestLocator.PROTOCOL}://$className/$method")
                    testLogs["$className.$method"]?.let { log ->
                        val body = if (debugOnly) log.lineSequence().filter { it.contains("|USER_DEBUG|") }.joinToString("\n") else log
                        service("testStdOut", "name" to method, "out" to "Apex log for $className.$method\n${"=".repeat(LOG_SEPARATOR_WIDTH)}\n$body\n")
                    }
                    when (test.str("Outcome")) {
                        "Pass" -> Unit
                        "Skip" -> service("testIgnored", "name" to method, "message" to (test.str("Message") ?: "Skipped"))
                        else -> service(
                            "testFailed",
                            "name" to method,
                            "message" to (test.str("Message") ?: test.str("Outcome").orEmpty()),
                            "details" to test.str("StackTrace").orEmpty(),
                        )
                    }
                    service("testFinished", "name" to method, "duration" to (test.int("RunTime") ?: 0).toString())
                }
                service("testSuiteFinished", "name" to className)
            }
    }

    private fun reportSummary(summary: JsonObject) {
        val parts = listOfNotNull(
            summary.str("outcome"),
            summary.str("testsRan")?.let { "$it ran" },
            summary.str("passing")?.let { "$it passed" },
            summary.str("failing")?.let { "$it failed" },
            summary.str("testTotalTime")?.let { "total $it" },
            summary.str("testRunCoverage")?.let { "run coverage $it" },
            summary.str("orgWideCoverage")?.let { "org coverage $it" },
        )
        info(parts.joinToString(" · "))
    }

    private fun reportCoverage(coverage: List<JsonObject>, summary: JsonObject?) {
        val run = coverage.mapNotNull { entry ->
            val name = entry.str("name") ?: return@mapNotNull null
            CoverageEntry(name, entry.int("totalCovered") ?: 0, entry.int("totalLines") ?: 0)
        }
        val fromOrg = try {
            ApexCoverage.fromOrg(project, org, indicator)
        } catch (e: Exception) {
            error("Warning: Cannot read code coverage of the other Apex files from the org: ${firstLine(e.message)}")
            emptyList()
        }
        val orgWide = summary?.str("orgWideCoverage")?.filter { it.isDigit() }?.toIntOrNull()
            ?: runCatching { ApexCoverage.orgWide(project, org, indicator) }.getOrNull()
        val files = com.intellij.openapi.application.runReadAction { CoverageReports.scan(project) }
        val report = CoverageReports.build(files, run, fromOrg, orgWide)
        ApexCoverageService.getInstance(project).publish(run, report)
        info("Code coverage: ${coverageLine(run, report)}")
        info("Every Apex file of the project is listed in the 'Code Coverage' tool window.")
        coveragePublished = true
    }

    private fun firstLine(message: String?): String =
        message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(200)

    private fun coverageLine(run: List<CoverageEntry>, report: CoverageReport): String {
        val ranLines = run.sumOf { it.total }
        val ranCovered = run.sumOf { it.covered }
        val parts = mutableListOf<String>()
        parts += if (ranLines > 0) {
            "this run ${percentOf(ranCovered, ranLines)}% ($ranCovered/$ranLines lines, ${run.size} files)"
        } else {
            "this run reported no lines"
        }
        report.percent?.let { parts += "all Apex files $it% (${report.covered}/${report.total} lines, ${report.files} files)" }
        report.orgWide?.let { parts += "org-wide $it%" }
        return parts.joinToString(" · ")
    }

    private fun service(name: String, vararg attributes: Pair<String, String>) {
        val body = attributes.joinToString(" ") { (key, value) -> "$key='${escape(value)}'" }
        notifyTextAvailable("##teamcity[$name $body]\n", ProcessOutputTypes.STDOUT)
    }

    private fun info(text: String) = print(text, ProcessOutputTypes.STDOUT)

    private fun error(text: String) = print(text, ProcessOutputTypes.STDERR)

    private fun print(text: String, type: Key<*>) {
        notifyTextAvailable("$text\n", type as? ProcessOutputType ?: ProcessOutputTypes.STDOUT)
    }

    private fun escape(value: String): String = buildString {
        value.forEach {
            when (it) {
                '|' -> append("||")
                '\'' -> append("|'")
                '\n' -> append("|n")
                '\r' -> append("|r")
                '[' -> append("|[")
                ']' -> append("|]")
                else -> append(it)
            }
        }
    }

    companion object {
        private const val LOG_CLOCK_SKEW_SECONDS = 120L
        private const val MAX_TEST_LOGS = 100
        private const val LOG_SEPARATOR_WIDTH = 80

        fun testNameOf(log: String, names: Set<String>): String? {
            val lower = names.associateBy { it.lowercase() }
            val units = dev.sfcloud.log.ApexLogParser.parse(log).events
                .filter { it.type == "CODE_UNIT_STARTED" || it.type == "METHOD_ENTRY" }
                .map { it.name }
            return units.firstNotNullOfOrNull { unit ->
                val candidate = unit.substringBefore('(').substringAfterLast('|').lowercase()
                lower[candidate] ?: lower.entries.firstOrNull { candidate.endsWith(".${it.key}") }?.value
            }
        }
    }

    override fun destroyProcessImpl() {
        indicator.cancel()
    }

    override fun detachProcessImpl() {
        indicator.cancel()
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}
