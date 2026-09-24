package dev.sfcloud.metadata

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.sfcloud.api.SfApi
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.core.str
import dev.sfcloud.deploy.DeployService
import dev.sfcloud.org.Connections
import dev.sfcloud.org.OrgService
import dev.sfcloud.org.SfOrg
import dev.sfcloud.ui.SfUi
import javax.swing.Icon

abstract class SfdxAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && SfdxProject.isSfdx(project) && isAvailable(e, project)
    }

    protected open fun isAvailable(e: AnActionEvent, project: Project): Boolean = true

    protected fun context(e: AnActionEvent, project: Project): List<VirtualFile> =
        (e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE)))
            .filter { SfdxProject.isSourceFile(project, it) || it in SfdxProject.packageDirectories(project) }
}

abstract class ContextSfdxAction : SfdxAction() {
    override fun isAvailable(e: AnActionEvent, project: Project): Boolean = context(e, project).isNotEmpty()
}

class ForceSaveAction : ContextSfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        DeployService.getInstance(project).deploy(context(e, project))
    }
}

class ForceValidateAction : ContextSfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        DeployService.getInstance(project).deploy(context(e, project), checkOnly = true)
    }
}

class DeployModifiedMetadataAction : SfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        val modified = ChangeListManager.getInstance(project).affectedFiles
            .filter { it.isValid && SfdxProject.isSourceFile(project, it) }
        if (modified.isEmpty()) {
            SfNotifier.info(project, "Nothing to Deploy", "No modified metadata files were found in the package directories.")
            return
        }
        DeployService.getInstance(project).deploy(modified)
    }
}

class DeployAllMetadataAction : SfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        BuildOptionsDialog(project, BuildOperation.DEPLOY, context(e, project)).show()
    }
}

class RetrieveMetadataAction : SfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BuildOptionsDialog(project, BuildOperation.RETRIEVE, context(e, project)).show()
    }
}

class RefreshMetadataAction : ContextSfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DeployService.getInstance(project).retrieve(context(e, project))
    }
}

class DeleteMetadataAction : SfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BuildOptionsDialog(project, BuildOperation.DELETE, context(e, project)).show()
    }
}

class PushMetadataAction : SfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        DeployService.getInstance(project).pushTracked()
    }
}

class PullMetadataAction : SfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        DeployService.getInstance(e.project ?: return).pullTracked()
    }
}

class CompareWithServerAction : ContextSfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        MetadataOperations(project).compare(null, context(e, project).filterNot { it.isDirectory })
    }

    override fun isAvailable(e: AnActionEvent, project: Project): Boolean = context(e, project).any { !it.isDirectory }
}

class CompareWithOtherServerAction : ContextSfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = context(e, project).filterNot { it.isDirectory }
        if (files.isEmpty()) return
        FileDocumentManager.getInstance().saveAllDocuments()
        val orgs = OrgService.getInstance(project).orgs
        val step = object : BaseListPopupStep<SfOrg>("Choose Connection for Comparison", orgs) {
            override fun getTextFor(value: SfOrg): String =
                if (value.alias != null) "${value.displayName} (${Connections.shortenUsername(value.username)})" else value.displayName

            override fun getIconFor(value: SfOrg): Icon = Connections.icon(value)

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: SfOrg, finalChoice: Boolean): PopupStep<*>? {
                MetadataOperations(project).compare(selectedValue.key, files)
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(e.dataContext)
    }

    override fun isAvailable(e: AnActionEvent, project: Project): Boolean = context(e, project).any { !it.isDirectory }
}

abstract class OpenOrgPathAction : SfdxAction() {
    protected abstract fun path(project: Project, file: VirtualFile?, indicator: ProgressIndicator): String

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = OrgService.getInstance(project)
        val label = service.orgLabel(null)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        SfUi.background(project, "Opening '$label'", onError = { SfNotifier.error(project, "Cannot open $label", it) }) { indicator ->
            val result = SfCli.run(project, listOf("org", "open", "--path", path(project, file, indicator)) + service.orgArgs(null), 120_000, indicator)
            if (!result.success) SfNotifier.error(project, "Cannot open $label", result.message)
        }
    }
}

class ShowSetupAction : OpenOrgPathAction() {
    override fun path(project: Project, file: VirtualFile?, indicator: ProgressIndicator) = SETUP_HOME
}

class ShowDeveloperConsoleAction : OpenOrgPathAction() {
    override fun path(project: Project, file: VirtualFile?, indicator: ProgressIndicator) = "/_ui/common/apex/debug/ApexCSIPage"
}

class OpenInSetupAction : OpenOrgPathAction() {
    override fun isAvailable(e: AnActionEvent, project: Project): Boolean =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { SfdxProject.isSourceFile(project, it) } == true

    override fun path(project: Project, file: VirtualFile?, indicator: ProgressIndicator): String {
        file ?: return SETUP_HOME
        val target = SfdxProject.deployTarget(file)
        val name = target.nameWithoutExtension
        val lookup = when (target.extension?.lowercase()) {
            "cls" -> "ApexClass"
            "trigger" -> "ApexTrigger"
            "page" -> "ApexPage"
            "component" -> "ApexComponent"
            else -> null
        } ?: return SETUP_HOME
        val id = SfApi.getInstance(project)
            .query(null, "SELECT Id FROM $lookup WHERE Name = '${SfApi.escapeSoql(name)}' LIMIT 1", tooling = true, indicator = indicator)
            .records.firstOrNull()?.str("Id") ?: return SETUP_HOME
        return "/$id"
    }
}

private const val SETUP_HOME = "/lightning/setup/SetupOneHome/home"

abstract class ForceIgnoreAction(private val negated: Boolean) : ContextSfdxAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base = SfdxProject.root(project) ?: return
        val entries = context(e, project).mapNotNull { VfsUtil.getRelativePath(it, base) }
            .map { if (negated) "!$it" else it }
        if (entries.isEmpty()) return
        ApplicationManager.getApplication().runWriteAction {
            val ignore = base.findChild(".forceignore") ?: base.createChildData(this, ".forceignore")
            val existing = VfsUtil.loadText(ignore)
            val additions = entries.filter { it !in existing.lines() }
            if (additions.isEmpty()) return@runWriteAction
            val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
            VfsUtil.saveText(ignore, existing + separator + additions.joinToString("\n") + "\n")
        }
    }
}

class AddToForceIgnoreAction : ForceIgnoreAction(negated = false)

class AddToForceIgnoreNegatedAction : ForceIgnoreAction(negated = true)
