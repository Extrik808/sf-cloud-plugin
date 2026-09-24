package dev.sfcloud.log

import com.intellij.util.ui.ColumnInfo
import java.util.Locale
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode

class LogTreeNode(value: Any, private val childrenOf: (Any) -> List<Any>) : DefaultMutableTreeNode(value) {
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        childrenOf(userObject).forEach { super.insert(LogTreeNode(it, childrenOf), super.getChildCount()) }
    }

    override fun getChildCount(): Int {
        load()
        return super.getChildCount()
    }

    override fun getChildAt(index: Int): TreeNode {
        load()
        return super.getChildAt(index)
    }

    override fun children(): java.util.Enumeration<TreeNode> {
        load()
        return super.children()
    }

    override fun isLeaf(): Boolean = if (loaded) super.getChildCount() == 0 else childrenOf(userObject).isEmpty()
}

class LogColumn(
    val name: String,
    private val sample: String,
    private val value: (Any, ApexLog) -> String,
    private val tooltip: String? = null,
) {
    fun bind(log: ApexLog): ColumnInfo<Any, String> = object : ColumnInfo<Any, String>(name) {
        override fun valueOf(item: Any?): String {
            val node = (item as? DefaultMutableTreeNode)?.userObject ?: item ?: return ""
            return value(node, log)
        }

        override fun getPreferredStringValue(): String = sample

        override fun getTooltipText(): String? = tooltip

        override fun getRenderer(item: Any?): TableCellRenderer = if (name == "Details") LEFT else RIGHT
    }

    companion object {
        private val RIGHT = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }
        private val LEFT = DefaultTableCellRenderer()
    }
}

object LogColumns {
    private fun millis(nanos: Long): String = String.format(Locale.US, "%.2f ms", nanos / 1_000_000.0)

    private fun limit(self: Long, total: Long, max: Long): String = if (self == 0L && total == 0L) "0" else "$self/$total of $max"

    private fun log(item: Any): LogNode? = item as? LogNode

    private fun aggregate(item: Any): AggregateNode? = item as? AggregateNode

    fun details(item: Any): String = when (item) {
        is LogNode -> {
            val event = item.event
            when {
                event == null -> ""
                event.type == "LIMIT_USAGE" -> event.payload.drop(1).chunked(2).joinToString(", ") { it.joinToString("/") }
                else -> item.displayName.replace(WHITESPACE, " ")
            }
        }
        is AggregateNode -> item.key.name
        else -> ""
    }

    val DETAILS = LogColumn("Details", "SELECT Id, Name FROM Account WHERE Name = :name", { item, _ -> details(item) })
    val TIMESTAMP = LogColumn("Timestamp", "12:34:56.789", { item, _ -> log(item)?.event?.time.orEmpty() })
    val SINCE_START = LogColumn("Time Since Start", "123456789 ms", { item, _ -> log(item)?.event?.let { millis(it.nanos) }.orEmpty() })
    val DURATION_TOTAL = LogColumn("Duration (Total)", "10000 ms", { item, _ -> log(item)?.takeIf { it.isBlock }?.let { millis(it.durationNanos) }.orEmpty() })
    val DURATION_SELF = LogColumn("Duration (Self)", "10000 ms", { item, _ -> log(item)?.takeIf { it.isBlock }?.let { millis(it.selfNanos) }.orEmpty() })
    val HEAP_TOTAL = LogColumn("Heap (Total)", "10000000 bytes", { item, _ -> log(item)?.let { "${it.totalMetrics.heap} bytes" }.orEmpty() })
    val HEAP_SELF = LogColumn("Heap (Self)", "10000000 bytes", { item, _ -> log(item)?.let { "${it.selfMetrics.heap} bytes" }.orEmpty() })
    val SOQL_QUERIES = LogColumn("SOQL Queries", "12345/12345 of 12345", { item, l -> log(item)?.let { limit(it.selfMetrics.soqlQueries, it.totalMetrics.soqlQueries, l.maxSoqlQueries) }.orEmpty() }, "Self/Total of Limit")
    val SOQL_ROWS = LogColumn("SOQL Rows", "12345/12345 of 12345", { item, l -> log(item)?.let { limit(it.selfMetrics.soqlRows, it.totalMetrics.soqlRows, l.maxSoqlRows) }.orEmpty() }, "Self/Total of Limit")
    val AGGREGATIONS = LogColumn("Aggregations", "12345/12345 of 12345", { item, l -> log(item)?.let { limit(it.selfMetrics.aggregations, it.totalMetrics.aggregations, l.maxAggregations) }.orEmpty() }, "Self/Total of Limit")
    val DML_STATEMENTS = LogColumn("DML Statements", "12345/12345 of 12345", { item, l -> log(item)?.let { limit(it.selfMetrics.dmlStatements, it.totalMetrics.dmlStatements, l.maxDmlStatements) }.orEmpty() }, "Self/Total of Limit")
    val DML_ROWS = LogColumn("DML Rows", "12345/12345 of 12345", { item, l -> log(item)?.let { limit(it.selfMetrics.dmlRows, it.totalMetrics.dmlRows, l.maxDmlRows) }.orEmpty() }, "Self/Total of Limit")

    val INVOCATIONS = LogColumn("Invocation Count", "1234567", { item, _ -> aggregate(item)?.count?.toString().orEmpty() })
    val A_DURATION_TOTAL = LogColumn("Duration (Total)", "10000 ms (100.00%)", { item, _ -> aggregate(item)?.let { millis(it.totalNanos) }.orEmpty() })
    val A_DURATION_SELF = LogColumn("Duration (Self)", "10000 ms", { item, _ -> aggregate(item)?.let { millis(it.selfNanos) }.orEmpty() })
    val A_HEAP_TOTAL = LogColumn("Heap (Total)", "10000000 bytes", { item, _ -> aggregate(item)?.let { "${it.totalMetrics.heap} bytes" }.orEmpty() })
    val A_HEAP_SELF = LogColumn("Heap (Self)", "10000000 bytes", { item, _ -> aggregate(item)?.let { "${it.selfMetrics.heap} bytes" }.orEmpty() })
    val A_SOQL_TOTAL = LogColumn("SOQL Queries (Total)", "1234567", { item, _ -> aggregate(item)?.totalMetrics?.soqlQueries?.toString().orEmpty() })
    val A_SOQL_SELF = LogColumn("SOQL Queries (Self)", "1234567", { item, _ -> aggregate(item)?.selfMetrics?.soqlQueries?.toString().orEmpty() })
    val A_SOQL_ROWS_TOTAL = LogColumn("SOQL Rows (Total)", "1234567", { item, _ -> aggregate(item)?.totalMetrics?.soqlRows?.toString().orEmpty() })
    val A_SOQL_ROWS_SELF = LogColumn("SOQL Rows (Self)", "1234567", { item, _ -> aggregate(item)?.selfMetrics?.soqlRows?.toString().orEmpty() })
    val A_AGGREGATIONS_TOTAL = LogColumn("Aggregations (Total)", "1234567", { item, _ -> aggregate(item)?.totalMetrics?.aggregations?.toString().orEmpty() })
    val A_DML_TOTAL = LogColumn("DML Statements (Total)", "1234567", { item, _ -> aggregate(item)?.totalMetrics?.dmlStatements?.toString().orEmpty() })
    val A_DML_SELF = LogColumn("DML Statements (Self)", "1234567", { item, _ -> aggregate(item)?.selfMetrics?.dmlStatements?.toString().orEmpty() })
    val A_DML_ROWS_TOTAL = LogColumn("DML Rows (Total)", "1234567", { item, _ -> aggregate(item)?.totalMetrics?.dmlRows?.toString().orEmpty() })
    val A_DML_ROWS_SELF = LogColumn("DML Rows (Self)", "1234567", { item, _ -> aggregate(item)?.selfMetrics?.dmlRows?.toString().orEmpty() })

    private val WHITESPACE = Regex("\\s+")
}

enum class LogTreeView(
    val label: String,
    val description: String,
    val analysis: Boolean,
    val columns: () -> List<LogColumn>,
    val keep: ((LogEvent) -> Boolean)?,
    val prune: Boolean = false,
    val flat: Boolean = false,
) {
    STANDARD(
        "Standard Log", "Standard log review with event, details, and timestamp columns and all event types.", false,
        { listOf(LogColumns.DETAILS, LogColumns.TIMESTAMP) }, null,
    ),
    DEBUG_ONLY(
        "Debug Only", "Shows only USER_DEBUG events.", false,
        { listOf(LogColumns.DETAILS, LogColumns.TIMESTAMP) }, { it.type == "USER_DEBUG" }, flat = true,
    ),
    CPU_AND_MEMORY(
        "CPU Time and Memory Profiling", "Optimized for CPU time and memory profiling.", false,
        { listOf(LogColumns.DETAILS, LogColumns.DURATION_TOTAL, LogColumns.DURATION_SELF, LogColumns.HEAP_TOTAL, LogColumns.HEAP_SELF, LogColumns.SINCE_START) },
        { it.category != LogCategory.STATEMENT || it.type == "HEAP_ALLOCATE" },
    ),
    CPU(
        "CPU Time Profiling", "Optimized for CPU time profiling.", false,
        { listOf(LogColumns.DETAILS, LogColumns.DURATION_TOTAL, LogColumns.DURATION_SELF, LogColumns.SINCE_START) },
        { it.category != LogCategory.STATEMENT },
    ),
    MEMORY(
        "Memory Profiling", "Log view optimized for memory profiling.", false,
        { listOf(LogColumns.DETAILS, LogColumns.HEAP_TOTAL, LogColumns.HEAP_SELF, LogColumns.SINCE_START) },
        { it.type == "HEAP_ALLOCATE" }, prune = true,
    ),
    DATABASE(
        "Database Profiling", "Optimized for query and DML profiling.", false,
        { listOf(LogColumns.DETAILS, LogColumns.SOQL_QUERIES, LogColumns.SOQL_ROWS, LogColumns.AGGREGATIONS, LogColumns.DML_STATEMENTS, LogColumns.DML_ROWS, LogColumns.DURATION_TOTAL) },
        { it.category == LogCategory.SOQL || it.category == LogCategory.DML || it.type.startsWith("LIMIT_USAGE") || it.type.startsWith("CUMULATIVE_LIMIT_USAGE") || it.type == "EXCEPTION_THROWN" },
        prune = true,
    ),
    CUMULATIVE(
        "Cumulative Usage Limits and Profiling", "Optimized for cumulative usage limits and profiling data review.", false,
        { listOf(LogColumns.DETAILS, LogColumns.TIMESTAMP, LogColumns.DURATION_TOTAL) },
        { it.category == LogCategory.EXECUTION || it.category == LogCategory.CODE_UNIT || it.category == LogCategory.LIMITS || it.type == "TOTAL_EMAIL_RECIPIENTS_QUEUED" || it.type == "STATIC_VARIABLE_LIST" || it.type.startsWith("CUMULATIVE_PROFILING") },
        prune = true,
    ),
    CALLER_ANALYSIS(
        "Caller Analysis", "Optimized for caller resource usage analysis.", true,
        { listOf(LogColumns.INVOCATIONS, LogColumns.A_DURATION_TOTAL, LogColumns.A_HEAP_TOTAL, LogColumns.A_SOQL_TOTAL, LogColumns.A_DML_TOTAL) }, null,
    ),
    CPU_AND_MEMORY_ANALYSIS(
        "CPU Time and Memory Analysis", "Optimized for callee CPU time and memory usage analysis.", true,
        { listOf(LogColumns.INVOCATIONS, LogColumns.A_DURATION_TOTAL, LogColumns.A_DURATION_SELF, LogColumns.A_HEAP_TOTAL, LogColumns.A_HEAP_SELF) }, null,
    ),
    CPU_ANALYSIS(
        "CPU Time Analysis", "Optimized for callee CPU time usage analysis.", true,
        { listOf(LogColumns.INVOCATIONS, LogColumns.A_DURATION_TOTAL, LogColumns.A_DURATION_SELF) }, null,
    ),
    MEMORY_ANALYSIS(
        "Memory Analysis", "Optimized for callee memory usage analysis.", true,
        { listOf(LogColumns.INVOCATIONS, LogColumns.A_HEAP_TOTAL, LogColumns.A_HEAP_SELF) }, null,
    ),
    DATABASE_ANALYSIS(
        "Database Analysis", "Optimized for callee database resource usage analysis.", true,
        {
            listOf(
                LogColumns.INVOCATIONS, LogColumns.A_SOQL_TOTAL, LogColumns.A_SOQL_SELF, LogColumns.A_SOQL_ROWS_TOTAL,
                LogColumns.A_SOQL_ROWS_SELF, LogColumns.A_AGGREGATIONS_TOTAL, LogColumns.A_DML_TOTAL, LogColumns.A_DML_SELF,
                LogColumns.A_DML_ROWS_TOTAL, LogColumns.A_DML_ROWS_SELF,
            )
        },
        null,
    );

    fun root(log: ApexLog): LogNode {
        val keep = keep ?: return log.root
        if (flat) {
            val root = LogNode(null, null)
            log.events.filter(keep).forEach { root.children += LogNode(it, root) }
            return root
        }
        return select(log.root, keep, null) ?: LogNode(null, null)
    }

    private fun select(node: LogNode, keep: (LogEvent) -> Boolean, parent: LogNode?): LogNode? {
        val copy = LogNode(node.event, parent)
        copy.end = node.end
        copy.endNanos = node.endNanos
        node.children.forEach { child ->
            val event = child.event ?: return@forEach
            when {
                child.isBlock -> select(child, keep, copy)?.let { copy.children += it }
                keep(event) -> copy.children += child
            }
        }
        val event = node.event
        if (prune && event != null && copy.children.isEmpty() && !keep(event)) return null
        return copy
    }

    companion object {
        fun of(name: String?, analysis: Boolean): LogTreeView =
            entries.firstOrNull { it.name == name && it.analysis == analysis } ?: if (analysis) CPU_AND_MEMORY_ANALYSIS else STANDARD
    }
}

enum class ContextKind(val label: String, val defaultView: LogTreeView) {
    CALLERS("Callers", LogTreeView.CALLER_ANALYSIS),
    CALLEES("Callees", LogTreeView.CPU_AND_MEMORY_ANALYSIS),
    MERGED_CALLEES("Merged Callees", LogTreeView.CPU_AND_MEMORY_ANALYSIS);

    fun build(invocations: List<LogNode>): AggregateNode = when (this) {
        CALLERS -> AggregateNode.callers(invocations)
        CALLEES -> AggregateNode.callees(invocations)
        MERGED_CALLEES -> AggregateNode.mergedCallees(invocations)
    }
}
