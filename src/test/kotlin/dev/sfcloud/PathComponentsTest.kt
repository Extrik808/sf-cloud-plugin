package dev.sfcloud

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.metadata.ComponentRef
import dev.sfcloud.metadata.PathComponents

class PathComponentsTest : BasePlatformTestCase() {
    private val root = "force-app/main/default"

    private fun resolve(path: String): ComponentRef? = PathComponents.resolve(myFixture.addFileToProject("$root/$path", "").virtualFile)

    fun testContextFilesResolveToComponentsWithoutTheCli() {
        assertEquals(ComponentRef("ApexClass", "AccountService"), resolve("classes/AccountService.cls"))
        assertEquals(ComponentRef("ApexClass", "AccountService"), resolve("classes/AccountService.cls-meta.xml"))
        assertEquals(ComponentRef("ApexTrigger", "AccountTrigger"), resolve("triggers/AccountTrigger.trigger"))
        assertEquals(ComponentRef("LightningComponentBundle", "card"), resolve("lwc/card/__tests__/card.test.js"))
        assertEquals(ComponentRef("AuraDefinitionBundle", "shell"), resolve("aura/shell/shellController.js"))
        assertEquals(ComponentRef("CustomField", "Account.Region__c"), resolve("objects/Account/fields/Region__c.field-meta.xml"))
        assertEquals(ComponentRef("CustomObject", "Account"), resolve("objects/Account/Account.object-meta.xml"))
        assertEquals(ComponentRef("CustomMetadata", "Rate.Default"), resolve("customMetadata/Rate.Default.md-meta.xml"))
        assertEquals(ComponentRef("PermissionSet", "Billing"), resolve("permissionsets/Billing.permissionset-meta.xml"))
        assertEquals(ComponentRef("StaticResource", "logo"), resolve("staticresources/logo.png"))
        assertNull(resolve("labels/CustomLabels.labels-meta.xml"))
        val bundle = myFixture.addFileToProject("$root/lwc/badge/badge.js", "").virtualFile.parent
        assertEquals(ComponentRef("LightningComponentBundle", "badge"), PathComponents.resolve(bundle))
        assertNull(PathComponents.resolve(bundle.parent))
    }

    fun testFolderedFilesResolveToTheirFullFolderPath() {
        assertEquals(ComponentRef("Report", "Sales/Archive/Pipeline"), resolve("reports/Sales/Archive/Pipeline.report-meta.xml"))
        assertEquals(ComponentRef("Report", "unfiled\$public/Onboarding"), resolve("reports/unfiled\$public/Onboarding.report-meta.xml"))
        assertEquals(ComponentRef("ReportFolder", "Sales/Archive"), resolve("reports/Sales/Archive.reportFolder-meta.xml"))
        assertEquals(ComponentRef("ReportFolder", "Sales"), resolve("reports/Sales.reportFolder-meta.xml"))
        assertEquals(ComponentRef("Dashboard", "Exec/Weekly"), resolve("dashboards/Exec/Weekly.dashboard-meta.xml"))
        assertEquals(ComponentRef("EmailTemplate", "Notices/Welcome"), resolve("email/Notices/Welcome.email"))
        assertNull(resolve("classes/Pipeline.report-meta.xml"))
    }
}
