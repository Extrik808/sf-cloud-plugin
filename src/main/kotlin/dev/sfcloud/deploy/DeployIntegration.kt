package dev.sfcloud.deploy

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.settings.SfCloudProjectSettings

class DeployProblemsAnnotator : ExternalAnnotator<PsiFile, List<DeployProblem>>() {
    override fun collectInformation(file: PsiFile): PsiFile? = file.takeIf { it.virtualFile != null }

    override fun doAnnotate(collectedInfo: PsiFile): List<DeployProblem> {
        val path = collectedInfo.virtualFile?.path ?: return emptyList()
        return DeployService.getInstance(collectedInfo.project).problemsFor(path)
    }

    override fun apply(file: PsiFile, annotationResult: List<DeployProblem>, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        annotationResult.forEach { problem ->
            val lineIndex = (problem.line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
            if (document.lineCount == 0) return@forEach
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)
            val start = (lineStart + maxOf(problem.column - 1, 0)).coerceAtMost(lineEnd)
            val text = document.charsSequence
            var end = start
            while (end < lineEnd && (text[end].isLetterOrDigit() || text[end] == '_')) end++
            if (end == start) end = if (start < lineEnd) start + 1 else lineEnd
            val range = if (end > start) TextRange(start, end) else TextRange(lineStart, lineEnd)
            holder.newAnnotation(
                if (problem.isError) HighlightSeverity.ERROR else HighlightSeverity.WARNING,
                "Salesforce: ${problem.message}",
            )
                .range(range)
                .create()
        }
    }
}

class DeployOnSaveListener : AnActionListener {
    private var pending: Pair<Project, List<VirtualFile>>? = null

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (!isSaveAction(action)) return
        pending = null
        val project = event.project ?: return
        if (project.isDisposed || !SfdxProject.isSfdx(project)) return
        if (!SfCloudProjectSettings.getInstance(project).state.deployOnSave) return
        val documents = FileDocumentManager.getInstance()
        val unsaved = documents.unsavedDocuments.mapNotNull { documents.getFile(it) }
        val current = listOfNotNull(event.getData(CommonDataKeys.VIRTUAL_FILE))
        val files = (current + unsaved).distinct().filter { SfdxProject.isSourceFile(project, it) }
        if (files.isNotEmpty()) pending = project to files
    }

    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
        if (!isSaveAction(action)) return
        val (project, files) = pending ?: return
        pending = null
        if (project.isDisposed) return
        val service = DeployService.getInstance(project)
        files.filter { it.isValid }.forEach { service.scheduleDeployOnSave(it) }
    }

    private fun isSaveAction(action: AnAction): Boolean =
        ActionManager.getInstance().getId(action) in SAVE_ACTIONS

    private companion object {
        private val SAVE_ACTIONS = setOf("SaveAll", "SaveDocument")
    }
}

class ToggleDeployOnSaveAction : com.intellij.openapi.actionSystem.ToggleAction(), com.intellij.openapi.project.DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && SfdxProject.isSfdx(project)
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return SfCloudProjectSettings.getInstance(project).state.deployOnSave
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        SfCloudProjectSettings.getInstance(project).state.deployOnSave = state
    }
}
