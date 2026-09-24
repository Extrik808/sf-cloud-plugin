package dev.sfcloud.tests

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.messages.Topic
import dev.sfcloud.core.obj
import dev.sfcloud.core.str
import dev.sfcloud.settings.SfCloudProjectSettings
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

data class ClassCoverage(val covered: Set<Int>, val uncovered: Set<Int>, val percent: Int)

interface CoverageListener {
    fun coverageChanged()

    companion object {
        val TOPIC: Topic<CoverageListener> = Topic.create("SF Cloud Apex coverage", CoverageListener::class.java)
    }
}

@Service(Service.Level.PROJECT)
class ApexCoverageService(private val project: Project) : Disposable {
    private val coverage = ConcurrentHashMap<String, ClassCoverage>()
    private val highlighters = HashMap<Editor, List<RangeHighlighter>>()

    @Volatile
    var report: CoverageReport? = null
        private set

    @Volatile
    var lastRun: List<CoverageEntry> = emptyList()
        private set

    fun publish(entries: List<CoverageEntry>?, report: CoverageReport?) {
        if (entries != null) lastRun = entries
        this.report = report
        project.messageBus.syncPublisher(CoverageListener.TOPIC).coverageChanged()
    }

    fun update(entries: List<JsonObject>) {
        entries.forEach { entry ->
            val name = entry.str("name") ?: return@forEach
            val lines = entry.obj("lines") ?: return@forEach
            val covered = mutableSetOf<Int>()
            val uncovered = mutableSetOf<Int>()
            lines.entrySet().forEach { (line, hits) ->
                val number = line.toIntOrNull() ?: return@forEach
                if (runCatching { hits.asInt }.getOrDefault(0) > 0) covered += number else uncovered += number
            }
            coverage[name.lowercase()] = ClassCoverage(covered, uncovered, entry.get("coveredPercent")?.asInt ?: 0)
        }
        refreshEditors()
    }

    fun clear() {
        coverage.clear()
        lastRun = emptyList()
        publish(null, null)
        refreshEditors()
    }

    fun coverageFor(file: VirtualFile): ClassCoverage? {
        if (file.extension?.lowercase() !in setOf("cls", "trigger")) return null
        return coverage[file.nameWithoutExtension.lowercase()]
    }

    fun refreshEditors() {
        ApplicationManager.getApplication().invokeLater({
            FileEditorManager.getInstance(project).allEditors
                .filterIsInstance<TextEditor>()
                .forEach { apply(it.editor, it.file) }
        }, project.disposed)
    }

    fun apply(editor: Editor, file: VirtualFile?) {
        highlighters.keys.removeIf { it.isDisposed }
        highlighters.remove(editor)?.forEach { it.dispose() }
        if (file == null || !SfCloudProjectSettings.getInstance(project).state.showCoverage) return
        val data = coverageFor(file) ?: return
        val document = editor.document
        val markup = editor.markupModel
        val created = mutableListOf<RangeHighlighter>()
        fun mark(line: Int, background: Color, stripe: Color) {
            val index = line - 1
            if (index < 0 || index >= document.lineCount) return
            val attributes = TextAttributes().apply { backgroundColor = background }
            val highlighter = markup.addRangeHighlighter(
                document.getLineStartOffset(index),
                document.getLineEndOffset(index),
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.lineMarkerRenderer = LineMarkerRenderer { _, g, r ->
                g.color = stripe
                g.fillRect(r.x, r.y, 3, r.height)
            }
            created += highlighter
        }
        data.covered.forEach { mark(it, COVERED_BACKGROUND, COVERED_STRIPE) }
        data.uncovered.forEach { mark(it, UNCOVERED_BACKGROUND, UNCOVERED_STRIPE) }
        highlighters[editor] = created
    }

    override fun dispose() {
        highlighters.values.flatten().forEach { it.dispose() }
        highlighters.clear()
    }

    companion object {
        private val COVERED_BACKGROUND = JBColor(Color(0xEAF6EC), Color(0x243328))
        private val UNCOVERED_BACKGROUND = JBColor(Color(0xFCEBEA), Color(0x3D2728))
        private val COVERED_STRIPE = JBColor(Color(0x4CAF50), Color(0x5FB865))
        private val UNCOVERED_STRIPE = JBColor(Color(0xE53935), Color(0xE0605D))

        fun getInstance(project: Project): ApexCoverageService = project.service()
    }
}

class CoverageEditorListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val service = ApexCoverageService.getInstance(project)
        if (service.coverageFor(file) == null) return
        source.getEditors(file).filterIsInstance<TextEditor>().forEach { service.apply(it.editor, file) }
    }
}

class ToggleCoverageAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return SfCloudProjectSettings.getInstance(project).state.showCoverage
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        SfCloudProjectSettings.getInstance(project).state.showCoverage = state
        ApexCoverageService.getInstance(project).refreshEditors()
    }
}

class ClearCoverageAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ApexCoverageService.getInstance(e.project ?: return).clear()
    }
}
