package dev.sfcloud

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.lang.LwcGotoDeclarationHandler
import dev.sfcloud.lang.LwcTemplate

class LwcTemplateTest : BasePlatformTestCase() {
    private val script = """
        import { LightningElement, api, track, wire } from 'lwc';

        export default class AccountCard extends LightningElement {
            @api recordId;
            @track items = [];
            @wire(getAccount, { recordId: '${'$'}recordId' })
            account;
            static delegatesFocus = true;
            label = 'Card';
            _title;

            @api
            get title() {
                return this._title;
            }
            set title(value) {
                this._title = value;
            }

            get hasItems() {
                return this.items.length > 0;
            }

            async load() {
                this.items = await fetchItems(this.recordId);
            }

            handleClick() {
                this.load();
            }
        }
    """.trimIndent()

    private fun propertyAt(template: String): String? {
        val offset = template.indexOf('|')
        return LwcTemplate.propertyAt(template.removeRange(offset, offset + 1), offset)
    }

    private fun declarationLine(name: String): List<String> =
        LwcTemplate.memberOffsets(script, name).map { offset ->
            script.substring(script.lastIndexOf('\n', offset) + 1, script.indexOf('\n', offset)).trim()
        }

    fun testPropertyAtResolvesBareAndQualifiedExpressions() {
        assertEquals("hasItems", propertyAt("""<template lwc:if={has|Items}>"""))
        assertEquals("label", propertyAt("""<p>{|label}</p>"""))
        assertEquals("label", propertyAt("""<p>{label|}</p>"""))
        assertEquals("account", propertyAt("""<p>{acc|ount.data.Name}</p>"""))
        assertEquals("items", propertyAt("""<template for:each={it|ems} for:item="item">"""))
    }

    fun testPropertyAtIgnoresNonTemplateAndHandlerPositions() {
        assertNull("A later segment of a member chain is not a component member", propertyAt("""<p>{account.da|ta}</p>"""))
        assertNull("Event handlers are left to the LWC language server", propertyAt("""<button onclick={handle|Click}>"""))
        assertNull("Plain text outside braces is not an expression", propertyAt("""<p>lab|el</p>"""))
        assertNull("Quoted attribute values are not expressions", propertyAt("""<p class="lab|el">"""))
    }

    fun testMemberOffsetsFindsFieldsGettersAndMethods() {
        assertEquals(listOf("@api recordId;"), declarationLine("recordId"))
        assertEquals(listOf("@track items = [];"), declarationLine("items"))
        assertEquals(listOf("account;"), declarationLine("account"))
        assertEquals(listOf("label = 'Card';"), declarationLine("label"))
        assertEquals(listOf("static delegatesFocus = true;"), declarationLine("delegatesFocus"))
        assertEquals(listOf("get hasItems() {"), declarationLine("hasItems"))
        assertEquals(listOf("get title() {", "set title(value) {"), declarationLine("title"))
        assertEquals(listOf("async load() {"), declarationLine("load"))
        assertEquals(emptyList<String>(), declarationLine("missing"))
        assertEquals(emptyList<String>(), declarationLine("LightningElement"))
    }

    fun testGotoDeclarationNavigatesFromTemplateToGetter() {
        myFixture.addFileToProject("force-app/main/default/lwc/accountCard/accountCard.js", script)
        val html = myFixture.addFileToProject(
            "force-app/main/default/lwc/accountCard/accountCard.html",
            """<template><template lwc:if={hasItems}><p>{label}</p></template></template>""",
        )
        myFixture.configureFromExistingVirtualFile(html.virtualFile)
        val offset = html.text.indexOf("hasItems") + 2
        myFixture.editor.caretModel.moveToOffset(offset)
        val targets = LwcGotoDeclarationHandler().getGotoDeclarationTargets(html.findElementAt(offset), offset, myFixture.editor)
        assertNotNull("The getter is a navigation target", targets)
        assertEquals(1, targets!!.size)
        assertEquals("accountCard.js", targets[0].containingFile.name)
        assertEquals("hasItems", targets[0].text)
    }
}
