package dev.sfcloud.org

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.metadata.MetadataOperations
import dev.sfcloud.ui.SfUi
import javax.swing.Icon

class OrgStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = OrgStatusBarWidget.ID

    override fun getDisplayName(): String = "SF Cloud Connections"

    override fun isAvailable(project: Project): Boolean = SfdxProject.isSfdx(project)

    override fun createWidget(project: Project): StatusBarWidget = OrgStatusBarWidget(project)
}

class OrgStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.MultipleTextValuesPresentation {
    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(
            OrgListener.TOPIC,
            OrgListener { this.statusBar?.updateWidget(ID) },
        )
        OrgService.getInstance(project).ensureLoaded()
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getSelectedValue(): String {
        val service = OrgService.getInstance(project)
        if (service.loading && service.orgs.isEmpty()) return "Loading connections…"
        val org = service.targetOrg
        return org?.displayName ?: service.targetOrgKey ?: "<No Connection>"
    }

    override fun getIcon(): Icon {
        val service = OrgService.getInstance(project)
        val org = service.targetOrg ?: return AllIcons.Actions.OfflineMode
        return Connections.icon(org)
    }

    override fun getTooltipText(): String {
        val service = OrgService.getInstance(project)
        val key = service.targetOrgKey ?: return "SF Cloud Connection: no connection selected"
        val org = service.targetOrg
        return when {
            org == null && !service.loading -> "SF Cloud Connection: $key is invalid"
            org != null && !org.connected -> "SF Cloud Connection: ${org.displayName} is invalid${if (org.kind == OrgKind.SCRATCH) " or expired" else ""}"
            org != null -> "SF Cloud Connection: ${org.displayName} (${org.username}) · ${org.kind.label}"
            else -> "SF Cloud Connection: $key"
        }
    }

    override fun getPopup(): JBPopup {
        val service = OrgService.getInstance(project)
        val group = DefaultActionGroup()
        Connections.items(project).forEach { item ->
            when (item) {
                is ConnectionItem.Header -> group.add(Separator.create(item.text))
                is ConnectionItem.Org -> group.add(connectionGroup(item.org, item.org.key == service.targetOrgKey))
                else -> Unit
            }
        }
        group.add(Separator.create())
        listOf("SfCloud.LoginOrg", "SfCloud.CreateScratchOrg", "SfCloud.RefreshOrgs", "SfCloud.OpenSettings")
            .mapNotNull { ActionManager.getInstance().getAction(it) }
            .forEach { group.add(it) }
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "SF Cloud connections for project '${project.name}'",
            group,
            SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    private fun connectionGroup(org: SfOrg, current: Boolean): DefaultActionGroup {
        val name = if (org.alias != null) "${org.displayName} (${Connections.shortenUsername(org.username)})" else org.displayName
        val text = org.expirationDate?.takeIf { org.kind == OrgKind.SCRATCH }?.let { "$name · expires $it" } ?: name
        val group = DefaultActionGroup(text, true)
        group.templatePresentation.icon = if (current) AllIcons.Actions.Checked else Connections.icon(org)
        group.add(object : DumbAwareAction("Set as connection for '${project.name}'") {
            override fun actionPerformed(e: AnActionEvent) {
                OrgService.getInstance(project).select(org)
                Connections.recordUsage(project, org.key)
            }
        })
        group.add(object : DumbAwareAction("Open connection...") {
            override fun actionPerformed(e: AnActionEvent) {
                SfUi.background(project, "Logging into ${org.displayName} as ${org.username}", onError = { SfNotifier.error(project, "Cannot open ${org.displayName}", it) }) { indicator ->
                    val result = SfCli.run(project, listOf("org", "open", "--target-org", org.key), 120_000, indicator)
                    if (!result.success) SfNotifier.error(project, "Cannot open ${org.displayName}", result.message)
                }
            }
        })
        if (org.kind == OrgKind.SCRATCH) {
            group.add(object : DumbAwareAction("Delete scratch org...") {
                override fun actionPerformed(e: AnActionEvent) {
                    ScratchOrgService.getInstance(project).delete(org)
                }
            })
        }
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.takeIf { SfdxProject.isSourceFile(project, it) }
        if (file != null) {
            group.add(object : DumbAwareAction("Compare '${file.name}' with '${org.displayName}'...") {
                override fun actionPerformed(e: AnActionEvent) {
                    MetadataOperations(project).compare(org.key, listOf(file))
                }
            })
        }
        return group
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID = "SfCloud.OrgWidget"
    }
}
