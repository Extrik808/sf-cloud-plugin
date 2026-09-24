package dev.sfcloud

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTabbedPane
import dev.sfcloud.editor.BundleEditorState
import dev.sfcloud.editor.BundleFileEditor
import dev.sfcloud.editor.SfBundle

class BundleTabsTest : BasePlatformTestCase() {
    fun testLwcBundleIsOrderedMarkupCodeStyleMetadata() {
        val root = "force-app/main/default/lwc/pinger"
        val html = add("$root/pinger.html", "<template></template>")
        add("$root/pinger.js", "export default class Pinger {}")
        add("$root/pinger.css", ":host {}")
        add("$root/pinger.js-meta.xml", "<LightningComponentBundle/>")
        val test = add("$root/__tests__/pinger.test.js", "describe('pinger', () => {});")
        assertEquals(
            listOf("pinger.html", "pinger.js", "pinger.css", "pinger.js-meta.xml"),
            SfBundle.files(html).map { it.name },
        )
        assertEquals(emptyList<String>(), SfBundle.files(test).map { it.name })
    }

    fun testAuraBundleKeepsTheComponentFirst() {
        val root = "force-app/main/default/aura/pinger"
        val controller = add("$root/pingerController.js", "({})")
        add("$root/pinger.cmp", "<aura:component/>")
        add("$root/pingerHelper.js", "({})")
        add("$root/pinger.css", ".THIS {}")
        assertEquals(
            listOf("pinger.cmp", "pingerController.js", "pingerHelper.js", "pinger.css"),
            SfBundle.files(controller).map { it.name },
        )
    }

    fun testSourceFilePairsWithItsMetadataFile() {
        val cls = add("force-app/main/default/classes/Util.cls", "public class Util {}")
        val meta = add("force-app/main/default/classes/Util.cls-meta.xml", "<ApexClass/>")
        val lonely = add("force-app/main/default/classes/Solo.cls", "public class Solo {}")
        assertEquals(listOf("Util.cls", "Util.cls-meta.xml"), SfBundle.files(cls).map { it.name })
        assertEquals(listOf("Util.cls", "Util.cls-meta.xml"), SfBundle.files(meta).map { it.name })
        assertEquals(emptyList<String>(), SfBundle.files(lonely).map { it.name })
    }

    fun testTabsSwapTheShownFileAndKeepTheEditorTabFile() {
        val root = "force-app/main/default/lwc/pinger"
        val html = add("$root/pinger.html", "<template></template>")
        val js = add("$root/pinger.js", "export default class Pinger {}")
        val editor = BundleFileEditor(project, js, SfBundle.files(js))
        try {
            val tabs = editor.component as JBTabbedPane
            assertEquals(listOf("pinger.html", "pinger.js"), (0 until tabs.tabCount).map { tabs.getTitleAt(it) })
            assertEquals(1, tabs.selectedIndex)
            assertEquals(js, shownFile(editor))
            tabs.selectedIndex = 0
            assertEquals(html, shownFile(editor))
            assertEquals(js, editor.file)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testStateRemembersTheSelectedTab() {
        val root = "force-app/main/default/lwc/pinger"
        add("$root/pinger.html", "<template></template>")
        val js = add("$root/pinger.js", "export default class Pinger {}")
        val editor = BundleFileEditor(project, js, SfBundle.files(js))
        try {
            (editor.component as JBTabbedPane).selectedIndex = 0
            val state = editor.getState(FileEditorStateLevel.FULL)
            assertEquals("pinger.html", (state as BundleEditorState).selected)
            editor.setState(BundleEditorState("pinger.js", null))
            assertEquals(js, shownFile(editor))
        } finally {
            Disposer.dispose(editor)
        }
    }

    private fun shownFile(editor: BundleFileEditor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(editor.editor.document)

    private fun add(path: String, text: String): VirtualFile =
        myFixture.addFileToProject(path, text).virtualFile
}
