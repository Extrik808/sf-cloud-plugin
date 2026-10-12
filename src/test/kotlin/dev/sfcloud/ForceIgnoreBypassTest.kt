package dev.sfcloud

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.deploy.ForceIgnoreBypass
import java.io.File

class ForceIgnoreBypassTest : BasePlatformTestCase() {
    private lateinit var project: File
    private lateinit var packageDirectory: File

    override fun setUp() {
        super.setUp()
        project = FileUtil.createTempDirectory("sf-cloud-project", null, true)
        packageDirectory = File(project, "payjunction-base")
    }

    private fun write(path: String, text: String = path): File = File(packageDirectory, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    fun testStagesAForceIgnoredReportUnderItsPackagePath() {
        val report = write("main/default/reports/unfiled\$public/UW_SLA_Report.report-meta.xml")
        val workspace = FileUtil.createTempDirectory("sf-cloud-staged", null, true)

        val staged = ForceIgnoreBypass.stage(listOf(report), listOf(packageDirectory), workspace, "62.0")

        val copy = File(workspace, "force-app/main/default/reports/unfiled\$public/UW_SLA_Report.report-meta.xml")
        assertEquals(listOf(copy), staged.sourceDirs)
        assertEquals(report.readText(), copy.readText())
        assertEquals(
            """{"packageDirectories":[{"path":"force-app","default":true}],"sourceApiVersion":"62.0"}""",
            File(workspace, "sfdx-project.json").readText(),
        )
        assertEquals(FileUtil.toSystemIndependentName(report.path), staged.original(copy.path))
    }

    fun testStagesTheMetaFileNextToASourceFile() {
        val source = write("main/default/classes/Service.cls")
        val meta = write("main/default/classes/Service.cls-meta.xml")
        val workspace = FileUtil.createTempDirectory("sf-cloud-staged", null, true)

        val staged = ForceIgnoreBypass.stage(listOf(source), listOf(packageDirectory), workspace, null)

        val stagedMeta = File(workspace, "force-app/main/default/classes/Service.cls-meta.xml")
        assertTrue(stagedMeta.isFile)
        assertEquals(FileUtil.toSystemIndependentName(meta.path), staged.original(stagedMeta.path))
        assertFalse(File(workspace, "sfdx-project.json").readText().contains("sourceApiVersion"))
    }

    fun testMapsFilesInsideAStagedDirectoryBackToTheProject() {
        write("main/default/reports/Sales/Pipeline.report-meta.xml")
        val folder = File(packageDirectory, "main/default/reports/Sales")
        val workspace = FileUtil.createTempDirectory("sf-cloud-staged", null, true)

        val staged = ForceIgnoreBypass.stage(listOf(folder), listOf(packageDirectory), workspace, null)

        val stagedReport = File(staged.sourceDirs.single(), "Pipeline.report-meta.xml")
        assertTrue(stagedReport.isFile)
        assertEquals(
            FileUtil.toSystemIndependentName(File(folder, "Pipeline.report-meta.xml").path),
            staged.original(stagedReport.path),
        )
        assertEquals("/elsewhere/Other.cls", staged.original("/elsewhere/Other.cls"))
    }

    fun testRecognisesTheSfMessageForAPackageWithNoSource() {
        assertTrue(ForceIgnoreBypass.isExcluded("No source-backed components present in the package."))
        assertFalse(ForceIgnoreBypass.isExcluded("Deploy failed."))
    }
}
