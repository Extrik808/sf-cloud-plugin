package dev.sfcloud.repl

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.settings.ToolWindowSettings

abstract class ShowReplAction(private val id: String) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { SfdxProject.isSfdx(it) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(id)?.activate(null)
    }
}

class ShowAnonymousApexAction : ShowReplAction(ToolWindowSettings.ANONYMOUS_APEX)

class ShowSoqlQueryAction : ShowReplAction(ToolWindowSettings.SOQL_QUERY)

class ShowSoslQueryAction : ShowReplAction(ToolWindowSettings.SOSL_QUERY)

abstract class OpenInReplAction(private val kind: ReplKind, private val execute: Boolean) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && SfdxProject.isSfdx(project) && source(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReplSources.run(project, source(e) ?: return, execute)
    }

    private fun source(e: AnActionEvent): ReplSource? =
        ReplSources.of(kind, e.getData(CommonDataKeys.EDITOR), e.getData(CommonDataKeys.VIRTUAL_FILE), e.getData(CommonDataKeys.PSI_FILE))
}

class OpenInAnonymousApexAction : OpenInReplAction(AnonymousApexKind, execute = false)

class ExecuteInAnonymousApexAction : OpenInReplAction(AnonymousApexKind, execute = true)

class OpenInSoqlQueryAction : OpenInReplAction(SoqlQueryKind, execute = false)

class ExecuteInSoqlQueryAction : OpenInReplAction(SoqlQueryKind, execute = true)

class OpenInSoslQueryAction : OpenInReplAction(SoslQueryKind, execute = false)

class ExecuteInSoslQueryAction : OpenInReplAction(SoslQueryKind, execute = true)
