package dev.sfcloud

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.lang.ApexDocumentationProvider
import dev.sfcloud.lang.ApexParameterInfoHandler
import dev.sfcloud.lang.ApexReferenceElement
import dev.sfcloud.lang.ApexSignatures
import dev.sfcloud.lang.SalesforceSchema
import dev.sfcloud.lang.SoqlCaret
import dev.sfcloud.lang.SoqlContext
import dev.sfcloud.repl.ReplSources
import dev.sfcloud.repl.SoqlQueryKind
import dev.sfcloud.repl.SoslQueryKind

class SoqlCompletionTest : BasePlatformTestCase() {
    private val root = "force-app/main/default"

    private fun caret(query: String): SoqlCaret {
        val offset = query.indexOf('|')
        return SoqlContext.at(query.removeRange(offset, offset + 1), offset)
    }

    private fun schema() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
        myFixture.addFileToProject("$root/objects/Account/Account.object-meta.xml", "<CustomObject/>")
        myFixture.addFileToProject("$root/objects/Account/fields/Region__c.field-meta.xml", "<CustomField>\n    <type>Text</type>\n</CustomField>")
        myFixture.addFileToProject(
            "$root/objects/Account/fields/Primary_Contact__c.field-meta.xml",
            "<CustomField>\n    <type>Lookup</type>\n    <referenceTo>Contact</referenceTo>\n    <relationshipName>Primary_Accounts</relationshipName>\n</CustomField>",
        )
        myFixture.addFileToProject("$root/objects/Contact/Contact.object-meta.xml", "<CustomObject/>")
        myFixture.addFileToProject("$root/objects/Contact/fields/Badge__c.field-meta.xml", "<CustomField>\n    <type>Checkbox</type>\n</CustomField>")
    }

    fun testCaretAfterFromOffersObjects() {
        assertEquals("Acc", (caret("SELECT Id FROM Acc|") as SoqlCaret.Objects).prefix)
        assertTrue(caret("FIND {x} IN ALL FIELDS RETURNING Acc|") is SoqlCaret.Objects)
        assertTrue(caret("FIND {x} RETURNING Account(Id), Con|") is SoqlCaret.Objects)
    }

    fun testCaretInSelectListOffersFieldsOfTheQueriedObjectEvenBeforeFromIsTyped() {
        val fields = caret("SELECT Id, Na| FROM Account WHERE Id != null") as SoqlCaret.Fields
        assertEquals("Na", fields.prefix)
        assertEquals(listOf("Account"), fields.scope)
        assertEquals(emptyList<String>(), fields.path)
        val where = caret("SELECT Id FROM Account WHERE Name = 'a' AND Reg|") as SoqlCaret.Fields
        assertEquals(listOf("Account"), where.scope)
        val order = caret("SELECT Id FROM Account ORDER BY Cr|") as SoqlCaret.Fields
        assertEquals(listOf("Account"), order.scope)
    }

    fun testRelationshipPathIsCollectedBeforeTheCaret() {
        val fields = caret("SELECT Owner.Profile.Na| FROM Account") as SoqlCaret.Fields
        assertEquals("Na", fields.prefix)
        assertEquals(listOf("Owner", "Profile"), fields.path)
    }

    fun testParentChildSubqueryUsesTheChildRelationshipChain() {
        val inner = caret("SELECT Id, (SELECT La| FROM Contacts) FROM Account") as SoqlCaret.Fields
        assertEquals(listOf("Account", "Contacts"), inner.scope)
        val from = caret("SELECT Id, (SELECT Id FROM Con|) FROM Account") as SoqlCaret.ChildRelationships
        assertEquals(listOf("Account"), from.scope)
        val outer = caret("SELECT Id, (SELECT Id FROM Contacts), Na| FROM Account") as SoqlCaret.Fields
        assertEquals(listOf("Account"), outer.scope)
    }

    fun testSemiJoinSubqueryIsAnIndependentQuery() {
        val fields = caret("SELECT Id FROM Account WHERE Id IN (SELECT AccountId FROM Contact WHERE La|)") as SoqlCaret.Fields
        assertEquals(listOf("Contact"), fields.scope)
        assertTrue(caret("SELECT Id FROM Account WHERE Id IN (SELECT AccountId FROM Con|)") is SoqlCaret.Objects)
    }

    fun testSoslReturningListOffersFieldsOfItsObject() {
        val fields = caret("FIND {Acme} IN ALL FIELDS RETURNING Account(Id, Na|), Contact(Id)") as SoqlCaret.Fields
        assertEquals(listOf("Account"), fields.scope)
        val second = caret("FIND {Acme} RETURNING Account(Id), Contact(Id WHERE La|)") as SoqlCaret.Fields
        assertEquals(listOf("Contact"), second.scope)
    }

    fun testBindVariablesAndLimitsAreNotFieldPositions() {
        assertTrue(caret("SELECT Id FROM Account WHERE Id = :acc|") is SoqlCaret.Bind)
        assertTrue(caret("SELECT Id FROM Account LIMIT |") is SoqlCaret.Keywords)
        assertTrue(caret("SELECT Id FROM Account WHERE Name = 'FROM x' AND |") is SoqlCaret.Fields)
    }

    private fun keywords(query: String): List<String> = when (val caret = caret(query)) {
        is SoqlCaret.Keywords -> caret.keywords
        is SoqlCaret.Fields -> caret.keywords
        else -> fail("Expected keywords for $query, got $caret") as Nothing
    }

    fun testKeywordsFollowTheSoqlGrammar() {
        assertEquals(listOf("SELECT", "FIND"), keywords("|"))
        assertEquals(listOf("FROM"), keywords("SELECT Id, Name |"))
        assertTrue(keywords("SELECT |").containsAll(listOf("COUNT()", "FIELDS()", "TYPEOF")))
        val afterFrom = keywords("SELECT Id FROM Account |")
        assertEquals("USING SCOPE", afterFrom.first())
        assertContainsElements(afterFrom, "WHERE", "WITH", "GROUP BY", "ORDER BY", "LIMIT", "OFFSET", "FOR UPDATE", "ALL ROWS")
        assertFalse("HAVING needs GROUP BY", "HAVING" in afterFrom)
        assertEquals(listOf("LIKE", "IN", "NOT IN", "INCLUDES", "EXCLUDES"), keywords("SELECT Id FROM Account WHERE Name |"))
        assertContainsElements(keywords("SELECT Id FROM Account WHERE CreatedDate > |"), "TODAY", "LAST_N_DAYS:", "NULL")
        val afterValue = keywords("SELECT Id FROM Account WHERE Name = 'x' |")
        assertContainsElements(afterValue, "AND", "OR", "ORDER BY", "LIMIT")
        assertFalse("WHERE cannot repeat", "WHERE" in afterValue)
        assertEquals(listOf("AND", "OR"), keywords("SELECT Id FROM Account WHERE (Name = 'x' |)"))
        assertContainsElements(keywords("SELECT Id FROM Account WHERE Id = :recordId |"), "AND", "OR")
        assertContainsElements(keywords("SELECT Id FROM Account WHERE Name LIKE 'a%' AND |"), "NOT")
        assertEquals(listOf("IN"), keywords("SELECT Id FROM Account WHERE Id NOT |"))
        assertEquals(listOf("BY"), keywords("SELECT Id FROM Account ORDER |"))
        assertContainsElements(keywords("SELECT Id FROM Account ORDER BY Name |"), "ASC", "DESC", "NULLS LAST", "LIMIT")
        assertEquals(listOf("FIRST", "LAST"), keywords("SELECT Id FROM Account ORDER BY Name DESC NULLS |"))
        assertContainsElements(keywords("SELECT Industry FROM Account GROUP BY Industry |"), "HAVING", "ORDER BY", "LIMIT")
        assertEquals(emptyList<String>(), keywords("SELECT Id FROM Account LIMIT |"))
        assertEquals(listOf("OFFSET", "FOR VIEW", "FOR REFERENCE", "FOR UPDATE", "ALL ROWS"), keywords("SELECT Id FROM Account LIMIT 10 |"))
        assertContainsElements(keywords("SELECT Id FROM Account WITH |"), "SECURITY_ENFORCED", "USER_MODE")
        assertEquals(listOf("SCOPE"), keywords("SELECT Id FROM Account USING |"))
        assertEquals(listOf("ALL", "STANDARD", "CUSTOM"), keywords("SELECT FIELDS(|) FROM Account"))
        assertEquals(listOf("SELECT"), keywords("SELECT Id FROM Account WHERE Id IN (|)"))
    }

    fun testTypeofBlocksFollowTheirOwnGrammar() {
        assertEquals(listOf("WHEN"), keywords("SELECT TYPEOF What |"))
        assertTrue(caret("SELECT TYPEOF What WHEN Acc|") is SoqlCaret.Objects)
        assertEquals(listOf("THEN"), keywords("SELECT TYPEOF What WHEN Account |"))
        assertEquals(listOf("WHEN", "ELSE", "END"), keywords("SELECT TYPEOF What WHEN Account THEN Name |"))
        assertEquals(listOf("FROM"), keywords("SELECT TYPEOF What WHEN Account THEN Name END |"))
    }

    fun testKeywordsFollowTheSoslGrammar() {
        assertEquals(emptyList<String>(), keywords("FIND |"))
        assertContainsElements(keywords("FIND {Acme} |"), "IN ALL FIELDS", "RETURNING", "LIMIT")
        assertContainsElements(keywords("FIND {Acme} IN |"), "ALL FIELDS", "NAME FIELDS")
        assertContainsElements(keywords("FIND {Acme} IN ALL FIELDS |"), "RETURNING")
        assertContainsElements(keywords("FIND {Acme} RETURNING Account |"), "WITH", "LIMIT")
        assertEquals(listOf("WHERE", "ORDER BY", "LIMIT", "OFFSET"), keywords("FIND {Acme} RETURNING Account(Id |)"))
    }

    fun testSchemaMergesLocalFieldsRelationshipsAndStandardFields() {
        schema()
        val account = SalesforceSchema.sObject(project, "account")!!
        assertEquals("Account", account.name)
        assertEquals("String", account.field("Region__c")!!.type)
        assertEquals("Id", account.field("Primary_Contact__c")!!.type)
        assertEquals("Contact", account.parent("Primary_Contact__r")!!.target)
        assertNotNull(account.field("Id"))
        assertEquals("Boolean", SalesforceSchema.traverse(project, "Account", listOf("Primary_Contact__r"))!!.field("Badge__c")!!.type)
        assertNull(SalesforceSchema.sObject(project, "Nope__c"))
        assertContainsElements(SalesforceSchema.objectNames(project), "Account", "Contact")
    }

    fun testSoqlFileCompletionOffersFieldsAndObjects() {
        schema()
        myFixture.configureByText("accounts.soql", "SELECT <caret> FROM Account")
        val select = myFixture.completeBasic().map { it.lookupString }
        assertContainsElements(select, "Region__c", "Primary_Contact__r", "Id", "COUNT", "TYPEOF")
        assertFalse("Clauses are not offered inside the select list", "WHERE" in select)
        myFixture.configureByText("paths.soql", "SELECT Primary_Contact__r.<caret> FROM Account")
        val related = myFixture.completeBasic().map { it.lookupString }
        assertContainsElements(related, "Badge__c")
        assertFalse("Keywords are not offered after a relationship dot", "WHERE" in related)
        myFixture.configureByText("clause.soql", "SELECT Id FROM Account WH<caret>")
        myFixture.completeBasic()
        assertEquals("SELECT Id FROM Account WHERE ", myFixture.editor.document.text)
        myFixture.configureByText("objects.soql", "SELECT Id FROM Con<caret>")
        myFixture.completeBasic()
        assertEquals("SELECT Id FROM Contact", myFixture.editor.document.text)
    }

    fun testInlineApexSoqlCompletesFieldsAndBindsCompleteLocals() {
        schema()
        myFixture.configureByText(
            "Cart.cls",
            """
            public class Cart {
                public void run(String region) {
                    List<Account> rows = [SELECT Id, <caret> FROM Account];
                }
            }
            """.trimIndent(),
        )
        val variants = myFixture.completeBasic().map { it.lookupString }
        assertContainsElements(variants, "Region__c", "Primary_Contact__r")
        assertFalse("Apex keywords are not offered inside SOQL", "virtual" in variants)
        myFixture.configureByText(
            "Binds.cls",
            """
            public class Binds {
                public void run(String region) {
                    List<Account> rows = [SELECT Id FROM Account WHERE Region__c = :reg<caret>];
                }
            }
            """.trimIndent(),
        )
        myFixture.completeBasic()
        assertTrue(myFixture.editor.document.text.contains(":region]"))
    }

    fun testApexSObjectVariableCompletesLocalSchemaFields() {
        schema()
        myFixture.configureByText(
            "Cart.cls",
            """
            public class Cart {
                public void run() {
                    Account acc = new Account();
                    acc.<caret>
                }
            }
            """.trimIndent(),
        )
        assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "Region__c", "Primary_Contact__r")
    }

    fun testAnonymousApexCompletesProjectClassesAndSchema() {
        schema()
        myFixture.addFileToProject("$root/classes/AccountService.cls", "public class AccountService {\n    public static void sync(Id accountId, Boolean force) {}\n}")
        myFixture.configureByText("script.apex", "Acc<caret>")
        assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "AccountService", "Account")
    }

    fun testLwcSchemaImportCompletesFieldsThroughRelationships() {
        schema()
        myFixture.configureByText("card.js", "import FIELD from '@salesforce/schema/Account.Primary_Contact__r.<caret>';")
        assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "@salesforce/schema/Account.Primary_Contact__r.Badge__c")
    }

    fun testParameterInfoListsOverloadsAndTracksTheArgument() {
        schema()
        myFixture.addFileToProject(
            "$root/classes/AccountService.cls",
            """
            public class AccountService {
                public static void sync(Id accountId, Map<String, Object> options) {}
                public static void sync(Id accountId) {}
            }
            """.trimIndent(),
        )
        val file = myFixture.configureByText("script.apex", "AccountService.sync(recordId, new Map<String, Object>{'a' => 1}, <caret>")
        val handler = ApexParameterInfoHandler()
        val overloads = handler.overloadsAt(file, myFixture.caretOffset)
        assertEquals(setOf("Id accountId, Map<String, Object> options", "Id accountId"), overloads.map { it.parameters }.toSet())
        assertEquals(2, handler.argumentAt(file, myFixture.caretOffset))
        assertEquals(listOf("Id accountId", "Map<String, Object> options"), ApexSignatures.parameters("Id accountId, Map<String, Object> options"))
    }

    fun testQuickDocumentationShowsTheDeclarationAndDocComment() {
        schema()
        myFixture.addFileToProject(
            "$root/classes/AccountService.cls",
            """
            public class AccountService {
                /**
                 * Pushes the account to the billing system.
                 */
                public static void sync(Id accountId) {}
            }
            """.trimIndent(),
        )
        val file = myFixture.configureByText("script.apex", "AccountService.sy<caret>nc(null);")
        val reference = file.findElementAt(myFixture.caretOffset)!!.parent as ApexReferenceElement
        val target = reference.reference.resolve()
        val doc = ApexDocumentationProvider().generateDoc(target, reference)!!
        assertTrue(doc, doc.contains("static void sync(Id accountId)"))
        assertTrue(doc, doc.contains("Pushes the account to the billing system."))
        assertTrue(doc, doc.contains("AccountService.cls"))
    }

    fun testQueriesAreFoundInApexBracketsAndStringLiterals() {
        val apex = "List<Account> rows = [\n    SELECT Id FROM Account WHERE Name = :name\n];"
        assertEquals("SELECT Id FROM Account WHERE Name = :name", ReplSources.queryAt(apex, apex.indexOf("FROM"), true))
        val dynamic = "Database.query('SELECT Id FROM Contact LIMIT 5');"
        assertEquals("SELECT Id FROM Contact LIMIT 5", ReplSources.queryAt(dynamic, dynamic.indexOf("Contact"), false))
        val js = "const q = `FIND {Acme} RETURNING Account`;"
        assertEquals("FIND {Acme} RETURNING Account", ReplSources.queryAt(js, js.indexOf("Acme"), false))
        assertNull(ReplSources.queryAt("String s = 'hello';", 13, true))
        assertSame(SoslQueryKind, ReplSources.route(SoqlQueryKind, "  find {x} RETURNING Account"))
        assertSame(SoqlQueryKind, ReplSources.route(SoslQueryKind, "SELECT Id FROM Account"))
        assertTrue(ReplSources.hasBinds("SELECT Id FROM Account WHERE Id = :recordId"))
        assertFalse(ReplSources.hasBinds("SELECT Id FROM Account WHERE Name = 'a:b' AND CreatedDate = LAST_N_DAYS:5"))
    }
}
