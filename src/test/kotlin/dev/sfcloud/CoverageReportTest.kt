package dev.sfcloud

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.tests.CoverageEntry
import dev.sfcloud.tests.CoverageFile
import dev.sfcloud.tests.CoverageReports
import dev.sfcloud.tests.CoverageSource
import junit.framework.TestCase

class CoverageReportTest : TestCase() {
    fun testEveryProjectFileGetsARowAndTheWorstCoverageComesFirst() {
        val files = listOf(
            CoverageFile("AccountService.cls", "/p/AccountService.cls"),
            CoverageFile("RiskReview.cls", "/p/RiskReview.cls"),
            CoverageFile("LegacyBatch.cls", "/p/LegacyBatch.cls"),
            CoverageFile("AccountTrigger.trigger", "/p/AccountTrigger.trigger"),
        )
        val run = listOf(CoverageEntry("AccountService", 19, 20), CoverageEntry("RiskReview", 36, 50))
        val org = listOf(CoverageEntry("LegacyBatch", 0, 84), CoverageEntry("RiskReview", 10, 50))
        val report = CoverageReports.build(files, run, org, 78)

        assertEquals(
            listOf("LegacyBatch.cls", "RiskReview.cls", "AccountService.cls", "AccountTrigger.trigger"),
            report.rows.map { it.name },
        )
        assertEquals(listOf(0, 72, 95, null), report.rows.map { it.percent })
        assertEquals(
            listOf(CoverageSource.ORG, CoverageSource.RUN, CoverageSource.RUN, CoverageSource.NONE),
            report.rows.map { it.source },
        )
    }

    fun testTotalsCountOnlyFilesWithData() {
        val files = listOf(
            CoverageFile("A.cls", "/p/A.cls"),
            CoverageFile("B.cls", "/p/B.cls"),
            CoverageFile("NoData.cls", "/p/NoData.cls"),
        )
        val report = CoverageReports.build(
            files,
            listOf(CoverageEntry("A", 5, 10)),
            listOf(CoverageEntry("B", 45, 90)),
            78,
        )
        assertEquals(50, report.covered)
        assertEquals(100, report.total)
        assertEquals(2, report.files)
        assertEquals(50, report.percent)
        assertEquals(78, report.orgWide)
    }

    fun testCoveredClassWithoutALocalFileStillCounts() {
        val report = CoverageReports.build(
            listOf(CoverageFile("A.cls", "/p/A.cls")),
            listOf(CoverageEntry("A", 1, 1), CoverageEntry("PackagedHelper", 3, 4)),
            emptyList(),
            null,
        )
        val packaged = report.rows.single { it.name == "PackagedHelper" }
        assertNull(packaged.path)
        assertEquals(75, packaged.percent)
        assertEquals(5, report.total)
        assertNull(report.orgWide)
    }

    fun testEmptyProjectAndNoCoverageGiveNoPercent() {
        val report = CoverageReports.build(emptyList(), emptyList(), emptyList(), null)
        assertTrue(report.rows.isEmpty())
        assertNull(report.percent)
        assertEquals(0, report.files)
    }
}

class CoverageScanTest : BasePlatformTestCase() {
    fun testScanFindsApexFilesOfEveryPackageDirectory() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
        myFixture.addFileToProject("force-app/main/default/classes/Util.cls", "public class Util {}")
        myFixture.addFileToProject("force-app/main/default/classes/Util.cls-meta.xml", "<ApexClass/>")
        myFixture.addFileToProject("force-app/main/default/triggers/AccountTrigger.trigger", "trigger AccountTrigger on Account (before insert) {}")
        myFixture.addFileToProject("force-app/main/default/lwc/pinger/pinger.js", "export default class Pinger {}")
        assertEquals(
            listOf("AccountTrigger.trigger", "Util.cls"),
            CoverageReports.scan(project).map { it.name }.sorted(),
        )
    }
}
