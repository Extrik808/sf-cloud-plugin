package dev.sfcloud.org

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import javax.swing.JComponent
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.settings.SfCloudConfigurable

abstract class SfdxProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && SfdxProject.isSfdx(project)
    }
}

class RefreshOrgsAction : SfdxProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        OrgService.getInstance(e.project ?: return).refresh()
    }
}

class OpenOrgAction : SfdxProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val args = listOf("org", "open") + OrgService.getInstance(project).targetOrgArgs()
        object : Task.Backgroundable(project, "Opening Salesforce org", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = SfCli.run(project, args, 120_000, indicator)
                if (!result.success) SfNotifier.error(project, "Cannot open org", result.message)
            }
        }.queue()
    }
}

class LoginOrgAction : SfdxProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        authorize(e.project ?: return)
    }

    companion object {
        fun authorize(project: Project) {
            val dialog = AuthorizeOrgDialog(project)
            if (!dialog.showAndGet()) return
            val alias = dialog.alias
            val instanceUrl = dialog.loginUrl
            object : Task.Backgroundable(project, "Authorizing Org", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text2 = "Log into the org using your Web browser."
                    val result = SfCli.run(
                        project,
                        listOf("org", "login", "web", "--alias", alias, "--instance-url", instanceUrl),
                        10 * 60 * 1000,
                        indicator,
                    )
                    when {
                        result.success -> {
                            SfNotifier.info(project, "Authorized $alias")
                            OrgService.getInstance(project).refresh()
                        }
                        indicator.isCanceled -> SfNotifier.warn(project, "Authorization Canceled", "Org authorization was canceled by the user.")
                        else -> SfNotifier.error(project, "Authorization Failed", result.message)
                    }
                }
            }.queue()
        }
    }
}

class AuthorizeOrgDialog(project: Project) : DialogWrapper(project, true) {
    private enum class OrgType(val label: String, val url: String?) {
        PRODUCTION("Production", "https://login.salesforce.com"),
        SANDBOX("Sandbox", "https://test.salesforce.com"),
        CUSTOM("Custom Domain", null),
    }

    private val aliasField = JBTextField()
    private val typeCombo = ComboBox(OrgType.entries.toTypedArray())
    private val urlField = JBTextField(OrgType.PRODUCTION.url)

    val alias: String get() = aliasField.text.trim()
    val loginUrl: String get() = urlField.text.trim()

    init {
        title = "Authorize OAuth Org"
        typeCombo.renderer = SimpleListCellRenderer.create("") { it.label }
        typeCombo.addActionListener {
            val type = typeCombo.selectedItem as OrgType
            urlField.isEditable = type.url == null
            if (type.url != null) urlField.text = type.url
        }
        urlField.isEditable = false
        init()
    }

    override fun createCenterPanel(): JComponent = com.intellij.util.ui.FormBuilder.createFormBuilder()
        .addLabeledComponent("Alias:", aliasField)
        .addLabeledComponent("Organization type:", typeCombo)
        .addLabeledComponent("Login URL:", urlField)
        .addComponent(JBLabel("Log into the org using your Web browser.").apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL })
        .panel

    override fun getPreferredFocusedComponent(): JComponent = aliasField

    override fun doValidate(): ValidationInfo? = when {
        alias.isEmpty() -> ValidationInfo("An alias must be specified", aliasField)
        !Regex("[A-Za-z0-9_.-]+").matches(alias) -> ValidationInfo("The alias may contain letters, digits, '.', '_' and '-' only", aliasField)
        !loginUrl.startsWith("https://") -> ValidationInfo("The login URL must start with https://", urlField)
        else -> null
    }
}

class OpenSettingsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, SfCloudConfigurable::class.java)
    }
}
