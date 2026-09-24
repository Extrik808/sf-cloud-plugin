package dev.sfcloud

import com.google.gson.JsonParser
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.UIUtil
import dev.sfcloud.log.ApexLogLevel
import dev.sfcloud.log.LogAnalyzerPanel
import dev.sfcloud.log.LogCategory
import dev.sfcloud.log.LogCategoryKey
import dev.sfcloud.log.LogLevelPreset
import dev.sfcloud.log.LogLevels
import dev.sfcloud.log.LogLevelsPanel
import dev.sfcloud.repl.QueryResultsPanel
import javax.swing.JComboBox
import javax.swing.JComponent
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.core.objects
import dev.sfcloud.lang.ApexFile
import dev.sfcloud.lang.ApexFileType
import dev.sfcloud.lang.ApexTokens
import dev.sfcloud.lang.ApexTriggerFileType
import dev.sfcloud.lang.ApexMember
import dev.sfcloud.lang.ApexMethodNavigationOffsetProvider
import dev.sfcloud.log.ApexLogFileType
import dev.sfcloud.log.ApexLogMethodNavigationOffsetProvider
import dev.sfcloud.org.OrgService
import dev.sfcloud.settings.SfCloudProjectSettings
import dev.sfcloud.tests.ApexCoverageService
import dev.sfcloud.tests.ApexTestLocator
import dev.sfcloud.tests.ApexTestRunConfiguration
import dev.sfcloud.tests.ApexTestRunLineMarkerContributor
import dev.sfcloud.tests.ApexTestTarget

class ApexPlatformTest : BasePlatformTestCase() {
    private val testClass = """
        @IsTest
        private class AccountServiceTest {
            @TestSetup
            static void setup() {
                insert new Account(Name = 'A');
            }

            @IsTest
            static void loadsAccounts() {
                List<Account> accounts = AccountService.load('A');
                System.assertEquals(1, accounts.size());
            }

            @IsTest(SeeAllData=true)
            static void seesOrgData() {
                helper();
            }

            static testMethod void legacyStyle() {
                if (true) { helper(); }
            }

            private static void helper() {
            }
        }
    """.trimIndent()

    fun testClsAndTriggerFilesUseApexLanguage() {
        val cls = myFixture.configureByText("Foo.cls", "public class Foo {}")
        assertInstanceOf(cls, ApexFile::class.java)
        assertEquals(ApexFileType, cls.virtualFile.fileType)
        val trigger = myFixture.configureByText("FooTrigger.trigger", "trigger FooTrigger on Account (before insert) {}")
        assertEquals(ApexTriggerFileType, trigger.virtualFile.fileType)
    }

    fun testOnlyTestClassAndTestMethodsAreRunTargets() {
        val file = myFixture.configureByText("AccountServiceTest.cls", testClass)
        val targets = PsiTreeUtil.collectElements(file) { it.elementType == ApexTokens.IDENTIFIER }
            .mapNotNull { ApexTestTarget.fromElement(it) }
            .map { it.displayName }
        assertEquals(
            listOf(
                "AccountServiceTest",
                "AccountServiceTest.loadsAccounts",
                "AccountServiceTest.seesOrgData",
                "AccountServiceTest.legacyStyle",
            ),
            targets,
        )
    }

    fun testLineMarkersAppearOnlyOnRunTargets() {
        val file = myFixture.configureByText("AccountServiceTest.cls", testClass)
        val contributor = ApexTestRunLineMarkerContributor()
        val marked = PsiTreeUtil.collectElements(file) { contributor.getInfo(it) != null }.map { it.text }
        assertEquals(listOf("AccountServiceTest", "loadsAccounts", "seesOrgData", "legacyStyle"), marked)
    }

    fun testNonTestClassHasNoRunTargets() {
        val file = myFixture.configureByText("AccountService.cls", "public class AccountService { static void loadsAccounts() {} }")
        val targets = PsiTreeUtil.collectElements(file) { ApexTestTarget.fromElement(it) != null }
        assertEmpty(targets)
    }

    fun testRunConfigurationIsCreatedForMethodUnderCaret() {
        myFixture.configureByText("AccountServiceTest.cls", testClass.replace("seesOrgData() {\n        helper", "seesOrgData() {\n        hel<caret>per"))
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val configurations = ConfigurationContext(element).configurationsFromContext.orEmpty()
            .map { it.configuration }
            .filterIsInstance<ApexTestRunConfiguration>()
        assertEquals(1, configurations.size)
        assertEquals(listOf("AccountServiceTest"), configurations.single().classes)
        assertEquals("seesOrgData", configurations.single().method)
        val runner = com.intellij.execution.runners.ProgramRunner.getRunner(
            com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
            configurations.single(),
        )
        assertNotNull("No program runner can execute Apex test configurations", runner)
    }

    fun testLocatorResolvesClassAndMethod() {
        myFixture.addFileToProject("classes/AccountServiceTest.cls", testClass)
        val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        val method = ApexTestLocator.getLocation(ApexTestLocator.PROTOCOL, "AccountServiceTest/loadsAccounts", project, scope)
        assertEquals("loadsAccounts", method.single().psiElement.text)
        val cls = ApexTestLocator.getLocation(ApexTestLocator.PROTOCOL, "AccountServiceTest", project, scope)
        assertInstanceOf(cls.single().psiElement, ApexFile::class.java)
    }

    fun testCoverageIsParsedFromSfJson() {
        val json = JsonParser.parseString(
            """[{"name":"AccountService","totalLines":3,"lines":{"8":1,"10":0,"11":2},"totalCovered":2,"coveredPercent":67}]""",
        )
        val service = ApexCoverageService.getInstance(project)
        service.update(json.objects())
        val file = myFixture.configureByText("AccountService.cls", "public class AccountService {}").virtualFile
        val coverage = service.coverageFor(file)!!
        assertEquals(setOf(8, 11), coverage.covered)
        assertEquals(setOf(10), coverage.uncovered)
        assertEquals(67, coverage.percent)
        service.clear()
        assertNull(service.coverageFor(file))
    }

    fun testToolWindowOrgOverridesTheProjectOrg() {
        val state = SfCloudProjectSettings.getInstance(project).state
        val previous = state.targetOrg
        try {
            state.targetOrg = "ProjectOrg"
            val service = OrgService.getInstance(project)
            assertEquals(listOf("--target-org", "Scratch1"), service.orgArgs("Scratch1"))
            assertEquals(listOf("--target-org", "ProjectOrg"), service.orgArgs(""))
            assertEquals(listOf("--target-org", "ProjectOrg"), service.orgArgs(null))
            assertEquals("Scratch1", service.orgLabel(" Scratch1".trim()))
            assertEquals("ProjectOrg", service.orgLabel(""))
            state.targetOrg = ""
            assertEquals("default org", service.orgLabel(null))
            assertTrue(service.orgArgs(null).isEmpty())
        } finally {
            state.targetOrg = previous
        }
    }

    fun testStructureViewAndMethodNavigation() {
        val file = myFixture.configureByText(
            "Sample.cls",
            "public class Sample {\n    public Integer count;\n    public void first() {}\n    private static String second(Integer x) { return ''; }\n}",
        )
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("return"))
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(myFixture.editor)
        try {
            val classNode = model.root.children.single()
            assertEquals("Sample", classNode.presentation.presentableText)
            assertEquals(
                listOf("count: Integer", "first(): void", "second(Integer x): String"),
                classNode.children.map { it.presentation.presentableText },
            )
            assertEquals("second", (model.currentEditorElement as ApexMember).name)
        } finally {
            Disposer.dispose(model)
        }

        val offsets = ApexMethodNavigationOffsetProvider().getMethodNavigationOffsets(file, 0)!!.toList()
        assertEquals(listOf(0, file.text.indexOf("public Integer"), file.text.indexOf("public void"), file.text.indexOf("private static")), offsets)
    }

    fun testReferencesResolveThroughTheFileStructure() {
        myFixture.addFileToProject("force-app/main/default/classes/Util.cls", "public class Util {\n    public static void ping() {}\n}")
        val file = myFixture.configureByText(
            "Caller.cls",
            "public class Caller {\n    void run() { local(); Util.ping(); }\n    void local() {}\n}",
        )
        assertEquals(file.text.lastIndexOf("local"), reference(file, file.text.indexOf("local()"))!!.textRange.startOffset)
        val ping = reference(file, file.text.indexOf("ping"))!!
        assertEquals("Util.cls", ping.containingFile.name)
        assertEquals("ping", ping.text)
        assertEquals("Util", reference(file, file.text.indexOf("Util"))!!.text)
    }

    private fun reference(file: com.intellij.psi.PsiFile, offset: Int): com.intellij.psi.PsiElement? =
        (file.findElementAt(offset)?.parent as? dev.sfcloud.lang.ApexReferenceElement)?.reference?.resolve()

    fun testLogStructureFollowsTheExecutionTree() {
        val text = javaClass.getResourceAsStream("/sf/apex-debug.log")!!.bufferedReader().readText()
        val file = myFixture.configureByText(ApexLogFileType, text)
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(myFixture.editor)
        try {
            val execution = model.root.children.single()
            val unit = execution.children.single()
            assertTrue(unit.presentation.presentableText!!.startsWith("execute_anonymous_apex"))
            assertEquals(listOf(2, 4), unit.children.map { it.children.size })
        } finally {
            Disposer.dispose(model)
        }
        val offsets = ApexLogMethodNavigationOffsetProvider().getMethodNavigationOffsets(file, 0)!!
        assertTrue(offsets.size >= 6)
    }

    fun testLogAnalyzerPanelRendersRawAndTreeViews() {
        val text = javaClass.getResourceAsStream("/sf/apex-debug.log")!!.bufferedReader().readText()
        val holder = Disposer.newDisposable()
        try {
            val panel = LogAnalyzerPanel(project, holder, { null })
            panel.show("sample", text)
            assertEquals(3, panel.log!!.count(LogCategory.SOQL) + panel.log!!.count(LogCategory.DML))
            val tabs = panel.component as JBTabbedPane
            tabs.selectedIndex = 1
            val treeTable = UIUtil.findComponentOfType(tabs.getComponentAt(1) as JComponent, TreeTable::class.java)!!
            assertTrue(treeTable.rowCount > 3)
            assertEquals(listOf("Event", "Details", "Timestamp"), (0 until treeTable.columnCount).map { treeTable.getColumnName(it) })
            tabs.selectedIndex = 0
            panel.clear()
            assertNull(panel.log)
        } finally {
            Disposer.dispose(holder)
        }
    }

    fun testLogLevelsPanelKeepsPresetAndCategoriesInSync() {
        val changes = mutableListOf<LogLevels>()
        val panel = LogLevelsPanel(LogLevels.DEFAULT) { changes += it }
        val combos = UIUtil.findComponentsOfType(panel, JComboBox::class.java)
        assertEquals(12, combos.size)
        combos[0].selectedItem = LogLevelPreset.TRACING
        assertEquals(LogLevelPreset.TRACING.levels, panel.levels)
        combos[1].selectedItem = ApexLogLevel.ERROR
        assertNull(combos[0].selectedItem)
        assertEquals(ApexLogLevel.ERROR, changes.last()[LogCategoryKey.APEX_CODE])
        panel.setLevels(LogLevels.DEFAULT)
        assertEquals(LogLevelPreset.DEFAULT, combos[0].selectedItem)
        assertEquals(2, changes.size)
    }

    fun testQueryResultsPanelShowsTableAndTree() {
        val holder = Disposer.newDisposable()
        try {
            val panel = QueryResultsPanel(project, holder, "SF Cloud SOQL Query", { null }, 0.7f) {}
            val records = listOf(
                JsonParser.parseString("""{"attributes":{"type":"Account"},"Id":"001000000000001AAA","Name":"Acme"}""").asJsonObject,
            )
            val rows = records.map { QueryResultsPanel.flatten(it) }
            panel.show(records, listOf("Id", "Name"), rows)
            assertTrue(panel.hasResults)
            val table = UIUtil.findComponentOfType(panel.component, JBTable::class.java)!!
            assertEquals(1, table.rowCount)
            assertEquals("Acme", table.getValueAt(0, 1))
            panel.clear()
            assertFalse(panel.hasResults)
        } finally {
            Disposer.dispose(holder)
        }
    }

    fun testDeployTargetsBundleForLwcAndSourceForMetaXml() {
        val js = myFixture.addFileToProject("force-app/main/default/lwc/card/card.js", "").virtualFile
        assertEquals("card", SfdxProject.deployTarget(js).name)
        val meta = myFixture.addFileToProject("force-app/main/default/classes/Foo.cls-meta.xml", "<x/>").virtualFile
        myFixture.addFileToProject("force-app/main/default/classes/Foo.cls", "public class Foo {}")
        assertEquals("Foo.cls", SfdxProject.deployTarget(meta).name)
        val field = myFixture.addFileToProject("force-app/main/default/objects/A__c/fields/B__c.field-meta.xml", "<x/>").virtualFile
        assertEquals(field, SfdxProject.deployTarget(field))
    }
}
