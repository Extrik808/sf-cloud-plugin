package dev.sfcloud

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.lang.LwcImport

class LwcImportsTest : BasePlatformTestCase() {
    private val root = "force-app/main/default"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
    }

    private fun targetsAt(file: PsiFile, marker: String, shift: Int = 2): List<PsiElement> {
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val offset = file.text.indexOf(marker).also { assertTrue("Marker $marker is in the file", it >= 0) } + shift
        myFixture.editor.caretModel.moveToOffset(offset)
        return GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, offset).toList()
    }

    private fun lineOf(element: PsiElement): String {
        val text = element.containingFile.text
        val offset = element.textOffset
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.substring(text.lastIndexOf('\n', offset - 1) + 1, end).trim()
    }

    private fun component(): PsiFile {
        myFixture.addFileToProject(
            "$root/lwc/jsconfig.json",
            """{"compilerOptions":{"experimentalDecorators":true,"baseUrl":".","paths":{"c/*":["*"]}},"include":["**/*"]}""",
        )
        myFixture.addFileToProject(
            "other-package/main/default/lwc/dateUtils/dateUtils.js",
            """
            export const MAX_DAYS = 31;

            export function formatDate(value) {
                return String(value);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "$root/lwc/baseComponent/baseComponent.js",
            """
            import { LightningElement } from 'lwc';

            export default class BaseComponent extends LightningElement {
                showError(message) {
                    return message;
                }
            }
            """.trimIndent(),
        )
        return myFixture.addFileToProject(
            "$root/lwc/accountCard/accountCard.js",
            """
            import BaseComponent from 'c/baseComponent';
            import { formatDate, MAX_DAYS } from 'c/dateUtils';

            export default class AccountCard extends BaseComponent {
                get label() {
                    return formatDate(MAX_DAYS);
                }

                handleError() {
                    this.showError('failed');
                }
            }
            """.trimIndent(),
        )
    }

    fun testSpecifierAtReadsOnlyImportModuleStrings() {
        val text = "import x from 'c/dateUtils';\nconst y = 'c/dateUtils';\nimport('c/lazy');"
        assertEquals("c/dateUtils", LwcImport.specifierAt(text, text.indexOf("date")))
        assertNull("A plain string literal is not a module specifier", LwcImport.specifierAt(text, text.lastIndexOf("date")))
        assertEquals("c/lazy", LwcImport.specifierAt(text, text.indexOf("lazy")))
    }

    fun testDefaultImportOfFindsTheModuleOfAnImportedBinding() {
        val text = "import getRows from '@salesforce/apex/RowController.getRows';\nimport LABEL, { a } from \"@salesforce/label/c.Title\";"
        assertEquals("@salesforce/apex/RowController.getRows", LwcImport.defaultImportOf(text, "getRows"))
        assertEquals("@salesforce/label/c.Title", LwcImport.defaultImportOf(text, "LABEL"))
        assertNull(LwcImport.defaultImportOf(text, "a"))
        assertNull("A member access is not the imported binding", LwcImport.identifierAt("this.getRows()", 7))
    }

    fun testComponentImportNavigatesToTheComponentScript() {
        val targets = targetsAt(component(), "c/dateUtils", 4)
        assertEquals(listOf("dateUtils.js"), targets.map { it.containingFile.name })
    }

    fun testImportedFunctionAndConstantNavigateToTheirExports() {
        val file = component()
        val function = targetsAt(file, "formatDate(MAX_DAYS)")
        assertEquals(listOf("dateUtils.js"), function.map { it.containingFile.name })
        assertEquals("export function formatDate(value) {", lineOf(function.single()))
        val constant = targetsAt(file, "MAX_DAYS)")
        assertEquals(listOf("dateUtils.js"), constant.map { it.containingFile.name })
        assertEquals("export const MAX_DAYS = 31;", lineOf(constant.single()))
    }

    fun testInheritedMethodNavigatesIntoTheImportedBaseComponent() {
        val targets = targetsAt(component(), "showError('failed')")
        assertEquals(listOf("baseComponent.js"), targets.map { it.containingFile.name })
        assertEquals("showError(message) {", lineOf(targets.single()))
    }

    fun testSalesforceModulesNavigateToMetadata() {
        myFixture.addFileToProject(
            "$root/labels/CustomLabels.labels-meta.xml",
            "<CustomLabels>\n    <labels>\n        <fullName>Card_Title</fullName>\n    </labels>\n</CustomLabels>",
        )
        myFixture.addFileToProject("$root/objects/Account/Account.object-meta.xml", "<CustomObject/>")
        myFixture.addFileToProject(
            "$root/objects/Contact/fields/Billing_Account__c.field-meta.xml",
            "<CustomField>\n    <referenceTo>Account</referenceTo>\n</CustomField>",
        )
        myFixture.addFileToProject("$root/objects/Account/fields/Region__c.field-meta.xml", "<CustomField/>")
        myFixture.addFileToProject("$root/staticresources/charts.resource-meta.xml", "<StaticResource/>")
        myFixture.addFileToProject("$root/staticresources/charts.js", "")
        myFixture.addFileToProject("$root/messageChannels/Refresh.messageChannel-meta.xml", "<LightningMessageChannel/>")
        myFixture.addFileToProject("$root/customPermissions/Can_Edit.customPermission-meta.xml", "<CustomPermission/>")
        val js = myFixture.addFileToProject(
            "$root/lwc/card/card.js",
            """
            import TITLE from '@salesforce/label/c.Card_Title';
            import REGION from '@salesforce/schema/Contact.Billing_Account__r.Region__c';
            import CHARTS from '@salesforce/resourceUrl/charts';
            import REFRESH from '@salesforce/messageChannel/Refresh__c';
            import CAN_EDIT from '@salesforce/customPermission/Can_Edit';

            export default class Card {
                title = TITLE;
            }
            """.trimIndent(),
        )
        assertEquals("Card_Title", targetsAt(js, "c.Card_Title").single().text)
        assertEquals("Card_Title", targetsAt(js, "TITLE;").single().text)
        assertEquals("Region__c.field-meta.xml", (targetsAt(js, "Contact.Billing").single() as PsiFile).name)
        assertEquals("charts.js", (targetsAt(js, "resourceUrl/charts").single() as PsiFile).name)
        assertEquals("Refresh.messageChannel-meta.xml", (targetsAt(js, "Refresh__c").single() as PsiFile).name)
        assertEquals("Can_Edit.customPermission-meta.xml", (targetsAt(js, "Can_Edit'").single() as PsiFile).name)
    }

    fun testApexImportAndItsBindingNavigateToTheMethod() {
        myFixture.addFileToProject(
            "$root/classes/RowController.cls",
            "public with sharing class RowController {\n    @AuraEnabled\n    public static List<String> getRows() {\n        return null;\n    }\n}",
        )
        val js = myFixture.addFileToProject(
            "$root/lwc/rows/rows.js",
            "import getRows from '@salesforce/apex/RowController.getRows';\n\nexport default class Rows {\n    load() {\n        return getRows();\n    }\n}",
        )
        assertEquals("getRows", targetsAt(js, "RowController.getRows", 16).single().text)
        assertEquals("getRows", targetsAt(js, "getRows();").single().text)
    }
}
