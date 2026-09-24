package dev.sfcloud

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.metadata.MetadataDiff
import dev.sfcloud.metadata.MetadataDiffEntry

class MetadataDiffTest : BasePlatformTestCase() {
    fun testRequestPutsTheOrgOnTheLeftAndTheEditableLocalFileOnTheRight() {
        val local = myFixture.addFileToProject("force-app/main/default/classes/Util.cls", "public class Util {}")
        val entry = MetadataDiffEntry(
            "classes/Util.cls",
            local.virtualFile,
            "Util.cls",
            "public class Util { void run() {} }",
        )
        val request = MetadataDiff.requests(project, "DevOrg", listOf(entry)).single() as SimpleDiffRequest
        assertEquals("classes/Util.cls", request.title)
        assertEquals(listOf("DevOrg", "Local (editable)"), request.contentTitles)
        assertEquals("public class Util { void run() {} }", (request.contents[0] as DocumentContent).document.text)
        assertEquals("public class Util {}", (request.contents[1] as DocumentContent).document.text)
        assertTrue(request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)!!.contentEquals(booleanArrayOf(true, false)))
    }

    fun testServerOnlyFileIsShownAgainstAnEmptyReadOnlySide() {
        val entry = MetadataDiffEntry("classes/New.cls", null, "New.cls", "public class New {}")
        val request = MetadataDiff.requests(project, "DevOrg", listOf(entry)).single() as SimpleDiffRequest
        assertEquals(listOf("DevOrg", "Not in the project"), request.contentTitles)
        assertTrue(request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)!!.contentEquals(booleanArrayOf(true, true)))
    }

    fun testAllChangedFilesEndUpInOneDiffTab() {
        val first = myFixture.addFileToProject("force-app/main/default/classes/A.cls", "public class A {}")
        val second = myFixture.addFileToProject("force-app/main/default/classes/B.cls", "public class B {}")
        val entries = listOf(
            MetadataDiffEntry("classes/A.cls", first.virtualFile, "A.cls", "public class A { }"),
            MetadataDiffEntry("classes/B.cls", second.virtualFile, "B.cls", "public class B { }"),
        )
        val file = MetadataDiff.chainFile(project, "DevOrg", "Merge with 'DevOrg'", entries)!!
        assertEquals("Merge with 'DevOrg'", file.name)
        assertEquals(2, file.chain.requests.size)
        assertNull(MetadataDiff.chainFile(project, "DevOrg", "Merge with 'DevOrg'", emptyList()))
    }
}
