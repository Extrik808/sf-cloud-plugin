package dev.sfcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.lang.ApexMemberKind
import dev.sfcloud.lang.ApexStructure
import dev.sfcloud.ost.OstStubSet
import dev.sfcloud.ost.OstStubs

class OfflineSymbolTableTest : BasePlatformTestCase() {
    private val completions = JsonParser.parseString(
        """
        {"publicDeclarations": {
          "System": {
            "String": {"constructors": [], "properties": [], "methods": [
              {"name": "abbreviate", "returnType": "String", "isStatic": false, "parameters": [{"name": "maxWidth", "type": "Integer"}]},
              {"name": "valueOf", "returnType": "String", "isStatic": true, "parameters": [{"name": "toConvert", "type": "Object"}]}
            ]},
            "LoggingLevel": {"constructors": [], "properties": [{"name": "DEBUG"}, {"name": "ERROR"}, {"name": "void"}], "methods": [
              {"name": "values", "returnType": "List<System.LoggingLevel>", "isStatic": true, "parameters": []}
            ]},
            "Database": {"constructors": [], "properties": [], "methods": [
              {"name": "insert", "returnType": "Database.SaveResult", "isStatic": true, "parameters": [{"name": "record", "type": "SObject"}]}
            ]},
            "SObject": {"constructors": [], "properties": [], "methods": [
              {"name": "get", "returnType": "Object", "isStatic": false, "parameters": [{"name": "field", "type": "String"}]}
            ]},
            "LIST": {"constructors": [], "properties": [], "methods": [
              {"name": "size", "returnType": "Integer", "isStatic": false, "parameters": []}
            ]}
          },
          "Database": {
            "SaveResult": {"constructors": [], "properties": [], "methods": [
              {"name": "isSuccess", "returnType": "Boolean", "isStatic": false, "parameters": []},
              {"name": "getErrors", "returnType": "List<Database.Error>", "isStatic": false, "parameters": []}
            ]},
            "Error": {"constructors": [], "properties": [], "methods": [
              {"name": "getMessage", "returnType": "String", "isStatic": false, "parameters": []}
            ]}
          },
          "AppFramework": {
            "TemplateTaskContext": {"constructors": [], "properties": [], "methods": [
              {"name": "getAction", "returnType": "AppFramework.TemplateTaskContext.Action", "isStatic": false, "parameters": []}
            ]},
            "Action": {"constructors": [], "properties": [{"name": "CREATE"}], "methods": [
              {"name": "values", "returnType": "List<AppFramework.TemplateTaskContext.Action>", "isStatic": true, "parameters": []}
            ]}
          }
        }}
        """.trimIndent(),
    ).asJsonObject

    private fun describe(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private val sObjects = listOf(
        describe(
            """
            {"name": "Account",
             "fields": [
               {"name": "Id", "soapType": "tns:ID"},
               {"name": "Name", "soapType": "xsd:string"},
               {"name": "AnnualRevenue", "soapType": "xsd:double"},
               {"name": "OwnerId", "soapType": "tns:ID", "relationshipName": "Owner", "referenceTo": ["User"]},
               {"name": "WhatId", "soapType": "tns:ID", "relationshipName": "What", "referenceTo": ["Account", "Opportunity"]}
             ],
             "childRelationships": [{"childSObject": "Contact", "relationshipName": "Contacts"}, {"childSObject": "Task", "relationshipName": null}]}
            """.trimIndent(),
        ),
        describe("""{"name": "User", "fields": [{"name": "Name", "soapType": "xsd:string"}], "childRelationships": []}"""),
    )

    private fun stubs(): OstStubSet = OstStubs.render(completions, sObjects)

    private fun members(stubs: OstStubSet, file: String) =
        ApexStructure.parse(stubs.files.getValue(file)).single()

    fun testSystemTypesWithKeywordNamesParseWithTheirMembers() {
        val string = members(stubs(), "String")
        assertEquals(ApexMemberKind.CLASS, string.kind)
        assertEquals("String", string.name)
        assertEquals(listOf("abbreviate", "valueOf"), string.children.map { it.name })
        assertTrue(string.children.single { it.name == "valueOf" }.isStatic)
        assertEquals("String", string.children.single { it.name == "abbreviate" }.type)
        assertEquals("size", members(stubs(), "List").children.single().name)
    }

    fun testEnumsKeepTheirConstantsAndDropReservedNames() {
        val level = members(stubs(), "LoggingLevel")
        assertEquals(ApexMemberKind.ENUM, level.kind)
        assertEquals(listOf("DEBUG", "ERROR"), level.children.map { it.name })
    }

    fun testNamespacesMergeWithSameNamedSystemClassesAndNestTheirTypes() {
        val database = members(stubs(), "Database")
        val insert = database.children.single { it.name == "insert" }
        assertEquals(ApexMemberKind.METHOD, insert.kind)
        assertEquals("Database.SaveResult", insert.type)
        assertEquals(listOf("SaveResult", "Error"), database.children.filter { it.kind.isType }.map { it.name })
        val appFramework = members(stubs(), "AppFramework")
        val context = appFramework.children.single { it.name == "TemplateTaskContext" }
        assertEquals(listOf("TemplateTaskContext"), appFramework.children.filter { it.kind.isType }.map { it.name })
        assertEquals(ApexMemberKind.ENUM, context.children.single { it.name == "Action" }.kind)
    }

    fun testSObjectsExtendSObjectWithTypedFieldsAndRelationships() {
        val stubs = stubs()
        assertEquals(listOf("Account", "User"), stubs.sObjects)
        assertFalse("Account" in stubs.types)
        assertTrue(stubs.types.containsAll(listOf("String", "Database", "AppFramework", "Object", "Trigger")))
        assertTrue(stubs.files.getValue("Account").startsWith("global class Account extends SObject {"))
        val fields = members(stubs, "Account").children.associate { it.name to it.type }
        assertEquals(
            mapOf(
                "Id" to "Id", "Name" to "String", "AnnualRevenue" to "Decimal", "OwnerId" to "Id", "Owner" to "User",
                "WhatId" to "Id", "What" to "SObject", "Contacts" to "List<Contact>",
            ),
            fields,
        )
    }

    fun testTriggerContextVariablesAreStatic() {
        val trigger = members(stubs(), "Trigger")
        assertTrue(trigger.children.all { it.isStatic })
        assertContainsElements(trigger.children.map { it.name }, "new", "old", "newMap", "operationType")
    }

    fun testCompletionWalksSymbolTableTypesAndRelationshipChains() {
        myFixture.addFileToProject("sfdx-project.json", """{"packageDirectories":[{"path":"force-app","default":true}]}""")
        stubs().files.forEach { (name, text) -> myFixture.addFileToProject("ost/$name.cls", text) }

        assertContainsElements(complete("String label = 'x';\n        label.<caret>"), "abbreviate", "valueOf")
        assertDoesntContain(complete("String.<caret>"), "abbreviate")
        assertContainsElements(complete("String.<caret>"), "valueOf")
        assertContainsElements(complete("Account account = new Account();\n        account.<caret>"), "Name", "Owner", "Contacts", "get")
        assertContainsElements(complete("Account account = new Account();\n        account.Owner.<caret>"), "Name")
        assertContainsElements(complete("Database.<caret>"), "insert", "SaveResult")
        assertContainsElements(complete("Database.SaveResult result = null;\n        result.<caret>"), "isSuccess", "getErrors")
        assertContainsElements(complete("LoggingLevel.<caret>"), "DEBUG", "ERROR")
        assertContainsElements(complete("Trigger.<caret>"), "new", "newMap", "isInsert")
    }

    private fun complete(body: String): List<String> {
        myFixture.configureByText("Probe.cls", "public class Probe {\n    public void run() {\n        $body\n    }\n}")
        return myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    }
}
