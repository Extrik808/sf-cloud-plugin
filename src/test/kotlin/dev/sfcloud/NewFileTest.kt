package dev.sfcloud

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.metadata.NewFileTemplates
import dev.sfcloud.metadata.NewFiles
import dev.sfcloud.metadata.SfFileKind
import dev.sfcloud.metadata.SfNewFileOptions
import junit.framework.TestCase

class NewFileTemplatesTest : TestCase() {
    fun testApexClassComesWithItsMetadataFile() {
        val files = NewFileTemplates.files(SfFileKind.APEX_CLASS, "AccountService", SfNewFileOptions(), "64.0")
        assertEquals(listOf("AccountService.cls", "AccountService.cls-meta.xml"), files.map { it.path })
        assertEquals("public with sharing class AccountService {\n}\n", files[0].content)
        assertTrue(files[1].content, files[1].content.contains("<ApexClass xmlns=\"http://soap.sforce.com/2006/04/metadata\">"))
        assertTrue(files[1].content, files[1].content.contains("<apiVersion>64.0</apiVersion>"))
    }

    fun testApexTestClassIsAnIsTestSkeleton() {
        val files = NewFileTemplates.files(SfFileKind.APEX_TEST_CLASS, "AccountServiceTest", SfNewFileOptions(), "64.0")
        assertEquals(
            "@IsTest\nprivate class AccountServiceTest {\n    @IsTest\n    static void itWorks() {\n    }\n}\n",
            files[0].content,
        )
    }

    fun testTriggerUsesTheSObjectAndTheSelectedEvents() {
        val options = SfNewFileOptions(sobject = "Account", events = listOf("before insert", "after update"))
        val files = NewFileTemplates.files(SfFileKind.APEX_TRIGGER, "AccountTrigger", options, "64.0")
        assertEquals(listOf("AccountTrigger.trigger", "AccountTrigger.trigger-meta.xml"), files.map { it.path })
        assertEquals("trigger AccountTrigger on Account (before insert, after update) {\n}\n", files[0].content)
    }

    fun testLwcBundleIsCreatedWithOptionalFiles() {
        val bare = NewFileTemplates.files(SfFileKind.LWC, "pinger", SfNewFileOptions(), "64.0")
        assertEquals(listOf("pinger/pinger.js", "pinger/pinger.html", "pinger/pinger.js-meta.xml"), bare.map { it.path })
        assertTrue(bare[0].content, bare[0].content.contains("export default class Pinger extends LightningElement {}"))
        assertTrue(bare[2].content, bare[2].content.contains("<isExposed>false</isExposed>"))
        assertFalse(bare[2].content, bare[2].content.contains("<targets>"))

        val full = NewFileTemplates.files(
            SfFileKind.LWC,
            "pingerCard",
            SfNewFileOptions(exposed = true, withCss = true, withTest = true),
            "64.0",
        )
        assertEquals(
            listOf(
                "pingerCard/pingerCard.js",
                "pingerCard/pingerCard.html",
                "pingerCard/pingerCard.js-meta.xml",
                "pingerCard/pingerCard.css",
                "pingerCard/__tests__/pingerCard.test.js",
            ),
            full.map { it.path },
        )
        assertTrue(full[2].content, full[2].content.contains("<target>lightning__RecordPage</target>"))
        assertTrue(full[4].content, full[4].content.contains("createElement('c-pinger-card', { is: PingerCard })"))
    }

    fun testAuraBundleFollowsTheCheckboxes() {
        val files = NewFileTemplates.files(
            SfFileKind.AURA,
            "pinger",
            SfNewFileOptions(withController = true, withHelper = true, withCss = true),
            "64.0",
        )
        assertEquals(
            listOf(
                "pinger/pinger.cmp",
                "pinger/pinger.cmp-meta.xml",
                "pinger/pingerController.js",
                "pinger/pingerHelper.js",
                "pinger/pinger.css",
            ),
            files.map { it.path },
        )
        assertEquals(".THIS {\n}\n", files[4].content)
    }

    fun testVisualforcePageFallsBackToTheNameAsLabel() {
        val files = NewFileTemplates.files(SfFileKind.VISUALFORCE_PAGE, "AccountView", SfNewFileOptions(), "64.0")
        assertEquals(listOf("AccountView.page", "AccountView.page-meta.xml"), files.map { it.path })
        assertEquals("<apex:page>\n</apex:page>\n", files[0].content)
        assertTrue(files[1].content, files[1].content.contains("<label>AccountView</label>"))
    }

    fun testElementAndClassNames() {
        assertEquals("c-pinger-card", NewFileTemplates.elementName("pingerCard"))
        assertEquals("PingerCard", NewFileTemplates.className("pingerCard"))
    }
}

class NewFileCreationTest : BasePlatformTestCase() {
    fun testApexClassLandsInTheExistingClassesFolderOfThePackageDirectory() {
        sfdxProject()
        myFixture.addFileToProject("force-app/main/default/classes/Existing.cls", "public class Existing {}")
        val created = NewFiles.create(project, SfFileKind.APEX_CLASS, "AccountService", SfNewFileOptions(), null)!!
        assertEquals("AccountService.cls", created.name)
        assertTrue(created.path, created.path.endsWith("force-app/main/default/classes/AccountService.cls"))
        assertNotNull(created.parent.findChild("AccountService.cls-meta.xml"))
        assertEquals("public with sharing class AccountService {\n}\n", VfsUtilCore.loadText(created))
    }

    fun testLwcBundleIsCreatedWithEveryFileAndIsReportedAsExisting() {
        sfdxProject()
        val options = SfNewFileOptions(withCss = true, withTest = true)
        val created = NewFiles.create(project, SfFileKind.LWC, "pinger", options, null)!!
        assertEquals("pinger.js", created.name)
        val bundle = created.parent
        assertTrue(bundle.path, bundle.path.endsWith("force-app/main/default/lwc/pinger"))
        assertEquals(
            listOf("__tests__", "pinger.css", "pinger.html", "pinger.js", "pinger.js-meta.xml"),
            bundle.children.map { it.name }.sorted(),
        )
        assertTrue(NewFiles.exists(project, SfFileKind.LWC, "pinger", null))
        assertFalse(NewFiles.exists(project, SfFileKind.LWC, "other", null))
    }

    fun testApiVersionComesFromSfdxProjectJson() {
        sfdxProject(apiVersion = "62.0")
        assertEquals("62.0", NewFileTemplates.apiVersion(project))
        val created = NewFiles.create(project, SfFileKind.APEX_TRIGGER, "AccountTrigger", SfNewFileOptions(sobject = "Account"), null)!!
        val meta = created.parent.findChild("AccountTrigger.trigger-meta.xml")!!
        assertTrue(VfsUtilCore.loadText(meta).contains("<apiVersion>62.0</apiVersion>"))
    }

    fun testFilesAreCreatedWhereTheActionWasInvoked() {
        sfdxProject()
        val nested = myFixture.addFileToProject("force-app/main/default/classes/services/Existing.cls", "public class Existing {}").virtualFile
        val service = NewFiles.create(project, SfFileKind.APEX_CLASS, "Billing", SfNewFileOptions(), nested)!!
        assertTrue(service.path, service.path.endsWith("force-app/main/default/classes/services/Billing.cls"))
        val folder = nested.parent
        val trigger = NewFiles.create(project, SfFileKind.APEX_TRIGGER, "BillingTrigger", SfNewFileOptions(sobject = "Account"), folder)!!
        assertTrue(trigger.path, trigger.path.endsWith("force-app/main/default/triggers/BillingTrigger.trigger"))
        val bundleFile = myFixture.addFileToProject("force-app/main/default/lwc/card/card.js", "").virtualFile
        val sibling = NewFiles.create(project, SfFileKind.LWC, "badge", SfNewFileOptions(), bundleFile)!!
        assertTrue(sibling.path, sibling.path.endsWith("force-app/main/default/lwc/badge/badge.js"))
        val custom = myFixture.addFileToProject("force-app/main/billing/README.md", "").virtualFile.parent
        val plain = NewFiles.create(project, SfFileKind.APEX_CLASS, "Invoices", SfNewFileOptions(), custom)!!
        assertTrue(plain.path, plain.path.endsWith("force-app/main/billing/Invoices.cls"))
        val component = NewFiles.create(project, SfFileKind.LWC, "invoiceList", SfNewFileOptions(), custom)!!
        assertTrue(component.path, component.path.endsWith("force-app/main/billing/lwc/invoiceList/invoiceList.js"))
        assertTrue(NewFiles.exists(project, SfFileKind.APEX_CLASS, "Invoices", custom))
        assertFalse(NewFiles.exists(project, SfFileKind.APEX_CLASS, "Invoices", null))
    }

    private fun sfdxProject(apiVersion: String? = null) {
        val version = apiVersion?.let { ""","sourceApiVersion":"$it"""" }.orEmpty()
        myFixture.addFileToProject(
            "sfdx-project.json",
            """{"packageDirectories":[{"path":"force-app","default":true}]$version}""",
        )
        myFixture.addFileToProject("force-app/main/default/labels/CustomLabels.labels-meta.xml", "<CustomLabels/>")
    }
}
