package dev.sfcloud.log

enum class LogCategory(val label: String) {
    EXECUTION("Execution"),
    CODE_UNIT("Code unit"),
    METHOD("Method"),
    SYSTEM("System method"),
    SOQL("SOQL / SOSL"),
    DML("DML"),
    CALLOUT("Callout"),
    DEBUG("Debug"),
    ERROR("Error"),
    LIMITS("Limits"),
    AUTOMATION("Flow / workflow / validation"),
    STATEMENT("Statement / variable"),
    OTHER("Other"),
}

data class LogEvent(
    val index: Int,
    val line: Int,
    val time: String,
    val nanos: Long,
    val type: String,
    val fields: List<String>,
    val details: String,
) {
    val category: LogCategory = ApexLogParser.categoryOf(type)

    val sourceLine: Int? by lazy {
        fields.firstNotNullOfOrNull { field ->
            if (field.length > 2 && field.first() == '[' && field.last() == ']') field.substring(1, field.length - 1).toIntOrNull() else null
        }
    }

    val payload: List<String> by lazy { fields.filterNot { ApexLogParser.isLineRef(it) } }

    val name: String by lazy {
        when (type) {
            "DML_BEGIN" -> {
                val values = payload.associate { it.substringBefore(':') to it.substringAfter(':', "") }
                listOfNotNull(values["Op"], values["Type"], values["Rows"]?.let { "($it rows)" }).joinToString(" ")
            }
            "USER_DEBUG" -> payload.drop(1).joinToString("|").ifEmpty { details }
            "EXCEPTION_THROWN", "FATAL_ERROR" -> payload.joinToString("|").ifEmpty { details }
            "LIMIT_USAGE_FOR_NS" -> payload.firstOrNull().orEmpty()
            else -> payload.lastOrNull { it.isNotBlank() && !it.matches(ID_PATTERN) }.orEmpty()
        }
    }

    val message: String
        get() = if (details.isEmpty()) name else name + "\n" + details

    fun number(key: String): Long = payload.firstNotNullOfOrNull { field ->
        if (field.startsWith("$key:")) field.substringAfter(':').trim().toLongOrNull() else null
    } ?: 0

    companion object {
        private val ID_PATTERN = Regex("[a-zA-Z0-9]{15}|[a-zA-Z0-9]{18}")
    }
}

class LogNode(val event: LogEvent?, val parent: LogNode?) {
    val children = mutableListOf<LogNode>()
    var end: LogEvent? = null
    var endNanos: Long = event?.nanos ?: 0

    val startNanos: Long get() = event?.nanos ?: 0

    val durationNanos: Long get() = (endNanos - startNanos).coerceAtLeast(0)

    val selfNanos: Long get() = (durationNanos - children.sumOf { it.durationNanos }).coerceAtLeast(0)

    val isBlock: Boolean get() = event != null && event.type in ApexLogParser.BLOCKS

    val hasError: Boolean by lazy { event?.category == LogCategory.ERROR || children.any { it.hasError } }

    val selfMetrics: NodeMetrics by lazy {
        var metrics = own()
        children.forEach { child -> if (!child.isBlock || child.event?.category in DIRECT_METRIC_CATEGORIES) metrics += child.own() }
        metrics
    }

    val totalMetrics: NodeMetrics by lazy {
        var metrics = own()
        children.forEach { child -> metrics += if (child.isBlock) child.totalMetrics else child.own() }
        metrics
    }

    val displayName: String
        get() = event?.name?.lineSequence()?.firstOrNull().orEmpty().ifEmpty { event?.type.orEmpty() }

    private fun own(): NodeMetrics {
        val event = event ?: return NodeMetrics.ZERO
        return when (event.type) {
            "HEAP_ALLOCATE" -> NodeMetrics(heap = event.number("Bytes"))
            "SOQL_EXECUTE_BEGIN" -> NodeMetrics(
                soqlQueries = 1,
                soqlRows = end?.number("Rows") ?: 0,
                aggregations = event.number("Aggregations"),
            )
            "DML_BEGIN" -> NodeMetrics(dmlStatements = 1, dmlRows = event.number("Rows"))
            else -> NodeMetrics.ZERO
        }
    }

    companion object {
        private val DIRECT_METRIC_CATEGORIES = setOf(LogCategory.SOQL, LogCategory.DML)
    }

    fun filtered(keep: (LogEvent) -> Boolean, parent: LogNode? = null): LogNode {
        val copy = LogNode(event, parent)
        copy.end = end
        copy.endNanos = endNanos
        children.forEach { child ->
            val childEvent = child.event ?: return@forEach
            if (child.isBlock || keep(childEvent)) copy.children += child.filtered(keep, copy)
        }
        return copy
    }
}

data class NodeMetrics(
    val heap: Long = 0,
    val soqlQueries: Long = 0,
    val soqlRows: Long = 0,
    val aggregations: Long = 0,
    val dmlStatements: Long = 0,
    val dmlRows: Long = 0,
) {
    operator fun plus(other: NodeMetrics) = NodeMetrics(
        heap + other.heap,
        soqlQueries + other.soqlQueries,
        soqlRows + other.soqlRows,
        aggregations + other.aggregations,
        dmlStatements + other.dmlStatements,
        dmlRows + other.dmlRows,
    )

    companion object {
        val ZERO = NodeMetrics()
    }
}

data class LimitUsage(val namespace: String, val name: String, val used: Long, val max: Long) {
    val percent: Int get() = if (max <= 0) 0 else ((used * 100) / max).toInt()
}

data class Hotspot(
    val category: LogCategory,
    val name: String,
    val count: Int,
    val totalNanos: Long,
    val selfNanos: Long,
    val firstLine: Int,
)

class ApexLog(
    val apiVersion: String?,
    val logLevels: Map<String, String>,
    val events: List<LogEvent>,
    val root: LogNode,
    val limits: List<LimitUsage>,
) {
    val totalNanos: Long get() = root.durationNanos

    val debugEvents: List<LogEvent> get() = events.filter { it.category == LogCategory.DEBUG || it.category == LogCategory.ERROR }

    fun limit(name: String, fallback: Long): Long = limits.firstOrNull { it.name.equals(name, true) }?.max ?: fallback

    val maxSoqlQueries: Long get() = limit("Number of SOQL queries", 100)
    val maxSoqlRows: Long get() = limit("Number of query rows", 50000)
    val maxAggregations: Long get() = limit("Number of aggregate queries", 300)
    val maxDmlStatements: Long get() = limit("Number of DML statements", 150)
    val maxDmlRows: Long get() = limit("Number of DML rows", 10000)

    fun invocations(key: AggregateKey): List<LogNode> {
        val result = mutableListOf<LogNode>()
        fun visit(node: LogNode) {
            if (node.isBlock && AggregateKey.of(node) == key) result += node
            node.children.forEach { visit(it) }
        }
        visit(root)
        return result
    }

    fun count(category: LogCategory): Int = events.count { it.category == category && it.type !in ApexLogParser.ENDS }

    fun hotspots(): List<Hotspot> {
        val totals = LinkedHashMap<Pair<LogCategory, String>, HotspotTotals>()
        fun visit(node: LogNode, open: Set<Pair<LogCategory, String>>) {
            val event = node.event
            var nested = open
            if (event != null && node.isBlock && event.category in HOTSPOT_CATEGORIES) {
                val key = event.category to event.name
                val entry = totals.getOrPut(key) { HotspotTotals(event.line) }
                entry.count++
                entry.self += node.selfNanos
                if (key !in open) {
                    entry.total += node.durationNanos
                    nested = open + key
                }
            }
            node.children.forEach { visit(it, nested) }
        }
        visit(root, emptySet())
        return totals
            .map { (key, value) -> Hotspot(key.first, key.second, value.count, value.total, value.self, value.firstLine) }
            .sortedByDescending { it.selfNanos }
    }

    private class HotspotTotals(val firstLine: Int) {
        var count = 0
        var total = 0L
        var self = 0L
    }

    companion object {
        private val HOTSPOT_CATEGORIES = setOf(LogCategory.CODE_UNIT, LogCategory.METHOD, LogCategory.SOQL, LogCategory.DML, LogCategory.CALLOUT, LogCategory.AUTOMATION)
    }
}

object ApexLogParser {
    val EVENT_LINE = Regex("""^(\d{1,2}:\d{2}:\d{2}\.\d+) \((\d+)\)\|([A-Z_0-9]+)(?:\|(.*))?$""")
    private val HEADER_LINE = Regex("""^(\d+\.\d+) (\S+)$""")
    private val LIMIT_LINE = Regex("""^\s*(?:\*+\s*)?(.+?): (\d+) out of (\d+)""")
    private val LINE_REF = Regex("""\[(\d+|EXTERNAL)]""")

    val PAIRS: Map<String, String> = mapOf(
        "EXECUTION_STARTED" to "EXECUTION_FINISHED",
        "CODE_UNIT_STARTED" to "CODE_UNIT_FINISHED",
        "METHOD_ENTRY" to "METHOD_EXIT",
        "CONSTRUCTOR_ENTRY" to "CONSTRUCTOR_EXIT",
        "SYSTEM_METHOD_ENTRY" to "SYSTEM_METHOD_EXIT",
        "SYSTEM_CONSTRUCTOR_ENTRY" to "SYSTEM_CONSTRUCTOR_EXIT",
        "SYSTEM_MODE_ENTER" to "SYSTEM_MODE_EXIT",
        "SOQL_EXECUTE_BEGIN" to "SOQL_EXECUTE_END",
        "SOSL_EXECUTE_BEGIN" to "SOSL_EXECUTE_END",
        "DML_BEGIN" to "DML_END",
        "CALLOUT_REQUEST" to "CALLOUT_RESPONSE",
        "NAMED_CREDENTIAL_REQUEST" to "NAMED_CREDENTIAL_RESPONSE",
        "VF_APEX_CALL_START" to "VF_APEX_CALL_END",
        "FLOW_START_INTERVIEW_BEGIN" to "FLOW_START_INTERVIEW_END",
        "FLOW_ELEMENT_BEGIN" to "FLOW_ELEMENT_END",
        "WF_RULE_EVAL_BEGIN" to "WF_RULE_EVAL_END",
        "CUMULATIVE_LIMIT_USAGE" to "CUMULATIVE_LIMIT_USAGE_END",
        "CUMULATIVE_PROFILING_BEGIN" to "CUMULATIVE_PROFILING_END",
    )
    val BLOCKS: Set<String> = PAIRS.keys
    val ENDS: Set<String> = PAIRS.values.toSet()
    private val BLOCK_BY_END: Map<String, String> = PAIRS.entries.associate { (begin, end) -> end to begin }

    fun isLineRef(field: String): Boolean = LINE_REF.matches(field)

    fun categoryOf(type: String): LogCategory = when {
        type.startsWith("EXECUTION_") -> LogCategory.EXECUTION
        type.startsWith("CODE_UNIT_") -> LogCategory.CODE_UNIT
        type.startsWith("METHOD_") || type.startsWith("CONSTRUCTOR_") -> LogCategory.METHOD
        type.startsWith("SYSTEM_") -> LogCategory.SYSTEM
        type.startsWith("SOQL_") || type.startsWith("SOSL_") || type.startsWith("QUERY_MORE") -> LogCategory.SOQL
        type.startsWith("DML_") -> LogCategory.DML
        type.startsWith("CALLOUT_") || type.startsWith("NAMED_CREDENTIAL_") -> LogCategory.CALLOUT
        type == "USER_DEBUG" -> LogCategory.DEBUG
        type == "EXCEPTION_THROWN" || type == "FATAL_ERROR" || type.endsWith("_ERROR") -> LogCategory.ERROR
        type.contains("LIMIT") || type.startsWith("CUMULATIVE_") -> LogCategory.LIMITS
        type.startsWith("FLOW_") || type.startsWith("WF_") || type.startsWith("VALIDATION_") -> LogCategory.AUTOMATION
        type.startsWith("STATEMENT_") || type.startsWith("VARIABLE_") || type.startsWith("HEAP_") -> LogCategory.STATEMENT
        else -> LogCategory.OTHER
    }

    fun parse(text: CharSequence): ApexLog {
        var apiVersion: String? = null
        val levels = LinkedHashMap<String, String>()
        val events = ArrayList<LogEvent>()
        var pending: PendingEvent? = null

        fun flush() {
            val current = pending ?: return
            events += LogEvent(events.size, current.line, current.time, current.nanos, current.type, current.fields, current.details.toString().trimEnd('\n'))
            pending = null
        }

        text.lineSequence().forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.trimEnd('\r')
            val match = EVENT_LINE.matchEntire(line)
            if (match != null) {
                flush()
                val (time, nanos, type, rest) = match.destructured
                pending = PendingEvent(lineIndex, time, nanos.toLongOrNull() ?: 0, type, if (match.groups[4] == null) emptyList() else rest.split('|'))
                return@forEachIndexed
            }
            if (pending == null && apiVersion == null && events.isEmpty()) {
                HEADER_LINE.matchEntire(line.trim())?.let { header ->
                    apiVersion = header.groupValues[1]
                    header.groupValues[2].split(';').forEach { part ->
                        val category = part.substringBefore(',', "")
                        val level = part.substringAfter(',', "")
                        if (category.isNotEmpty() && level.isNotEmpty()) levels[category] = level
                    }
                    return@forEachIndexed
                }
            }
            pending?.details?.append(line)?.append('\n')
        }
        flush()

        val root = buildTree(events)
        return ApexLog(apiVersion, levels, events, root, limits(events))
    }

    private fun buildTree(events: List<LogEvent>): LogNode {
        val root = LogNode(null, null)
        val stack = ArrayDeque<LogNode>()
        stack.addLast(root)
        events.forEach { event ->
            val begin = BLOCK_BY_END[event.type]
            if (begin != null) {
                val openIndex = stack.indexOfLast { it.event?.type == begin }
                if (openIndex > 0) {
                    while (stack.size > openIndex) {
                        val closed = stack.removeLast()
                        closed.endNanos = event.nanos
                        if (stack.size == openIndex) closed.end = event
                    }
                    return@forEach
                }
            }
            val parent = stack.last()
            val node = LogNode(event, parent)
            parent.children += node
            if (event.type in BLOCKS) stack.addLast(node)
        }
        val last = events.lastOrNull()?.nanos ?: 0
        while (stack.size > 1) stack.removeLast().endNanos = last
        root.endNanos = last
        return root
    }

    private fun limits(events: List<LogEvent>): List<LimitUsage> {
        val usage = LinkedHashMap<Pair<String, String>, LimitUsage>()
        events.filter { it.type == "LIMIT_USAGE_FOR_NS" }.forEach { event ->
            val namespace = event.payload.firstOrNull().orEmpty()
            event.details.lineSequence().forEach { line ->
                val match = LIMIT_LINE.find(line) ?: return@forEach
                val (name, used, max) = match.destructured
                val limit = LimitUsage(namespace, name.trim(), used.toLong(), max.toLong())
                usage.merge(namespace to limit.name, limit) { old, new -> if (new.used >= old.used) new else old }
            }
        }
        return usage.values.toList()
    }

    private class PendingEvent(val line: Int, val time: String, val nanos: Long, val type: String, val fields: List<String>) {
        val details = StringBuilder()
    }
}

data class AggregateKey(val category: LogCategory, val name: String) {
    companion object {
        fun of(node: LogNode): AggregateKey = AggregateKey(node.event?.category ?: LogCategory.OTHER, node.displayName)
    }
}

class AggregateNode(val key: AggregateKey, val sample: LogNode) {
    val children = LinkedHashMap<AggregateKey, AggregateNode>()
    var count = 0
    var totalNanos = 0L
    var selfNanos = 0L
    var totalMetrics = NodeMetrics.ZERO
    var selfMetrics = NodeMetrics.ZERO

    fun add(node: LogNode, includeTotals: Boolean = true) {
        count++
        selfNanos += node.selfNanos
        selfMetrics += node.selfMetrics
        if (includeTotals) {
            totalNanos += node.durationNanos
            totalMetrics += node.totalMetrics
        }
    }

    fun child(node: LogNode): AggregateNode {
        val key = AggregateKey.of(node)
        return children.getOrPut(key) { AggregateNode(key, node) }
    }

    companion object {
        fun callees(invocations: List<LogNode>): AggregateNode {
            val root = AggregateNode(AggregateKey.of(invocations.first()), invocations.first())
            fun merge(target: AggregateNode, node: LogNode) {
                target.add(node)
                node.children.filter { it.isBlock }.forEach { merge(target.child(it), it) }
            }
            invocations.forEach { merge(root, it) }
            return root
        }

        fun mergedCallees(invocations: List<LogNode>): AggregateNode {
            val root = AggregateNode(AggregateKey.of(invocations.first()), invocations.first())
            invocations.forEach { root.add(it) }
            fun collect(node: LogNode, open: Set<AggregateKey>) {
                node.children.filter { it.isBlock }.forEach { child ->
                    val key = AggregateKey.of(child)
                    root.child(child).add(child, includeTotals = key !in open)
                    collect(child, open + key)
                }
            }
            invocations.forEach { collect(it, setOf(root.key)) }
            return root
        }

        fun callers(invocations: List<LogNode>): AggregateNode {
            val root = AggregateNode(AggregateKey.of(invocations.first()), invocations.first())
            invocations.forEach { invocation ->
                root.add(invocation)
                var target = root
                var caller = invocation.parent
                while (caller?.event != null) {
                    if (caller.isBlock && caller.event.category in CALLER_CATEGORIES) {
                        target = target.child(caller)
                        target.count++
                        target.totalNanos += invocation.durationNanos
                        target.selfNanos += invocation.selfNanos
                        target.totalMetrics += invocation.totalMetrics
                        target.selfMetrics += invocation.selfMetrics
                    }
                    caller = caller.parent
                }
            }
            return root
        }

        private val CALLER_CATEGORIES = setOf(LogCategory.CODE_UNIT, LogCategory.METHOD, LogCategory.AUTOMATION)
    }
}
