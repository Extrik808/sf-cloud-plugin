package dev.sfcloud

import com.google.gson.JsonParser
import dev.sfcloud.metadata.ComponentRef
import dev.sfcloud.ost.OstChanges
import dev.sfcloud.ost.OstRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OstChangesTest {
    @Test
    fun objectsAndFieldsMapToTheirSObjects() {
        val names = OstChanges.sObjects(
            listOf(
                ComponentRef("CustomObject", "Invoice__c"),
                ComponentRef("CustomField", "Account.Region__c"),
                ComponentRef("CustomField", "account.Tier__c"),
                ComponentRef("ApexClass", "InvoiceService"),
                ComponentRef("Layout", "Account-Account Layout"),
            ),
        )
        assertEquals(listOf("Account", "Invoice__c"), names.toList())
    }

    @Test
    fun componentsWithoutSchemaChangesYieldNothing() {
        assertTrue(OstChanges.sObjects(listOf(ComponentRef("ApexClass", "A"), ComponentRef("LightningComponentBundle", "b"))).isEmpty())
    }

    @Test
    fun sourcePathsResolveToTheObjectFolder() {
        val names = OstChanges.sObjectsInPaths(
            listOf(
                "/p/force-app/main/default/objects/Account",
                "/p/force-app/main/default/objects/Invoice__c/fields/Total__c.field-meta.xml",
                "/p/force-app/main/default/objects/Invoice__c/Invoice__c.object-meta.xml",
                "/p/force-app/main/default/classes/Invoice.cls",
                "/p/force-app/main/default/objects",
            ),
        )
        assertEquals(listOf("Account", "Invoice__c"), names.toList())
    }

    @Test
    fun parseSplitsOnSpacesCommasAndSemicolons() {
        assertEquals(listOf("Account", "Contact", "Invoice__c"), OstChanges.parse(" Account,Contact; Invoice__c  account ").toList())
    }

    @Test
    fun referencesCollectLookupTargets() {
        val describe = JsonParser.parseString(
            """{"name": "Invoice__c", "fields": [
                {"name": "Account__c", "referenceTo": ["Account"]},
                {"name": "WhatId", "referenceTo": ["Account", "Opportunity"]},
                {"name": "Name"}
            ]}""",
        ).asJsonObject
        assertEquals(listOf("Account", "Opportunity"), OstChanges.references(describe).toList())
        assertTrue(OstChanges.references(null).isEmpty())
    }

    @Test
    fun selectiveRequestsMergeCaseInsensitively() {
        val merged = OstRequest.sObjects(listOf("Account"), notify = false).merge(OstRequest.sObjects(listOf("account", "Contact"), notify = false))
        assertEquals(listOf("Account", "Contact"), merged.sObjects!!.toList())
        assertFalse(merged.systemLibrary)
        assertFalse(merged.notify)
    }

    @Test
    fun aFullRequestAbsorbsASelectiveOne() {
        val merged = OstRequest.sObjects(listOf("Account"), notify = false).merge(OstRequest.everything())
        assertNull(merged.sObjects)
        assertTrue(merged.systemLibrary)
        assertTrue(merged.notify)
        assertFalse(merged.isSelective)
    }
}
