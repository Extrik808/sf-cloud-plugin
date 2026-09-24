package dev.sfcloud

import dev.sfcloud.lang.ApexMember
import dev.sfcloud.lang.ApexMemberKind
import dev.sfcloud.lang.ApexStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexStructureTest {
    private val source = """
        @IsTest
        public with sharing class AccountService implements Queueable {
            private static final String PREFIX = 'acc';
            public static Map<Id, Account> cache = new Map<Id, Account>();
            public Integer total { get; private set; }
            public String label {
                get { return PREFIX + total; }
            }

            public AccountService(Integer total) {
                this.total = total;
                if (total > 0) {
                    System.debug(total);
                }
            }

            @AuraEnabled(cacheable=true)
            public static List<Account> load(String name, Integer max) {
                List<Account> result = [SELECT Id FROM Account WHERE Name = :name LIMIT :max];
                helper(result);
                return result;
            }

            private void helper(List<Account> accounts) { for (Account a : accounts) { a.Name = PREFIX; } }

            public void execute(QueueableContext context) {}

            public abstract Integer size();

            public enum Mode { FAST, SAFE }

            public interface Callback {
                void done(Id recordId);
            }

            private class Row {
                public String value;
            }
        }
    """.trimIndent()

    private val members = ApexStructure.parse(source)
    private val service = members.single()

    private fun child(name: String): ApexMember = service.children.first { it.name == name }

    @Test
    fun topLevelClassWithModifiersAndAnnotations() {
        assertEquals(ApexMemberKind.CLASS, service.kind)
        assertEquals("AccountService", service.name)
        assertTrue(service.isTest)
        assertEquals("public", service.visibility)
        assertEquals(0, service.startOffset)
        assertEquals(source.length, service.endOffset)
    }

    @Test
    fun membersInDeclarationOrder() {
        assertEquals(
            listOf(
                ApexMemberKind.FIELD to "PREFIX",
                ApexMemberKind.FIELD to "cache",
                ApexMemberKind.PROPERTY to "total",
                ApexMemberKind.PROPERTY to "label",
                ApexMemberKind.CONSTRUCTOR to "AccountService",
                ApexMemberKind.METHOD to "load",
                ApexMemberKind.METHOD to "helper",
                ApexMemberKind.METHOD to "execute",
                ApexMemberKind.METHOD to "size",
                ApexMemberKind.ENUM to "Mode",
                ApexMemberKind.INTERFACE to "Callback",
                ApexMemberKind.CLASS to "Row",
            ),
            service.children.map { it.kind to it.name },
        )
    }

    @Test
    fun signaturesAndTypes() {
        assertEquals("load(String name, Integer max): List<Account>", child("load").presentableText)
        assertEquals("AccountService(Integer total)", child("AccountService").presentableText)
        assertEquals("cache: Map<Id, Account>", child("cache").presentableText)
        assertEquals("PREFIX: String", child("PREFIX").presentableText)
        assertTrue(child("load").isStatic)
        assertEquals(listOf("@AuraEnabled"), child("load").annotations)
        assertEquals(source.indexOf("@AuraEnabled"), child("load").startOffset)
        assertEquals(source.indexOf("load(String"), child("load").nameOffset)
    }

    @Test
    fun nestedTypesKeepTheirMembers() {
        assertEquals(listOf("FAST", "SAFE"), child("Mode").children.map { it.name })
        assertEquals(listOf(ApexMemberKind.METHOD to "done"), child("Callback").children.map { it.kind to it.name })
        assertEquals(listOf(ApexMemberKind.FIELD to "value"), child("Row").children.map { it.kind to it.name })
    }

    @Test
    fun memberAtFindsTheInnermostDeclaration() {
        val offset = source.indexOf("helper(result)")
        assertEquals("load", ApexStructure.memberAt(members, offset)?.name)
        assertEquals("done", ApexStructure.memberAt(members, source.indexOf("Id recordId"))?.name)
        assertEquals("AccountService", ApexStructure.memberAt(members, source.indexOf("implements"))?.name)
    }

    @Test
    fun triggersAndAnonymousScripts() {
        val trigger = ApexStructure.parse("trigger AccountTrigger on Account (before insert, after update) {\n  if (Trigger.isBefore) { Handler.run(Trigger.new); }\n}").single()
        assertEquals(ApexMemberKind.TRIGGER to "AccountTrigger", trigger.kind to trigger.name)
        assertEquals("Account", trigger.type)
        assertTrue(trigger.children.isEmpty())

        val script = ApexStructure.parse("Integer x = compute(2);\nSystem.debug(x);\nfoo();\nstatic Integer compute(Integer v) { return v * 2; }")
        assertEquals(listOf(ApexMemberKind.METHOD to "compute"), script.map { it.kind to it.name })
    }
}
