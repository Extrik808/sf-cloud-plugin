package dev.sfcloud.repl

import com.google.gson.JsonObject
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import dev.sfcloud.api.SfApi
import dev.sfcloud.api.SfApiException
import dev.sfcloud.core.arr
import dev.sfcloud.core.objects
import dev.sfcloud.core.str
import dev.sfcloud.lang.SoqlFileType
import dev.sfcloud.lang.SoslFileType
import dev.sfcloud.settings.ReplKindState
import dev.sfcloud.settings.ReplTabState
import dev.sfcloud.settings.ToolWindowSettings
import dev.sfcloud.ui.SfUi
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

object SoqlQueryKind : ReplKind(ToolWindowSettings.SOQL_QUERY, SoqlFileType, "SELECT Id, Name FROM Account LIMIT 10\n") {
    override fun createTab(window: ReplWindow, state: ReplTabState): ReplTab = SoqlQueryTab(window.project, window, state)

    override fun configure(project: Project, settings: ReplKindState) = object : ReplConfigDialog(project, "SOQL Query Configuration") {
        private val preventLoss = check("Prevent loss of unsaved changes", "When checked, the user will be prompted when closing a tab with unsaved changes.", settings.preventLoss)
        private val syntax = check("Validate query syntax", "When enabled, the user is prompted when query syntax appears invalid.", settings.validateSyntax)
        private val size = check("Validate query result set size", "When enabled, the user is prompted when a query would return more than the configured maximum result set size.", settings.validateResultSize)
        private val max = JBTextField(settings.maxResultSize.toString(), 6).apply { toolTipText = "The maximum query result set size in the range 100-50000." }
        private val unconstrained = check("Validate unconstrained query result set size", "When enabled, the user is prompted when an unconstrained query would return more than the configured maximum result set size.", settings.validateUnconstrained)
        private val subqueries = check("Validate unconstrained sub-queries", "When enabled, the user is prompted when an unconstrained query includes unconstrained sub-queries.", settings.validateSubqueries)
        private val blobs = check("Validate unconstrained queries for Blob fields", "When enabled, the user is prompted when an unconstrained query includes one or more Blob fields.", settings.validateBlobFields)

        init {
            init()
        }

        override fun createCenterPanel(): JComponent = SfUi.stack(
            SfUi.section("General"),
            preventLoss,
            SfUi.section("Validation"),
            syntax,
            size,
            indented(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JBLabel("Maximum query result set size"))
                add(max)
            }),
            indented(unconstrained),
            subqueries,
            blobs,
        )

        override fun doValidate() = if (max.text.toIntOrNull() in 100..50000) null else com.intellij.openapi.ui.ValidationInfo("The maximum query result set size must be in the range 100-50000.", max)

        override fun doOKAction() {
            settings.preventLoss = preventLoss.isSelected
            settings.validateSyntax = syntax.isSelected
            settings.validateResultSize = size.isSelected
            settings.maxResultSize = max.text.toIntOrNull() ?: settings.maxResultSize
            settings.validateUnconstrained = unconstrained.isSelected
            settings.validateSubqueries = subqueries.isSelected
            settings.validateBlobFields = blobs.isSelected
            super.doOKAction()
        }
    }
}

object SoslQueryKind : ReplKind(ToolWindowSettings.SOSL_QUERY, SoslFileType, "FIND {Acme} IN ALL FIELDS RETURNING Account(Id, Name), Contact(Id, Name)\n") {
    override fun createTab(window: ReplWindow, state: ReplTabState): ReplTab = SoslQueryTab(window.project, window, state)

    override fun configure(project: Project, settings: ReplKindState) = object : ReplConfigDialog(project, "SOSL Query Configuration") {
        private val preventLoss = check("Prevent loss of unsaved changes", "When checked, the user will be prompted when closing a tab with unsaved changes.", settings.preventLoss)
        private val syntax = check("Validate query syntax", "When enabled, the user is prompted when query syntax appears invalid.", settings.validateSyntax)

        init {
            init()
        }

        override fun createCenterPanel(): JComponent = SfUi.stack(SfUi.section("General"), preventLoss, SfUi.section("Validation"), syntax)

        override fun doOKAction() {
            settings.preventLoss = preventLoss.isSelected
            settings.validateSyntax = syntax.isSelected
            super.doOKAction()
        }
    }
}

class SoqlQueryToolWindowFactory : ReplToolWindowFactory(SoqlQueryKind)

class SoslQueryToolWindowFactory : ReplToolWindowFactory(SoslQueryKind)

object SoqlText {
    private val SELECT = Regex("""^\s*SELECT\s+.+?\s+FROM\s+(\w+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAIL = Regex("""\s+(ORDER\s+BY|LIMIT|OFFSET|FOR\s+(VIEW|REFERENCE|UPDATE)|UPDATE\s+(TRACKING|VIEWSTAT)|ALL\s+ROWS)\b.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SUBQUERY = Regex("""\(\s*SELECT\s+(.+?)\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val FIELDS = Regex("""^\s*SELECT\s+(.+?)\s+FROM\s""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TOOLING_ONLY = setOf(
        "apexcodecoverage", "apexcodecoverageaggregate", "apexexecutionoverlayaction", "apexexecutionoverlayresult",
        "apexorgwidecoverage", "debuglevel", "traceflag", "metadatacontainer", "containerasyncrequest", "entitydefinition",
        "fielddefinition", "customfield", "customobject", "symboltable", "validationrule", "workflowrule", "flowdefinition",
        "lightningcomponentbundle", "lightningcomponentresource", "auradefinitionbundle", "auradefinition", "apexclassmember",
        "apextriggermember", "operationlog", "sourcemember", "heapdump", "apexlog",
    )

    fun normalize(text: String): String = text.trim().removeSuffix(";").trim()

    fun isValid(query: String): Boolean = SELECT.containsMatchIn(query) && query.count { it == '(' } == query.count { it == ')' }

    fun sobject(query: String): String? = SELECT.find(SUBQUERY.replace(query, "Id"))?.groupValues?.get(1)

    fun isToolingOnly(query: String): Boolean = sobject(query)?.lowercase() in TOOLING_ONLY

    fun isConstrained(query: String): Boolean {
        val outer = SUBQUERY.replace(query, "")
        return Regex("""\b(WHERE|LIMIT)\b""", RegexOption.IGNORE_CASE).containsMatchIn(outer.substringAfter(" FROM ", outer))
    }

    fun hasUnconstrainedSubquery(query: String): Boolean =
        SUBQUERY.findAll(query).any { !Regex("""\b(WHERE|LIMIT)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.value) }

    fun countQuery(query: String): String {
        val withoutSubqueries = SUBQUERY.replace(query, "Id")
        val from = Regex("""\sFROM\s""", RegexOption.IGNORE_CASE).find(withoutSubqueries) ?: return query
        return "SELECT COUNT() " + TAIL.replace(withoutSubqueries.substring(from.range.first).trim(), "")
    }

    fun limit(query: String): Int? = clause(query, "LIMIT")

    fun offset(query: String): Int = clause(query, "OFFSET") ?: 0

    fun effectiveCount(query: String, total: Int): Int {
        val remaining = (total - offset(query)).coerceAtLeast(0)
        return limit(query)?.let { minOf(it, remaining) } ?: remaining
    }

    private fun clause(query: String, keyword: String): Int? =
        Regex("""\b$keyword\s+(\d+)\b""", RegexOption.IGNORE_CASE).findAll(SUBQUERY.replace(query, "Id"))
            .lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    fun selectedFields(query: String): List<String> =
        FIELDS.find(SUBQUERY.replace(query, ""))?.groupValues?.get(1).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.contains('(') }

    fun columns(query: String, rows: List<Map<String, String>>): List<String> {
        val keys = LinkedHashSet<String>()
        rows.forEach { keys.addAll(it.keys) }
        val order = selectedFields(query).map { it.lowercase() }
        return keys.sortedBy { key -> order.indexOf(key.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}

class SoqlQueryTab(project: Project, window: ReplWindow, state: ReplTabState) : ReplTab(project, window, state) {
    private val kindSettings = window.settings
    private val results = QueryResultsPanel(
        project, this, window.kind.notificationGroup, { connection.selectedKey },
        state.resultsSplitterProportion,
    ) { state.resultsSplitterProportion = it }

    override fun results(): JComponent = results.component

    override fun toolbar(): ActionGroup = SfUi.group(
        executeAction,
        SfUi.action("Export", "Export the query results to a CSV file", AllIcons.Nodes.DataTables, { results.hasResults }) {
            results.export("Export SOQL Query Results", state.name.orEmpty())
        },
        SfUi.action("Explain", "Show the query plan", AllIcons.Actions.IntentionBulbGrey, { !running }) { explain(editor.selectedOrAllText) },
        null,
        newTabAction,
        closeTabAction,
        SfUi.action("Copy Results", "Copy results", AllIcons.Actions.Copy, { results.hasResults }) { results.copyResults() },
        loadAction,
        saveAction,
        renameAction,
        SfUi.toggle("Use Tooling API", "When selected, the Tooling API is used for SOQL query execution.", AllIcons.General.ExternalTools, { state.useToolingApi }) {
            state.useToolingApi = it
        },
        SfUi.toggle("Query All", "When selected, deleted rows are included in the results (Partner API-only).", AllIcons.Actions.Show, { state.queryAll }) {
            state.queryAll = it
        },
        SfUi.toggle("Enable Query Validation", "When deselected, query validation is disabled for this tab.", VALIDATOR_ICON, { state.validation }) {
            state.validation = it
        },
        configureAction("Configure SOQL Query settings"),
    )

    override fun execute(text: String) {
        val query = SoqlText.normalize(text)
        if (running) return
        if (query.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(project, "No SOQL query found.", SfUi.TITLE)
            return
        }
        if (SoqlText.isToolingOnly(query) && !state.useToolingApi) state.useToolingApi = true
        val validate = state.validation
        if (validate && kindSettings.validateSyntax && !SoqlText.isValid(query) &&
            !confirm("The SOQL query does not appear valid. Would you still like to execute it?")
        ) {
            return
        }
        if (validate && kindSettings.validateSubqueries && !SoqlText.isConstrained(query) && SoqlText.hasUnconstrainedSubquery(query) &&
            !confirm("The SOQL query includes sub-queries, and some of the queries do not include `WHERE` or `LIMIT` clauses. This can result in slow query result transfer and processing times. Would you still like to execute it?")
        ) {
            return
        }
        running = true
        val org = connection.selectedKey
        val tooling = state.useToolingApi
        val all = state.queryAll && !tooling
        results.clear()
        val api = if (tooling) "Tooling API" else "Partner API"
        val connectionName = connection.selectedOrg?.displayName ?: org ?: "default org"
        results.print("Executing query against $connectionName using the $api:\n$query\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        SfUi.background(project, "Executing SOQL Query", onError = { message ->
            running = false
            SfUi.edt(project) { results.print("$message\n", ConsoleViewContentType.ERROR_OUTPUT) }
        }) { indicator ->
            if (validate && !preflight(org, query, tooling, all, indicator)) {
                running = false
                SfUi.edt(project) { results.print("Query execution canceled.\n", ConsoleViewContentType.SYSTEM_OUTPUT) }
                return@background
            }
            val started = System.currentTimeMillis()
            val result = SfApi.getInstance(project).query(org, query, tooling, all, indicator = indicator)
            val rows = result.records.map { QueryResultsPanel.flatten(it) }
            val columns = SoqlText.columns(query, rows)
            val elapsed = System.currentTimeMillis() - started
            SfUi.edt(project) {
                running = false
                results.show(result.records, columns, rows)
                val message = when {
                    result.records.isEmpty() && result.totalSize > 0 -> "${result.totalSize} rows counted."
                    result.records.isEmpty() -> "No rows returned."
                    else -> "${result.records.size} rows returned."
                }
                results.print("$message ($elapsed ms)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        }
    }

    private fun preflight(org: String?, query: String, tooling: Boolean, all: Boolean, indicator: ProgressIndicator): Boolean {
        if (SoqlText.selectedFields(query).any { it.equals("COUNT()", true) }) return true
        val unconstrained = !SoqlText.isConstrained(query)
        if (kindSettings.validateBlobFields && unconstrained) {
            val sobject = SoqlText.sobject(query)
            if (sobject != null) {
                val blobs = runCatching {
                    SfApi.getInstance(project).describe(org, sobject, tooling, indicator).arr("fields").objects()
                        .filter { it.str("type") == "base64" }.mapNotNull { it.str("name")?.lowercase() }
                }.getOrDefault(emptyList())
                if (SoqlText.selectedFields(query).any { it.lowercase() in blobs } &&
                    !confirmLater("The SOQL query includes `Blob` fields but does not specify `WHERE` or `LIMIT` clauses. This can result in slow query result transfer and processing times. Would you still like to execute it?")
                ) {
                    return false
                }
            }
        }
        val checkSize = kindSettings.validateResultSize && (!unconstrained || kindSettings.validateUnconstrained)
        if (!checkSize) return true
        val max = kindSettings.maxResultSize
        if ((SoqlText.limit(query) ?: Int.MAX_VALUE) <= max) return true
        indicator.text2 = "Counting Rows"
        val count = try {
            SoqlText.effectiveCount(query, SfApi.getInstance(project).query(org, SoqlText.countQuery(query), tooling, all, maxRows = 0, indicator = indicator).totalSize)
        } catch (e: SfApiException) {
            return true
        }
        return count <= max || confirmLater(
            "The SOQL query would return $count rows which is more than the configured maximum result set size of $max. " +
                "This can result in slow query result transfer and processing times. Would you still like to execute it?",
        )
    }

    private fun explain(text: String) {
        val query = SoqlText.normalize(text)
        if (query.isEmpty()) return
        val org = connection.selectedKey
        results.clear()
        results.print("Explaining:\n$query\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        SfUi.background(project, "Explaining SOQL Query", onError = { message ->
            SfUi.edt(project) {
                results.print("Failed to retrieve query plan(s). This is likely due to a malformed query:\n$message\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
        }) { indicator ->
            val plans = SfApi.getInstance(project).explain(org, query, indicator).arr("plans").objects()
            val output = if (plans.isEmpty()) "No query plan(s) returned.\n" else buildString {
                plans.forEachIndexed { index, plan -> appendPlan(index + 1, plan) }
            }
            SfUi.edt(project) { results.print(output) }
        }
    }

    private fun StringBuilder.appendPlan(index: Int, plan: JsonObject) {
        appendLine("Plan $index")
        appendLine("  Leading operation type: ${plan.str("leadingOperationType").orEmpty()}")
        appendLine("  SObject type: ${plan.str("sobjectType").orEmpty()}")
        appendLine("  Field(s): ${plan.arr("fields")?.joinToString(", ") { it.asString }.orEmpty()}")
        appendLine("  Relative cost: ${plan.str("relativeCost").orEmpty()}")
        appendLine("  Cardinality: ${plan.str("cardinality").orEmpty()}")
        appendLine("  SObject cardinality: ${plan.str("sobjectCardinality").orEmpty()}")
        val notes = plan.arr("notes").objects()
        if (notes.isNotEmpty()) {
            appendLine("  Notes:")
            notes.forEach { note ->
                appendLine("    Table: ${note.str("tableEnumOrId").orEmpty()}")
                appendLine("    - ${note.str("description").orEmpty()}")
            }
        }
        appendLine()
    }

    private fun confirm(message: String): Boolean =
        MessageDialogBuilder.yesNo(SfUi.TITLE, message)
            .doNotAsk(object : DoNotAskOption.Adapter() {
                override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                    if (isSelected) state.validation = false
                }

                override fun getDoNotShowMessage(): String = "Disable validation for this tab"
            })
            .ask(project)

    private fun confirmLater(message: String): Boolean {
        var answer = false
        ApplicationManager.getApplication().invokeAndWait { answer = confirm(message) }
        return answer
    }
}

class SoslQueryTab(project: Project, window: ReplWindow, state: ReplTabState) : ReplTab(project, window, state) {
    private val results = QueryResultsPanel(
        project, this, window.kind.notificationGroup, { connection.selectedKey },
        state.resultsSplitterProportion,
    ) { state.resultsSplitterProportion = it }

    override fun results(): JComponent = results.component

    override fun toolbar(): ActionGroup = SfUi.group(
        executeAction,
        SfUi.action("Export", "Export the search results to a CSV file", AllIcons.Nodes.DataTables, { results.hasResults }) {
            results.export("Export SOSL Query Results", state.name.orEmpty())
        },
        null,
        newTabAction,
        closeTabAction,
        SfUi.action("Copy Results", "Copy results", AllIcons.Actions.Copy, { results.hasResults }) { results.copyResults() },
        loadAction,
        saveAction,
        renameAction,
        configureAction("Configure SOSL Query settings"),
    )

    override fun execute(text: String) {
        val query = SoqlText.normalize(text)
        if (running) return
        if (query.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(project, "No SOSL query found.", SfUi.TITLE)
            return
        }
        if (window.settings.validateSyntax && !Regex("""^\s*FIND\s+""", RegexOption.IGNORE_CASE).containsMatchIn(query) &&
            !MessageDialogBuilder.yesNo(SfUi.TITLE, "The SOSL query does not appear valid. Would you still like to execute it?").ask(project)
        ) {
            return
        }
        running = true
        val org = connection.selectedKey
        results.clear()
        val connectionName = connection.selectedOrg?.displayName ?: org ?: "default org"
        results.print("Executing search against $connectionName:\n$query\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        SfUi.background(project, "Executing SOSL Query", onError = { message ->
            running = false
            SfUi.edt(project) { results.print("SOSL query failed.\n$message\n", ConsoleViewContentType.ERROR_OUTPUT) }
        }) { indicator ->
            val records = SfApi.getInstance(project).search(org, query, indicator)
            val rows = records.map { record ->
                linkedMapOf("Type" to (record.getAsJsonObject("attributes")?.get("type")?.asString ?: "")) + QueryResultsPanel.flatten(record)
            }
            val columns = LinkedHashSet<String>().apply { rows.forEach { addAll(it.keys) } }.toList()
            SfUi.edt(project) {
                running = false
                results.show(records, columns, rows, typeGroups = true)
                results.print(if (records.isEmpty()) "No search result found.\n" else "${records.size} results returned.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        }
    }
}
