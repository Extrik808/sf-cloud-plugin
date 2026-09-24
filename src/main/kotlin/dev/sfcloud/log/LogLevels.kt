package dev.sfcloud.log

import com.google.gson.JsonObject
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import dev.sfcloud.api.SfApi
import dev.sfcloud.api.SfApiException
import dev.sfcloud.core.obj
import dev.sfcloud.core.str
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class LogCategoryKey(val label: String, val field: String, val soapCategory: String?) {
    APEX_CODE("Apex Code", "ApexCode", "Apex_code"),
    APEX_PROFILING("Apex Profiling", "ApexProfiling", "Apex_profiling"),
    CALLOUT("Callout", "Callout", "Callout"),
    DATA_ACCESS("Data Access", "DataAccess", null),
    DATABASE("Database", "Database", "Db"),
    NBA("NBA", "Nba", "Nba"),
    SYSTEM("System", "System", "System"),
    VALIDATION("Validation", "Validation", "Validation"),
    VISUALFORCE("Visualforce", "Visualforce", "Visualforce"),
    WAVE("Wave", "Wave", "Wave"),
    WORKFLOW("Workflow", "Workflow", "Workflow");

    val tooltip: String get() = "Log level for the $label log category."
}

enum class ApexLogLevel {
    NONE, ERROR, WARN, INFO, DEBUG, FINE, FINER, FINEST;

    val soapName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun parse(value: String?): ApexLogLevel? = entries.firstOrNull { it.name.equals(value, true) }
    }
}

enum class LogLevelPresetCategory(val label: String) { STANDARD("Standard"), DEBUG("Debug"), PROFILE("Profile") }

enum class LogLevelPreset(
    val label: String,
    val category: LogLevelPresetCategory,
    val description: String,
    levels: String,
    keys: Set<LogCategoryKey>?,
) {
    DEFAULT(
        "Default", LogLevelPresetCategory.STANDARD,
        "Logs Apex and System at the debug level and everything else at the info level.",
        "DEBUG INFO INFO INFO INFO INFO DEBUG INFO INFO INFO INFO", null,
    ),
    NONE(
        "None", LogLevelPresetCategory.STANDARD,
        "Disables all logging output; particularly useful for Apex class- and trigger-specific trace flags.",
        "NONE NONE NONE NONE NONE NONE NONE NONE NONE NONE NONE", null,
    ),
    FULL_DEBUGGING(
        "Full Debugging", LogLevelPresetCategory.DEBUG,
        "Steps through code and displays variable assignments. Highest likelihood of exceeding maximum debug log size.",
        "FINEST ERROR INFO INFO INFO ERROR FINEST ERROR FINE ERROR ERROR",
        setOf(LogCategoryKey.APEX_CODE, LogCategoryKey.CALLOUT, LogCategoryKey.SYSTEM, LogCategoryKey.VISUALFORCE),
    ),
    STEPPING_AND_CHECKPOINTS(
        "Stepping with Checkpoints", LogLevelPresetCategory.DEBUG,
        "Steps through code but only displays variable values from checkpoint information. Lower likelihood of exceeding maximum debug log size.",
        "FINER ERROR INFO ERROR ERROR ERROR ERROR ERROR FINE ERROR ERROR",
        setOf(LogCategoryKey.APEX_CODE, LogCategoryKey.CALLOUT, LogCategoryKey.VISUALFORCE),
    ),
    CHECKPOINTS_ONLY(
        "Checkpoints Only", LogLevelPresetCategory.DEBUG,
        "Stops code execution and displays variable values only at registered checkpoints. Lowest likelihood of exceeding maximum debug log size.",
        "DEBUG ERROR INFO ERROR ERROR ERROR ERROR ERROR FINE ERROR ERROR",
        setOf(LogCategoryKey.CALLOUT, LogCategoryKey.VISUALFORCE),
    ),
    SAMPLING(
        "Sampling", LogLevelPresetCategory.PROFILE,
        "Captures sufficient logging details to allow simple profiling analysis.",
        "FINEST FINEST INFO ERROR ERROR ERROR ERROR ERROR FINE ERROR ERROR",
        setOf(LogCategoryKey.APEX_CODE, LogCategoryKey.APEX_PROFILING, LogCategoryKey.CALLOUT, LogCategoryKey.VISUALFORCE),
    ),
    TRACING(
        "Tracing", LogLevelPresetCategory.PROFILE,
        "Captures full logging details to allow detailed profiling analysis. May alter timings due to verbose logging.",
        "FINEST FINEST FINEST FINEST FINEST FINEST FINEST FINEST FINEST FINEST FINEST", null,
    );

    val levels: LogLevels = LogLevels(
        LogCategoryKey.entries.zip(levels.split(' ').map { ApexLogLevel.valueOf(it) }).toMap(),
    )

    private val keyCategories: Set<LogCategoryKey> = keys ?: LogCategoryKey.entries.toSet()

    val displayName: String get() = "$label (${category.label})"

    fun matches(candidate: LogLevels): Boolean = keyCategories.all { candidate[it] == levels[it] }

    companion object {
        fun matching(levels: LogLevels): LogLevelPreset? =
            entries.firstOrNull { it.levels == levels } ?: entries.firstOrNull { it.matches(levels) }
    }
}

data class LogLevels(val values: Map<LogCategoryKey, ApexLogLevel>) {
    operator fun get(key: LogCategoryKey): ApexLogLevel = values[key] ?: ApexLogLevel.NONE

    fun with(key: LogCategoryKey, level: ApexLogLevel): LogLevels = LogLevels(values + (key to level))

    fun toFields(includeDataAccess: Boolean = true): JsonObject = JsonObject().apply {
        LogCategoryKey.entries
            .filter { includeDataAccess || it != LogCategoryKey.DATA_ACCESS }
            .forEach { addProperty(it.field, get(it).name) }
    }

    fun encode(): String = LogCategoryKey.entries.joinToString(",") { "${it.name}=${get(it).name}" }

    companion object {
        val DEFAULT: LogLevels get() = LogLevelPreset.DEFAULT.levels

        fun fromRecord(record: JsonObject): LogLevels = LogLevels(
            LogCategoryKey.entries.associateWith { ApexLogLevel.parse(record.str(it.field)) ?: ApexLogLevel.NONE },
        )

        fun decode(text: String?): LogLevels? {
            if (text.isNullOrBlank()) return null
            val pairs = text.split(',').mapNotNull { part ->
                val key = runCatching { LogCategoryKey.valueOf(part.substringBefore('=')) }.getOrNull() ?: return@mapNotNull null
                val level = ApexLogLevel.parse(part.substringAfter('=')) ?: return@mapNotNull null
                key to level
            }.toMap()
            return LogLevels(LogCategoryKey.entries.associateWith { pairs[it] ?: DEFAULT[it] })
        }
    }
}

data class DebugLevelRecord(
    var id: String?,
    var name: String,
    var levels: LogLevels,
) {
    val isDevConsole: Boolean get() = name == LoggingConfig.DEV_CONSOLE

    companion object {
        private val NAME = Regex("[a-zA-Z](?:[a-zA-Z0-9]|_(?!_))*")

        fun isValidName(name: String): Boolean = NAME.matches(name) && !name.endsWith("_")
    }
}

enum class TraceFlagType(val apiName: String, val label: String) {
    CLASS_TRACING("CLASS_TRACING", "Apex class or trigger"),
    USER_DEBUG("USER_DEBUG", "User"),
    DEVELOPER_LOG("DEVELOPER_LOG", "Developer log"),
    PROFILING("PROFILING", "Profiling");

    val tracesUsers: Boolean get() = this != CLASS_TRACING

    companion object {
        fun parse(value: String?): TraceFlagType? = entries.firstOrNull { it.apiName.equals(value, true) }
    }
}

data class TracedEntity(val id: String, val name: String, val username: String?) {
    val isApex: Boolean get() = id.startsWith("01p") || id.startsWith("01q")

    val label: String get() = if (username.isNullOrEmpty()) name else "$name ($username)"
}

data class TraceFlagRecord(
    var id: String?,
    var type: TraceFlagType?,
    var entity: TracedEntity?,
    var createdBy: String?,
    var startDate: Instant?,
    var expirationDate: Instant?,
    var debugLevel: DebugLevelRecord?,
) {
    val isExpired: Boolean get() = expirationDate?.isBefore(Instant.now()) == true
}

data class LoggingConfiguration(val debugLevels: List<DebugLevelRecord>, val traceFlags: List<TraceFlagRecord>)

class LoggingConfig(private val project: Project) {
    private val api get() = SfApi.getInstance(project)

    fun loadLevels(org: String?, indicator: ProgressIndicator? = null): LogLevels {
        indicator?.text2 = "Retrieving log levels"
        return findDevConsole(org, indicator)?.levels ?: LogLevels.DEFAULT
    }

    fun saveLevels(org: String?, levels: LogLevels, indicator: ProgressIndicator? = null) {
        indicator?.text2 = "Saving log levels"
        val existing = findDevConsole(org, indicator)
        val levelId = if (existing?.id != null) {
            writeLevels(org, existing.id!!, levels, indicator)
            existing.id!!
        } else {
            createLevel(org, DEV_CONSOLE, levels, indicator)
        }
        ensureUserTraceFlag(org, levelId, indicator)
    }

    fun ensureUserTraceFlag(org: String?, debugLevelId: String, indicator: ProgressIndicator? = null) {
        val (userId, flag) = userTraceFlag(org, indicator)
        if (flag?.str("DebugLevelId") == debugLevelId && isActive(flag)) return
        writeTraceFlag(org, userId, flag, debugLevelId, indicator)
    }

    fun enableUserTracing(org: String?, fallback: LogLevels, indicator: ProgressIndicator? = null): LogLevels {
        val devConsole = findDevConsole(org, indicator)
        val levels = devConsole?.levels ?: fallback
        val (userId, flag) = userTraceFlag(org, indicator)
        if (flag != null && isActive(flag)) return levels
        val levelId = flag?.str("DebugLevelId") ?: devConsole?.id ?: createLevel(org, DEV_CONSOLE, fallback, indicator)
        writeTraceFlag(org, userId, flag, levelId, indicator)
        return levels
    }

    private fun userTraceFlag(org: String?, indicator: ProgressIndicator?): Pair<String, JsonObject?> {
        indicator?.text2 = "Retrieving trace flags"
        val userId = api.userId(org, indicator)
        val flag = api.query(
            org,
            "SELECT Id, ExpirationDate, DebugLevelId FROM TraceFlag WHERE TracedEntityId = '$userId' AND LogType = 'DEVELOPER_LOG'",
            tooling = true,
            indicator = indicator,
        ).records.firstOrNull()
        return userId to flag
    }

    private fun isActive(flag: JsonObject): Boolean =
        parseDate(flag.str("ExpirationDate"))?.isAfter(Instant.now().plus(RENEW_MARGIN_MINUTES, ChronoUnit.MINUTES)) == true

    private fun writeTraceFlag(org: String?, userId: String, flag: JsonObject?, debugLevelId: String, indicator: ProgressIndicator?) {
        indicator?.text2 = "Enabling the user trace flag"
        val expiration = Instant.now().plus(MAX_TRACE_HOURS, ChronoUnit.HOURS).minus(1, ChronoUnit.MINUTES)
        val fields = SfApi.jsonOf(
            "DebugLevelId" to debugLevelId,
            "StartDate" to format(Instant.now().minus(1, ChronoUnit.MINUTES)),
            "ExpirationDate" to format(expiration),
        )
        if (flag == null) {
            fields.addProperty("TracedEntityId", userId)
            fields.addProperty("LogType", TraceFlagType.DEVELOPER_LOG.apiName)
            api.create(org, "TraceFlag", fields, tooling = true, indicator = indicator)
        } else {
            api.update(org, "TraceFlag", flag.str("Id")!!, fields, tooling = true, indicator = indicator)
        }
    }

    fun load(org: String?, indicator: ProgressIndicator? = null): LoggingConfiguration {
        indicator?.text2 = "Loading debug levels"
        val levels = debugLevels(org, indicator)
        indicator?.text2 = "Loading trace flags"
        val byId = levels.associateBy { it.id }
        val flags = api.query(
            org,
            "SELECT Id, LogType, StartDate, ExpirationDate, TracedEntityId, TracedEntity.Name, TracedEntity.Username, " +
                "CreatedBy.Name, DebugLevelId FROM TraceFlag",
            tooling = true,
            indicator = indicator,
        ).records.map { record ->
            val entityId = record.str("TracedEntityId").orEmpty()
            val entity = record.obj("TracedEntity")
            TraceFlagRecord(
                id = record.str("Id"),
                type = TraceFlagType.parse(record.str("LogType")),
                entity = TracedEntity(entityId, entity?.str("Name") ?: entityId, entity?.str("Username")),
                createdBy = record.obj("CreatedBy")?.str("Name"),
                startDate = parseDate(record.str("StartDate")),
                expirationDate = parseDate(record.str("ExpirationDate")),
                debugLevel = byId[record.str("DebugLevelId")],
            )
        }
        return LoggingConfiguration(levels, flags)
    }

    fun users(org: String?, indicator: ProgressIndicator? = null): List<TracedEntity> =
        api.query(org, "SELECT Id, Name, Username FROM User WHERE IsActive = TRUE ORDER BY Name", indicator = indicator).records
            .map { TracedEntity(it.str("Id")!!, it.str("Name").orEmpty(), it.str("Username")) }

    fun apexTypes(org: String?, indicator: ProgressIndicator? = null): List<TracedEntity> {
        val classes = api.query(org, "SELECT Id, Name, NamespacePrefix FROM ApexClass ORDER BY Name", indicator = indicator).records
        val triggers = api.query(org, "SELECT Id, Name, NamespacePrefix FROM ApexTrigger ORDER BY Name", indicator = indicator).records
        return (classes + triggers).map { record ->
            val prefix = record.str("NamespacePrefix")?.let { "$it." }.orEmpty()
            TracedEntity(record.str("Id")!!, prefix + record.str("Name").orEmpty(), null)
        }
    }

    fun save(org: String?, original: LoggingConfiguration, updated: LoggingConfiguration, indicator: ProgressIndicator? = null): List<String> {
        val report = mutableListOf<String>()
        val keptFlagIds = updated.traceFlags.mapNotNull { it.id }.toSet()
        val keptLevelIds = updated.debugLevels.mapNotNull { it.id }.toSet()
        indicator?.text2 = "Deleting debug levels and trace flags"
        val deletedFlags = original.traceFlags.filter { it.id != null && it.id !in keptFlagIds }
        deletedFlags.forEach { api.delete(org, "TraceFlag", it.id!!, tooling = true, indicator = indicator) }
        val deletedLevels = original.debugLevels.filter { it.id != null && it.id !in keptLevelIds }
        deletedLevels.forEach { api.delete(org, "DebugLevel", it.id!!, tooling = true, indicator = indicator) }
        if (deletedLevels.isNotEmpty()) report += "Deleted ${deletedLevels.size} debug level(s)"
        if (deletedFlags.isNotEmpty()) report += "Deleted ${deletedFlags.size} trace flag(s)"

        indicator?.text2 = "Updating debug levels"
        val originalLevels = original.debugLevels.associateBy { it.id }
        var updatedLevels = 0
        var createdLevels = 0
        updated.debugLevels.forEach { level ->
            val id = level.id
            if (id == null) {
                level.id = createLevel(org, level.name, level.levels, indicator)
                createdLevels++
            } else if (originalLevels[id] != level) {
                val fields = level.levels.toFields()
                fields.addProperty("DeveloperName", level.name)
                fields.addProperty("MasterLabel", level.name)
                api.update(org, "DebugLevel", id, fields, tooling = true, indicator = indicator)
                updatedLevels++
            }
        }
        if (updatedLevels > 0) report += "Updated $updatedLevels debug level(s)"
        if (createdLevels > 0) report += "Created $createdLevels debug level(s)"

        indicator?.text2 = "Updating trace flags"
        val originalFlags = original.traceFlags.associateBy { it.id }
        var updatedFlags = 0
        var createdFlags = 0
        updated.traceFlags.forEach { flag ->
            val fields = SfApi.jsonOf(
                "LogType" to flag.type!!.apiName,
                "DebugLevelId" to flag.debugLevel!!.id,
                "StartDate" to flag.startDate?.let { format(it) },
                "ExpirationDate" to flag.expirationDate?.let { format(it) },
            )
            val id = flag.id
            if (id == null) {
                fields.addProperty("TracedEntityId", flag.entity!!.id)
                flag.id = api.create(org, "TraceFlag", fields, tooling = true, indicator = indicator)
                createdFlags++
            } else if (originalFlags[id] != flag) {
                api.update(org, "TraceFlag", id, fields, tooling = true, indicator = indicator)
                updatedFlags++
            }
        }
        if (updatedFlags > 0) report += "Updated $updatedFlags trace flag(s)"
        if (createdFlags > 0) report += "Created $createdFlags trace flag(s)"
        return report
    }

    private fun debugLevels(org: String?, indicator: ProgressIndicator?): List<DebugLevelRecord> {
        val fields = LogCategoryKey.entries.joinToString(", ") { it.field }
        val records = try {
            api.query(org, "SELECT Id, DeveloperName, $fields FROM DebugLevel ORDER BY DeveloperName", tooling = true, indicator = indicator).records
        } catch (e: SfApiException) {
            if (e.errorCode != "INVALID_FIELD") throw e
            val legacy = LogCategoryKey.entries.filter { it != LogCategoryKey.DATA_ACCESS }.joinToString(", ") { it.field }
            api.query(org, "SELECT Id, DeveloperName, $legacy FROM DebugLevel ORDER BY DeveloperName", tooling = true, indicator = indicator).records
        }
        return records.map { DebugLevelRecord(it.str("Id"), it.str("DeveloperName").orEmpty(), LogLevels.fromRecord(it)) }
    }

    private fun findDevConsole(org: String?, indicator: ProgressIndicator?): DebugLevelRecord? =
        debugLevels(org, indicator).firstOrNull { it.isDevConsole }

    private fun writeLevels(org: String?, id: String, levels: LogLevels, indicator: ProgressIndicator?) {
        try {
            api.update(org, "DebugLevel", id, levels.toFields(), tooling = true, indicator = indicator)
        } catch (e: SfApiException) {
            if (e.errorCode != "INVALID_FIELD") throw e
            api.update(org, "DebugLevel", id, levels.toFields(includeDataAccess = false), tooling = true, indicator = indicator)
        }
    }

    private fun createLevel(org: String?, name: String, levels: LogLevels, indicator: ProgressIndicator?): String {
        fun fields(includeDataAccess: Boolean) = levels.toFields(includeDataAccess).apply {
            addProperty("DeveloperName", name)
            addProperty("MasterLabel", name)
        }
        return try {
            api.create(org, "DebugLevel", fields(true), tooling = true, indicator = indicator)
        } catch (e: SfApiException) {
            if (e.errorCode != "INVALID_FIELD") throw e
            api.create(org, "DebugLevel", fields(false), tooling = true, indicator = indicator)
        }
    }

    companion object {
        const val DEV_CONSOLE = "SFDC_DevConsole"
        const val MAX_TRACE_HOURS = 24L
        private const val RENEW_MARGIN_MINUTES = 60L
        private val API_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX")

        fun format(instant: Instant): String = API_DATE.format(instant.atOffset(ZoneOffset.UTC))

        fun parseDate(value: String?): Instant? {
            if (value.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(value, API_DATE).toInstant() }
                .recoverCatching { OffsetDateTime.parse(value).toInstant() }
                .getOrNull()
        }
    }
}
