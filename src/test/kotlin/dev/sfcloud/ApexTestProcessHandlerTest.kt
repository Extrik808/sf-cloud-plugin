package dev.sfcloud

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.settings.SfCloudSettings
import dev.sfcloud.tests.ApexCoverageProgramRunner
import dev.sfcloud.tests.ApexCoverageService
import dev.sfcloud.tests.ApexTestConfigurationType
import dev.sfcloud.tests.ApexTestProcessHandler
import dev.sfcloud.tests.ApexTestRunConfiguration
import dev.sfcloud.tests.ApexTestRunState
import dev.sfcloud.tests.CoverageSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ApexTestProcessHandlerTest : BasePlatformTestCase() {
    private lateinit var workDir: Path
    private var previousSfPath: String? = null

    override fun setUp() {
        super.setUp()
        workDir = Files.createTempDirectory("sf-cloud-test")
        previousSfPath = SfCloudSettings.getInstance().state.sfPath
    }

    override fun tearDown() {
        try {
            SfCloudSettings.getInstance().state.sfPath = previousSfPath
            workDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun fakeSf(resource: String, exitCode: Int): Path {
        val json = workDir.resolve("output.json")
        javaClass.getResourceAsStream(resource)!!.use { Files.copy(it, json) }
        val args = workDir.resolve("args.txt")
        val script = workDir.resolve("sf")
        Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '$args'\ncat '$json'\nexit $exitCode\n")
        Files.writeString(args, "")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        SfCloudSettings.getInstance().state.sfPath = script.toString()
        return args
    }

    private fun configuration(classNames: String, method: String = "", org: String = "Scratch1"): ApexTestRunConfiguration {
        val factory = ConfigurationTypeUtil.findConfigurationType(ApexTestConfigurationType::class.java).configurationFactories.first()
        val configuration = factory.createTemplateConfiguration(project) as ApexTestRunConfiguration
        configuration.options.classNames = classNames
        configuration.options.methodName = method
        configuration.options.targetOrg = org
        return configuration
    }

    private fun run(configuration: ApexTestRunConfiguration, coverage: Boolean = true): Pair<String, Int> {
        val handler = ApexTestProcessHandler(configuration, coverage)
        val output = StringBuffer()
        var exitCode = -1
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                exitCode = event.exitCode
            }
        })
        handler.startNotify()
        assertTrue("Test run did not finish", handler.waitFor(30_000))
        return output.toString() to exitCode
    }

    fun testRealSfOutputBecomesServiceMessagesAndCoverage() {
        val args = fakeSf("/sf/apex-run-test.json", 100)
        val (output, exitCode) = run(configuration("MyProfilePageControllerTest"))

        val passed = "aRejectedSaveKeepsTheUserInEditModeAndReportsTheReason"
        val failed = "changePasswordRoutesToTheStandardChangePasswordPage"
        assertEquals(1, exitCode)
        assertTrue(output, output.contains("##teamcity[testSuiteStarted name='MyProfilePageControllerTest' locationHint='apex_test://MyProfilePageControllerTest']"))
        assertTrue(output, output.contains("##teamcity[testFinished name='$passed' duration='187']"))
        assertTrue(output, output.contains("##teamcity[testFailed name='$failed' message='System.AssertException: Assertion Failed: expected |[1|], actual |[0|]'"))
        assertFalse(output, output.contains("testFailed name='$passed'"))
        assertTrue(output, output.contains("Code coverage: this run 92% (22/24 lines, 1 files)"))
        assertTrue(output, output.contains("org-wide 76%"))

        val sentArgs = Files.readAllLines(args)
        assertEquals(listOf("apex", "run", "test"), sentArgs.take(3))
        assertTrue(sentArgs.toString(), sentArgs.containsAll(listOf("--class-names", "MyProfilePageControllerTest", "--code-coverage", "--target-org", "Scratch1", "--json")))

        val file = myFixture.configureByText("MyProfilePageController.cls", "public class MyProfilePageController {}").virtualFile
        val service = ApexCoverageService.getInstance(project)
        val coverage = service.coverageFor(file)!!
        assertEquals(setOf(21, 36), coverage.uncovered)
        assertEquals(92, coverage.percent)

        val report = service.report!!
        val row = report.rows.single { it.name == "MyProfilePageController" }
        assertEquals(92, row.percent)
        assertEquals(CoverageSource.RUN, row.source)
        assertEquals(76, report.orgWide)
        assertEquals(22, report.covered)
        assertEquals(24, report.total)
    }

    fun testPlainRunSkipsCoverage() {
        val args = fakeSf("/sf/apex-run-test.json", 100)
        ApexCoverageService.getInstance(project).clear()
        run(configuration("MyProfilePageControllerTest"), coverage = false)

        assertFalse(Files.readAllLines(args).contains("--code-coverage"))
        val file = myFixture.configureByText("MyProfilePageController.cls", "public class MyProfilePageController {}").virtualFile
        assertNull(ApexCoverageService.getInstance(project).coverageFor(file))
        assertNull("A plain run leaves the Code Coverage window empty", ApexCoverageService.getInstance(project).report)
    }

    fun testPlainRunKeepsTheLastCoverageRunUntouched() {
        fakeSf("/sf/apex-run-test.json", 100)
        run(configuration("MyProfilePageControllerTest"))
        val service = ApexCoverageService.getInstance(project)
        val report = service.report!!
        val lastRun = service.lastRun
        val (output, _) = run(configuration("MyProfilePageControllerTest"), coverage = false)
        assertFalse(output, output.contains("Code coverage:"))
        assertSame(report, service.report)
        assertSame(lastRun, service.lastRun)
    }

    fun testCoverageRunnerHandlesOnlyApexTestsUnderCoverageExecutor() {
        val runner = ApexCoverageProgramRunner()
        val configuration = configuration("MyProfilePageControllerTest")
        assertTrue(runner.canRun(ApexTestRunState.COVERAGE_EXECUTOR_ID, configuration))
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration))
        assertTrue(ExecutorRegistry.getInstance().getExecutorById(ApexTestRunState.COVERAGE_EXECUTOR_ID) != null)
    }

    fun testLogsAreMatchedToTheirTestMethods() {
        val log = "64.0 APEX_CODE,FINEST\n" +
            "09:00:00.1 (1)|CODE_UNIT_STARTED|[EXTERNAL]|01p000000000001|MyProfilePageControllerTest.savesProfile()\n" +
            "09:00:00.1 (2)|CODE_UNIT_FINISHED|MyProfilePageControllerTest.savesProfile()\n"
        val names = setOf("MyProfilePageControllerTest.savesProfile", "MyProfilePageControllerTest.other")
        assertEquals("MyProfilePageControllerTest.savesProfile", ApexTestProcessHandler.testNameOf(log, names))
        assertNull(ApexTestProcessHandler.testNameOf("09:00:00.1 (1)|CODE_UNIT_STARTED|[EXTERNAL]|execute_anonymous_apex", names))
    }

    fun testMultipleMethodsAndClassesAreSentAsTests() {
        val args = fakeSf("/sf/apex-run-test.json", 100)
        val configuration = configuration("OtherTest")
        configuration.options.testMethods = "MyProfilePageControllerTest.a,MyProfilePageControllerTest.b"
        run(configuration)
        val sentArgs = Files.readAllLines(args)
        assertFalse(sentArgs.contains("--class-names"))
        assertEquals(listOf("OtherTest", "MyProfilePageControllerTest.a", "MyProfilePageControllerTest.b"),
            sentArgs.withIndex().filter { it.index > 0 && sentArgs[it.index - 1] == "--tests" }.map { it.value })
    }

    fun testAllTestsIgnoresTheSelection() {
        val args = fakeSf("/sf/apex-run-test.json", 100)
        val configuration = configuration("OtherTest")
        configuration.options.allTests = true
        run(configuration)
        val sentArgs = Files.readAllLines(args)
        assertTrue(sentArgs.containsAll(listOf("--test-level", "RunLocalTests")))
        assertEquals("All Tests", configuration.suggestedName())
    }

    fun testSingleMethodUsesTestsFlag() {
        val args = fakeSf("/sf/apex-run-test.json", 100)
        run(configuration("MyProfilePageControllerTest", method = "changePasswordRoutesToTheStandardChangePasswordPage"))
        val sentArgs = Files.readAllLines(args)
        val index = sentArgs.indexOf("--tests")
        assertTrue(sentArgs.toString(), index >= 0)
        assertEquals("MyProfilePageControllerTest.changePasswordRoutesToTheStandardChangePasswordPage", sentArgs[index + 1])
        assertFalse(sentArgs.contains("--class-names"))
    }

    fun testCliErrorWithoutTestsIsReportedAndFails() {
        fakeSf("/sf/error.json", 1)
        val (output, exitCode) = run(configuration(""))
        assertEquals(1, exitCode)
        assertTrue(output, output.contains("No org configuration found for name Scratch1"))
        assertFalse(output, output.contains("##teamcity[testStarted"))
    }
}
