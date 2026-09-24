package dev.sfcloud

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LiveTemplateTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    fun testSdExpandsToSystemDebugInApex() {
        myFixture.configureByText("Foo.cls", "public class Foo {\n    void run() {\n        sd<caret>\n    }\n}")
        expand()
        myFixture.checkResult("public class Foo {\n    void run() {\n        System.debug('<caret>');\n    }\n}")
    }

    fun testApexTemplatesDoNotLeakIntoOtherLanguages() {
        myFixture.configureByText("script.js", "function run() {\n    sd<caret>\n}")
        expand()
        myFixture.checkResult("function run() {\n    sd<caret>\n}")
    }

    fun testSdExpandsToConsoleLogInAnLwcBundle() {
        val file = myFixture.addFileToProject(
            "force-app/main/default/lwc/pinger/pinger.js",
            "export default class Pinger {\n    run() {\n        sd\n    }\n}",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("sd") + 2)
        expand()
        myFixture.checkResult(
            "export default class Pinger {\n    run() {\n        console.log('<caret>');\n    }\n}",
        )
    }

    fun testLwcTemplatesStayOutOfPlainJavaScript() {
        val file = myFixture.addFileToProject(
            "force-app/main/default/classes/helper.js",
            "export function run() {\n    sd\n}",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("sd") + 2)
        expand()
        myFixture.checkResult("export function run() {\n    sd<caret>\n}")
    }

    fun testShippedTemplateSetsAreLoaded() {
        val groups = TemplateSettings.getInstance().templates.groupBy { it.groupName }
        assertEquals(
            listOf(
                "ade", "ae", "aem", "dbi", "dbu", "fore", "fori", "forq", "ife", "ifn", "ifnn", "list", "map",
                "mapq", "mock", "prop", "psv", "runas", "sav", "sd", "sde", "sdv", "set", "soql", "sosl", "tc",
                "tm", "tryc", "tss",
            ),
            groups.getValue("SF Cloud Apex").map { it.key }.sorted(),
        )
        assertEquals(
            listOf(
                "apex", "api", "btn", "call", "card", "event", "input", "label", "lfor", "lif", "sd", "spinner",
                "toast", "track", "wire", "wirem",
            ),
            groups.getValue("SF Cloud LWC").map { it.key }.sorted(),
        )
    }

    private fun expand() = myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)
}
