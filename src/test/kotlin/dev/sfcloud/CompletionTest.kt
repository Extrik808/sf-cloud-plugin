package dev.sfcloud

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.lang.ApexCaret
import dev.sfcloud.lang.ApexLocals
import dev.sfcloud.lang.LwcCaret
import dev.sfcloud.lang.LwcComponent
import dev.sfcloud.lang.LwcScript

class CompletionTest : BasePlatformTestCase() {
    private val script = """
        import { LightningElement, api } from 'lwc';

        export default class AccountCard extends LightningElement {
            @api recordId;
            @api
            get cardTitle() {
                return this._title;
            }
            label = 'Card';

            handleClick() {
                this.label = 'clicked';
            }
        }
    """.trimIndent()

    private fun caret(template: String): LwcCaret {
        val offset = template.indexOf('|')
        return LwcCaret.at(template.removeRange(offset, offset + 1), offset)
    }

    fun testTemplateCaretTellsTagAttributeAndExpressionApart() {
        assertEquals("light", (caret("<template><light|") as LwcCaret.TagName).prefix)
        val attribute = caret("""<c-account-card record|""") as LwcCaret.Attribute
        assertEquals("record", attribute.prefix)
        assertEquals("c-account-card", attribute.tag)
        assertEquals("lab", (caret("<p>{lab|</p>") as LwcCaret.Expression).prefix)
        assertEquals("rec", (caret("""<c-account-card record-id={rec|""") as LwcCaret.Expression).prefix)
        assertTrue(caret("""<p class="head|">""") is LwcCaret.Nowhere)
    }

    fun testScriptMembersSeparateApiPropertiesFromTheRest() {
        assertEquals(listOf("cardTitle", "recordId", "label", "handleClick"), LwcScript.members(script).map { it.name })
        assertEquals(listOf("cardTitle", "recordId"), LwcScript.apiMembers(script))
        assertEquals("card-title", LwcComponent.kebab("cardTitle"))
        assertEquals("c-account-card", LwcComponent(myFixture.tempDirFixture.findOrCreateDir("accountCard")).tag)
    }

    fun testApexCaretFindsTheQualifierBeforeTheDot() {
        val plain = ApexCaret.at("Account a = new Acc", 19)
        assertEquals("Acc", plain.prefix)
        assertTrue(plain is ApexCaret.Plain)
        val qualified = ApexCaret.at("System.deb", 10) as ApexCaret.Qualified
        assertEquals("System", qualified.qualifier)
        assertEquals("deb", qualified.prefix)
        assertTrue(ApexCaret.at("List<Account> a = [SELECT Id FROM Acc", 36) is ApexCaret.Soql)
        assertTrue(ApexCaret.at("String s = 'x'; Integer i", 24) is ApexCaret.Plain)
    }

    fun testApexLocalsAreCollectedFromTheEnclosingMethod() {
        val file = myFixture.addFileToProject(
            "force-app/main/default/classes/Cart.cls",
            """
            public class Cart {
                public void run(Id recordId) {
                    List<Account> accounts = new List<Account>();
                    Map<Id, Contact> byId = new Map<Id, Contact>();
                    Integer total = 0;
                    total++;
                }

                public void other() {
                    String only = 'x';
                }
            }
            """.trimIndent(),
        )
        val offset = file.text.indexOf("total++")
        val locals = ApexLocals.inScope(file, offset).associate { it.name to it.type }
        assertEquals(setOf("recordId", "accounts", "byId", "total"), locals.keys)
        assertEquals("Id", locals["recordId"])
        assertEquals("List", locals["accounts"])
        assertTrue("A local of another method is out of scope", "only" !in locals)
    }

    fun testApexCompletionOffersLocalsMembersAndProjectClasses() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
        myFixture.addFileToProject("force-app/main/default/classes/AccountService.cls", "public class AccountService {}")
        myFixture.configureByText(
            "Cart.cls",
            """
            public class Cart {
                private Integer counter;

                public void run() {
                    Integer subtotal = 0;
                    <caret>
                }
            }
            """.trimIndent(),
        )
        val variants = myFixture.completeBasic().map { it.lookupString }
        assertContainsElements(variants, "subtotal", "counter", "run", "AccountService", "System", "Database")
    }

    fun testApexCompletionAfterADotOffersTheQualifierMembers() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
        myFixture.configureByText(
            "Cart.cls",
            """
            public class Cart {
                public void run() {
                    System.<caret>
                }
            }
            """.trimIndent(),
        )
        assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "debug", "assertEquals")
    }

    fun testLwcTemplateCompletionOffersComponentsDirectivesAndMembers() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
        myFixture.addFileToProject("force-app/main/default/lwc/accountCard/accountCard.js", script)
        myFixture.addFileToProject("force-app/main/default/lwc/accountCard/accountCard.html", "<template></template>")
        val host = myFixture.addFileToProject(
            "force-app/main/default/lwc/cartPage/cartPage.html",
            "<template>\n    <c-account-card ></c-account-card>\n    <p>{}</p>\n</template>",
        )
        myFixture.addFileToProject("force-app/main/default/lwc/cartPage/cartPage.js", script)
        myFixture.configureFromExistingVirtualFile(host.virtualFile)

        myFixture.editor.caretModel.moveToOffset(host.text.indexOf("<c-account-card") + 1)
        assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "c-account-card", "lightning-card", "template")

        myFixture.configureFromExistingVirtualFile(host.virtualFile)
        myFixture.editor.caretModel.moveToOffset(host.text.indexOf("></c-account-card>"))
        assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "record-id", "card-title", "lwc:if", "onclick")

        myFixture.configureFromExistingVirtualFile(host.virtualFile)
        myFixture.editor.caretModel.moveToOffset(host.text.indexOf("{}") + 1)
        assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "label", "recordId", "handleClick")
    }

    fun testSalesforceModuleCompletionOffersApexClassesAndMethods() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
        myFixture.addFileToProject(
            "force-app/main/default/classes/AccountService.cls",
            """
            public with sharing class AccountService {
                @AuraEnabled(cacheable=true)
                public static List<Account> load(String name) {
                    return null;
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("force-app/main/default/classes/CartService.cls", "public class CartService {}")
        myFixture.configureByText("cart.js", """import load from '@salesforce/apex/<caret>';""")
        assertContainsElements(
            myFixture.completeBasic().map { it.lookupString },
            "@salesforce/apex/AccountService",
            "@salesforce/apex/CartService",
        )

        myFixture.configureByText("cart.js", """import load from '@salesforce/apex/AccountService.<caret>';""")
        assertNull("A single method is completed right away", myFixture.completeBasic())
        assertTrue(myFixture.file.text.contains("@salesforce/apex/AccountService.load"))
    }

    fun testComponentModuleCompletionOffersProjectComponentsInImports() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true},{"path":"other"}]}""")
        myFixture.addFileToProject("force-app/main/default/lwc/dateUtils/dateUtils.js", "export const A = 1;")
        myFixture.addFileToProject("other/main/default/lwc/accountCard/accountCard.js", "export default class AccountCard {}")
        myFixture.addFileToProject("other/main/default/lwc/accountCard/accountCard.html", "<template></template>")
        val cart = myFixture.addFileToProject("force-app/main/default/lwc/cart/cart.js", "import { A } from 'c/<caret>';")
        myFixture.configureFromExistingVirtualFile(cart.virtualFile)
        val variants = myFixture.completeBasic().map { it.lookupString }
        assertContainsElements(variants, "c/dateUtils", "c/accountCard")
        assertDoesntContain(variants, "c/cart")

        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject("force-app/main/default/lwc/cart/cartHelper.js", "import A from 'c/date<caret>';").virtualFile,
        )
        assertNull("A single component is completed right away", myFixture.completeBasic())
        assertTrue(myFixture.file.text.contains("'c/dateUtils'"))
    }

    fun testComponentModuleCompletionStaysOutOfPlainStrings() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
        myFixture.addFileToProject("force-app/main/default/lwc/dateUtils/dateUtils.js", "export const A = 1;")
        myFixture.configureByText("cart.js", "const name = 'c/<caret>';")
        assertDoesntContain(myFixture.completeBasic()?.map { it.lookupString }.orEmpty(), "c/dateUtils")
    }
}
