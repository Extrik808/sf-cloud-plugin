package dev.sfcloud

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.lang.ApexNamedElement
import dev.sfcloud.lang.ApexReferenceElement
import dev.sfcloud.lang.ApexUsages

class ApexResolveTest : BasePlatformTestCase() {
    fun testInstanceCallResolvesThroughTheLocalVariableType() {
        myFixture.addFileToProject(
            "force-app/main/default/classes/Util.cls",
            "public class Util {\n    public void ping() {}\n}",
        )
        val caller = myFixture.configureByText(
            "Caller.cls",
            "public class Caller {\n    void run() {\n        Util helper = new Util();\n        helper.ping();\n    }\n}",
        )
        val target = resolve(caller, caller.text.indexOf("ping();"))!!
        assertEquals("Util.cls", target.containingFile.name)
        assertEquals("ping", target.text)
    }

    fun testInheritedAndInnerMembersResolveWithoutTheLanguageServer() {
        myFixture.addFileToProject(
            "force-app/main/default/classes/Base.cls",
            "public virtual class Base {\n    protected void shared() {}\n}",
        )
        val child = myFixture.configureByText(
            "Child.cls",
            "public class Child extends Base {\n    public class Inner {\n        public Integer size;\n    }\n" +
                "    void run() {\n        shared();\n        Inner box = new Inner();\n        box.size = 1;\n    }\n}",
        )
        assertEquals("Base.cls", resolve(child, child.text.indexOf("shared();"))!!.containingFile.name)
        assertEquals(child.text.indexOf("Inner {"), resolve(child, child.text.indexOf("Inner box"))!!.textRange.startOffset)
        assertEquals(child.text.indexOf("size;"), resolve(child, child.text.indexOf("size = 1"))!!.textRange.startOffset)
    }

    fun testLocalVariablesDoNotResolveToFieldsOfTheSameName() {
        val file = myFixture.configureByText(
            "Shadow.cls",
            "public class Shadow {\n    private Integer total;\n    void run() {\n        Integer total = 0;\n        total = 1;\n    }\n}",
        )
        assertNull(resolve(file, file.text.indexOf("total = 1")))
    }

    fun testClassUsagesSpanApexLightningAndMetadata() {
        val util = myFixture.addFileToProject(
            "force-app/main/default/classes/Util.cls",
            "public class Util {\n    @AuraEnabled\n    public static void ping() {}\n}",
        )
        myFixture.addFileToProject(
            "force-app/main/default/classes/Caller.cls",
            "public class Caller {\n    void run() { Util.ping(); }\n}",
        )
        myFixture.addFileToProject(
            "force-app/main/default/lwc/pinger/pinger.js",
            "import ping from '@salesforce/apex/Util.ping';\nexport default { ping };\n",
        )
        myFixture.addFileToProject(
            "force-app/main/default/flows/Ping.flow-meta.xml",
            "<Flow><actionCalls><apexClass>Util</apexClass></actionCalls></Flow>",
        )
        val type = declaration(util, "Util")
        assertEquals(
            setOf("Caller.cls", "pinger.js", "Ping.flow-meta.xml"),
            usageFiles(type),
        )
        val method = declaration(util, "ping")
        assertEquals(setOf("Caller.cls", "pinger.js"), usageFiles(method))
        assertEquals("2 usages", ApexUsages.hint(ApexUsages.count(method)))
    }

    private fun usageFiles(target: ApexNamedElement): Set<String> =
        ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
            .findAll()
            .map { it.element.containingFile.name }
            .toSet()

    private fun declaration(file: PsiFile, name: String): ApexNamedElement =
        PsiTreeUtil.collectElements(file) { it is ApexNamedElement && it.text == name }.first() as ApexNamedElement

    private fun resolve(file: PsiFile, offset: Int): PsiElement? =
        (file.findElementAt(offset)?.parent as? ApexReferenceElement)?.reference?.resolve()
}
