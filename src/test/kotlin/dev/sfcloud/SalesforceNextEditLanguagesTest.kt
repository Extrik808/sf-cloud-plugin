package dev.sfcloud

import com.intellij.lang.Language
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.lang.ApexLanguage
import dev.sfcloud.lang.SoqlLanguage
import dev.sfcloud.lang.SoslLanguage
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class SalesforceNextEditLanguagesTest : BasePlatformTestCase() {
    private fun registrations(): List<Element> {
        val stream = javaClass.getResourceAsStream("/META-INF/sfcloud-ai-assistant.xml")
        assertNotNull("sfcloud-ai-assistant.xml is missing", stream)
        val document = stream!!.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val nodes = document.getElementsByTagName("ml.llm.nextEdits.backend.languageSettingsGroup.affiliation")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun testApexSoqlAndSoslJoinTheSalesforceGroup() {
        val languages = registrations().map { it.getAttribute("language") }.toSet()
        assertEquals(setOf(ApexLanguage.id, SoqlLanguage.id, SoslLanguage.id), languages)
        languages.forEach { assertNotNull("Language $it is not registered", Language.findLanguageByID(it)) }
    }

    fun testEveryAffiliationPointsAtTheGroupClass() {
        registrations().forEach {
            assertEquals("dev.sfcloud.ai.SalesforceNextEditLanguages", it.getAttribute("groupImplementationClass"))
        }
    }

    fun testTheConfigLoadsOnlyWithAiAssistant() {
        val plugin = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use { String(it.readAllBytes()) }
        assertTrue(plugin.contains("""<depends optional="true" config-file="sfcloud-ai-assistant.xml">com.intellij.ml.llm</depends>"""))
    }
}
