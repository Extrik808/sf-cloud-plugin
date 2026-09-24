package dev.sfcloud.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class LogAnalyzerState : BaseState() {
    var splitVertical by property(true)
    var splitterProportion by property(0.5f)
    var selectedTabIndex by property(0)
    var autoScrollToSource by property(true)
    var showHighlighting by property(true)
    var debugOnly by property(false)
    var treeView by string("STANDARD")
    var connection by string("")
    var logLevels by string("")
}

class ReplTabState : BaseState() {
    var name by string("")
    var body by string("")
    var connection by string("")
    var filePath by string("")
    var splitterProportion by property(0.4f)
    var resultsSplitterProportion by property(0.7f)
    var useToolingApi by property(false)
    var queryAll by property(false)
    var validation by property(true)
    var logLevels by string("")
}

class ReplKindState : BaseState() {
    var tabs by list<ReplTabState>()
    var selectedTab by property(0)
    var preventLoss by property(true)
    var showInOutput by property(false)
    var validateSyntax by property(true)
    var validateResultSize by property(true)
    var maxResultSize by property(1000)
    var validateUnconstrained by property(true)
    var validateSubqueries by property(true)
    var validateBlobFields by property(true)
}

class ToolWindowsState : BaseState() {
    var logAnalyzer by property(LogAnalyzerState())
    var anonymousApex by property(ReplKindState())
    var soqlQuery by property(ReplKindState())
    var soslQuery by property(ReplKindState())
    var showTransitiveFailures by property(true)
    var showUnexpectedErrors by property(true)
}

@Service(Service.Level.PROJECT)
@State(name = "SfCloudToolWindows", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ToolWindowSettings : SimplePersistentStateComponent<ToolWindowsState>(ToolWindowsState()) {
    val logAnalyzer: LogAnalyzerState get() = state.logAnalyzer

    fun repl(kind: String): ReplKindState = when (kind) {
        ANONYMOUS_APEX -> state.anonymousApex
        SOQL_QUERY -> state.soqlQuery
        else -> state.soslQuery
    }

    companion object {
        const val ANONYMOUS_APEX = "Anonymous Apex"
        const val SOQL_QUERY = "SOQL Query"
        const val SOSL_QUERY = "SOSL Query"

        fun getInstance(project: Project): ToolWindowSettings = project.service()
    }
}
