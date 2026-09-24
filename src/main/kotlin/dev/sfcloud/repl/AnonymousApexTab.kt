package dev.sfcloud.repl

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.sfcloud.api.AnonymousApexExecutor
import dev.sfcloud.api.AnonymousApexResult
import dev.sfcloud.lang.AnonymousApexFileType
import dev.sfcloud.log.ApexLogParser
import dev.sfcloud.log.LogAnalyzerPanel
import dev.sfcloud.log.LogLevels
import dev.sfcloud.log.LogLevelsPanel
import dev.sfcloud.log.LoggingConfig
import dev.sfcloud.org.OrgKind
import dev.sfcloud.settings.ReplKindState
import dev.sfcloud.settings.ReplTabState
import dev.sfcloud.settings.ToolWindowSettings
import dev.sfcloud.ui.SfUi
import javax.swing.JComponent

object AnonymousApexKind : ReplKind(ToolWindowSettings.ANONYMOUS_APEX, AnonymousApexFileType, "System.debug('Hello from SF Cloud');\n") {
    override fun createTab(window: ReplWindow, state: ReplTabState): ReplTab = AnonymousApexTab(window.project, window, state)

    override fun configure(project: Project, settings: ReplKindState) = object : ReplConfigDialog(project, "Anonymous Apex Configuration") {
        private val preventLoss = check("Prevent loss of unsaved changes", "When checked, the user will be prompted when closing a tab with unsaved changes.", settings.preventLoss)

        init {
            init()
        }

        override fun createCenterPanel(): JComponent = SfUi.stack(SfUi.section("General"), preventLoss)

        override fun doOKAction() {
            settings.preventLoss = preventLoss.isSelected
            super.doOKAction()
        }
    }
}

class AnonymousApexToolWindowFactory : ReplToolWindowFactory(AnonymousApexKind)

class AnonymousApexTab(project: Project, window: ReplWindow, state: ReplTabState) : ReplTab(project, window, state) {
    private val kindSettings = window.settings
    private val logPanel = LogAnalyzerPanel(project, this, { connection.selectedKey }, { line, column -> moveCaret(line, column) })
    private var levels = LogLevels.decode(state.logLevels)
        ?: LogLevels.decode(ToolWindowSettings.getInstance(project).logAnalyzer.logLevels)
        ?: LogLevels.DEFAULT
    private val levelsPanel = LogLevelsPanel(levels) { changed ->
        levels = changed
        state.logLevels = changed.encode()
        val org = connection.selectedKey
        SfUi.background(project, "Saving log levels", onError = error("Cannot save log levels")) { indicator ->
            LoggingConfig(project).saveLevels(org, changed, indicator)
        }
    }

    override fun header(): JComponent = levelsPanel

    override fun results(): JComponent = logPanel.component

    override fun toolbar(): ActionGroup = SfUi.group(
        executeAction,
        null,
        newTabAction,
        closeTabAction,
        loadAction,
        saveAction,
        renameAction,
        SfUi.toggle(
            "Show In Output",
            "When selected, the Anonymous Apex script body is written to the output window before the script execution results",
            AllIcons.Actions.ShowCode,
            { kindSettings.showInOutput },
        ) { kindSettings.showInOutput = it },
        configureAction("Configure Anonymous Apex settings"),
    )

    override fun execute(text: String) {
        if (running) return
        if (text.isBlank()) {
            Messages.showInfoMessage(project, "No anonymous Apex script found.", SfUi.TITLE)
            return
        }
        val org = connection.selectedKey
        if (connection.selectedOrg?.kind == OrgKind.PRODUCTION &&
            Messages.showYesNoDialog(project, "Are you sure you wish to execute this anonymous Apex script?", SfUi.TITLE, Messages.getWarningIcon()) != Messages.YES
        ) {
            return
        }
        running = true
        val echo = kindSettings.showInOutput
        val currentLevels = levels
        SfUi.background(project, "Executing Anonymous Apex", onError = { message ->
            running = false
            SfUi.edt(project) { logPanel.show("", "", header = listOf("$message\n" to ConsoleViewContentType.ERROR_OUTPUT)) }
        }) { indicator ->
            val result = AnonymousApexExecutor(project).execute(org, text, currentLevels, indicator)
            val log = result.log.orEmpty()
            val parsed = if (log.isEmpty()) null else runCatching { ApexLogParser.parse(log) }.getOrNull()
            val header = buildList {
                if (echo) {
                    add("Executing:\n$SEPARATOR\n$text\n$SEPARATOR\n\n" to ConsoleViewContentType.SYSTEM_OUTPUT)
                }
                addAll(outcome(result))
                if (log.isEmpty()) add("Warning: No Apex log found in response.\n\n" to ConsoleViewContentType.LOG_WARNING_OUTPUT)
                if (log.isNotEmpty() && parsed == null) add("Failed to parse the log. Showing the raw log instead.\n\n" to ConsoleViewContentType.LOG_WARNING_OUTPUT)
            }
            SfUi.edt(project) {
                running = false
                logPanel.show("${state.name} log", log, parsed, header)
            }
        }
    }

    private fun outcome(result: AnonymousApexResult): List<Pair<String, ConsoleViewContentType>> = when {
        !result.compiled -> listOf(
            "Compile failure on AnonymousBlock: line ${result.line ?: 0}, column ${result.column ?: 0}: ${result.compileProblem.orEmpty()}\n\n" to
                ConsoleViewContentType.ERROR_OUTPUT,
        )
        !result.success -> listOf(
            "Error on AnonymousBlock: line ${result.line ?: 0}, column ${result.column ?: 0}: ${result.exceptionMessage.orEmpty()}\n" to
                ConsoleViewContentType.ERROR_OUTPUT,
            "${result.exceptionStackTrace.orEmpty()}\n\n" to ConsoleViewContentType.ERROR_OUTPUT,
        )
        else -> listOf("Success.\n\n" to ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    companion object {
        private const val SEPARATOR = "=============================================================================="
    }
}
