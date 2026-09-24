package dev.sfcloud.deploy

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfCliException
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.SfResult
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.core.int
import dev.sfcloud.core.objects
import dev.sfcloud.core.str
import dev.sfcloud.metadata.ComponentRef
import dev.sfcloud.ost.OfflineSymbolTable
import dev.sfcloud.org.OrgKind
import dev.sfcloud.org.OrgService
import dev.sfcloud.ui.SfUi
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class DeployProblem(
    val path: String,
    val line: Int,
    val column: Int,
    val message: String,
    val component: String,
    val isError: Boolean,
    val kind: ProblemKind = ProblemKind.FAILURE,
)

enum class ProblemKind(val label: String, val plural: String) {
    FAILURE("Failure", "Failures"),
    TRANSITIVE("Transitive failure", "Transitive failures"),
    UNEXPECTED("Unexpected error", "Unexpected errors"),
}

enum class TestLevel(val apiName: String?, val label: String) {
    DEFAULT(null, "<Default>"),
    NO_TEST_RUN("NoTestRun", "NoTestRun"),
    RUN_SPECIFIED_TESTS("RunSpecifiedTests", "RunSpecifiedTests"),
    RUN_LOCAL_TESTS("RunLocalTests", "RunLocalTests"),
    RUN_ALL_TESTS_IN_ORG("RunAllTestsInOrg", "RunAllTestsInOrg"),
}

data class DeployRequest(
    val org: String?,
    val targets: List<VirtualFile> = emptyList(),
    val manifest: File? = null,
    val label: String,
    val checkOnly: Boolean = false,
    val ignoreWarnings: Boolean = false,
    val ignoreErrors: Boolean = false,
    val testLevel: TestLevel = TestLevel.DEFAULT,
    val tests: List<String> = emptyList(),
    val ignoreConflicts: Boolean = false,
    val onSave: Boolean = false,
    val tracked: Boolean = false,
)

interface DeployListener {
    fun problemsChanged() {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("SF Cloud deploy", DeployListener::class.java)
    }
}

@Service(Service.Level.PROJECT)
class DeployService(private val project: Project) : Disposable {
    private val problemsByOrg = ConcurrentHashMap<String, ConcurrentHashMap<String, List<DeployProblem>>>()
    private val pendingOnSave = ConcurrentHashMap.newKeySet<VirtualFile>()
    private val saveAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val lock = Any()

    val connections: List<String> get() = problemsByOrg.filterValues { it.isNotEmpty() }.keys.sorted()

    fun problems(org: String): List<DeployProblem> =
        problemsByOrg[org]?.values?.flatten().orEmpty().sortedWith(compareBy({ it.path }, { it.line }, { it.column }))

    val problems: List<DeployProblem> get() = problemsByOrg.values.flatMap { it.values.flatten() }

    fun problemsFor(path: String): List<DeployProblem> = problemsByOrg.values.flatMap { it[path].orEmpty() }

    fun deploy(files: Collection<VirtualFile>, ignoreConflicts: Boolean = false, onSave: Boolean = false, checkOnly: Boolean = false) {
        val targets = normalize(files)
        if (targets.isEmpty()) return
        submit(DeployRequest(null, targets = targets, label = describe(targets), ignoreConflicts = ignoreConflicts, onSave = onSave, checkOnly = checkOnly))
    }

    fun pushTracked(ignoreConflicts: Boolean = false) {
        submit(DeployRequest(null, label = "tracked changes", ignoreConflicts = ignoreConflicts, tracked = true))
    }

    fun submit(request: DeployRequest) {
        val service = OrgService.getInstance(project)
        if (!request.onSave && !request.checkOnly && !service.confirmWrite("Deploy", request.org)) return
        val verb = if (request.checkOnly) "Validating metadata against" else "Deploying metadata to"
        object : Task.Backgroundable(project, "$verb '${service.orgLabel(request.org)}'", true) {
            override fun run(indicator: ProgressIndicator) {
                synchronized(lock) { runDeploy(request, indicator) }
            }
        }.queue()
    }

    fun retrieve(files: Collection<VirtualFile>) {
        val targets = normalize(files)
        if (targets.isEmpty()) return
        object : Task.Backgroundable(project, "Refreshing metadata from '${OrgService.getInstance(project).orgLabel(null)}'", true) {
            override fun run(indicator: ProgressIndicator) {
                synchronized(lock) {
                    val args = mutableListOf("project", "retrieve", "start", "--wait", "60")
                    targets.forEach { args += listOf("--source-dir", it.path) }
                    runRetrieve(null, args, "Refresh", indicator)
                    VfsUtil.markDirtyAndRefresh(false, true, true, *targets.toTypedArray())
                }
            }
        }.queue()
    }

    fun pullTracked(ignoreConflicts: Boolean = false) {
        val connection = OrgService.getInstance(project).orgLabel(null)
        object : Task.Backgroundable(project, "Pulling changed metadata from '$connection'", true) {
            override fun run(indicator: ProgressIndicator) {
                synchronized(lock) {
                    val args = mutableListOf("project", "retrieve", "start", "--wait", "60")
                    if (ignoreConflicts) args += "--ignore-conflicts"
                    val result = runRetrieve(null, args, "Pull", indicator)
                    if (result != null && isConflict(result)) {
                        SfUi.notify(
                            project, GROUP, "Conflicts Detected",
                            "Conflicts were detected while pulling changes from '$connection'. Retry with the 'ignore-conflicts' option?",
                            NotificationType.WARNING,
                        )
                    }
                    SfdxProject.root(project)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
                }
            }
        }.queue()
    }

    fun runRetrieve(org: String?, baseArgs: List<String>, operation: String, indicator: ProgressIndicator): SfResult? {
        val args = baseArgs + OrgService.getInstance(project).orgArgs(org)
        val label = OrgService.getInstance(project).orgLabel(org)
        val started = System.currentTimeMillis()
        indicator.text2 = "Submitting retrieve request"
        val result = try {
            SfCli.run(project, args, 70 * 60 * 1000, indicator)
        } catch (e: SfCliException) {
            SfUi.notify(project, GROUP, "$operation Failed", e.message.orEmpty(), NotificationType.ERROR)
            return null
        }
        indicator.text2 = "Refreshing virtual file system"
        val files = result.payload?.takeIf { it.isJsonObject }?.asJsonObject?.get("files").objects()
        val failed = files.filter { it.str("state") == "Failed" }
        val paths = files.filter { it.str("state") != "Failed" }.mapNotNull { it.str("filePath") }.distinct()
        OfflineSymbolTable.getInstance(project).componentsChanged(org, components(files))
        paths.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
            .let { if (it.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, false, false, *it.toTypedArray()) }
        val elapsed = StringUtil.formatDuration(System.currentTimeMillis() - started)
        val past = PAST_TENSE[operation] ?: "${operation}ed"
        if (result.success && failed.isEmpty()) {
            val components = files.map { "${it.str("type")}:${it.str("fullName")}" }.distinct().size
            val body = buildString {
                append("$past $components components from `$label` in $elapsed.")
                if (paths.isEmpty()) {
                    append("<br>All files are up-to-date.")
                } else {
                    append("<br>$past the following files:")
                    paths.take(FILE_LIST_LIMIT).forEach { append("<br>• ").append(relative(it)) }
                    if (paths.size > FILE_LIST_LIMIT) append("<br>… and ${paths.size - FILE_LIST_LIMIT} more")
                }
            }
            SfUi.notify(project, GROUP, "$operation Complete", body)
        } else {
            val details = failed.take(10).joinToString("<br>") { "${it.str("fullName")}: ${it.str("error").orEmpty()}" }
            SfUi.notify(project, GROUP, "$operation Failed", details.ifEmpty { result.message }, NotificationType.ERROR)
        }
        return result
    }

    fun scheduleDeployOnSave(file: VirtualFile) {
        pendingOnSave.add(file)
        saveAlarm.cancelAllRequests()
        saveAlarm.addRequest({ flushOnSave() }, 400)
    }

    fun clearProblems(org: String? = null) {
        if (org == null) problemsByOrg.clear() else problemsByOrg.remove(org)
        problemsChanged()
    }

    private fun flushOnSave() {
        val files = pendingOnSave.toList()
        pendingOnSave.removeAll(files.toSet())
        if (files.isEmpty()) return
        val org = OrgService.getInstance(project).targetOrg
        if (org?.kind == OrgKind.PRODUCTION) {
            SfNotifier.warn(project, "Deploy on Save Skipped", "'${org.displayName}' is a production org. Use Force Save to deploy to it explicitly.")
            return
        }
        ApplicationManager.getApplication().invokeLater({ deploy(files, onSave = true) }, project.disposed)
    }

    private fun runDeploy(request: DeployRequest, indicator: ProgressIndicator) {
        val service = OrgService.getInstance(project)
        val connection = service.orgLabel(request.org)
        val args = mutableListOf("project", "deploy", "start", "--wait", "60")
        request.targets.forEach { args += listOf("--source-dir", it.path) }
        request.manifest?.let { args += listOf("--manifest", it.path) }
        if (request.ignoreConflicts) args += "--ignore-conflicts"
        if (request.checkOnly) args += "--dry-run"
        if (request.ignoreWarnings) args += "--ignore-warnings"
        if (request.ignoreErrors) args += "--ignore-errors"
        request.testLevel.apiName?.let { args += listOf("--test-level", it) }
        if (request.testLevel == TestLevel.RUN_SPECIFIED_TESTS) request.tests.forEach { args += listOf("--tests", it) }
        args += service.orgArgs(request.org)
        val operation = if (request.checkOnly) "Validation" else "Deployment"
        indicator.text2 = "Submitting deployment request"
        val started = System.currentTimeMillis()
        val result = try {
            SfCli.run(project, args, 70 * 60 * 1000, indicator)
        } catch (e: SfCliException) {
            SfUi.notify(project, GROUP, "$operation Failed", e.message.orEmpty(), NotificationType.ERROR)
            return
        }
        indicator.text2 = "Retrieving deployment result"
        val payload = result.payload?.takeIf { it.isJsonObject }?.asJsonObject
        val files = payload?.get("files").objects()
        val failures = files.filter { it.str("state") == "Failed" }
        val deployedPaths = files.mapNotNull { it.str("filePath") }.toSet()
        val orgProblems = problemsByOrg.getOrPut(connection) { ConcurrentHashMap() }
        clearProblemsUnder(orgProblems, request.targets, deployedPaths, full = request.tracked || request.manifest != null)
        failures.forEach { addProblem(orgProblems, it) }
        testFailures(payload).forEach { addProblem(orgProblems, it) }
        problemsChanged()
        if (!request.checkOnly) OfflineSymbolTable.getInstance(project).componentsChanged(request.org, components(files))
        val elapsed = StringUtil.formatDuration(System.currentTimeMillis() - started)
        val total = payload?.int("numberComponentsTotal") ?: files.size
        val deployed = payload?.int("numberComponentsDeployed") ?: files.count { it.str("state") != "Failed" }
        val status = payload?.str("status") ?: if (result.success) "Succeeded" else "Failed"
        val verb = if (request.checkOnly) "Validated" else "Deployed"
        when {
            result.success -> {
                val body = "$verb $deployed/$total components to `$connection` in $elapsed with status $status."
                if (request.onSave) SfUi.edt(project) { WindowManager.getInstance().getStatusBar(project)?.info = body } else SfUi.notify(project, GROUP, "$operation Complete", body)
            }
            failures.isNotEmpty() || orgProblems.isNotEmpty() -> {
                val count = failures.size.coerceAtLeast(orgProblems.values.sumOf { it.size })
                SfUi.notify(
                    project, GROUP, "$operation Failed",
                    "${request.label}: $operation with $count ${StringUtil.pluralize("error", count)}<br>" +
                        failures.take(3).joinToString("<br>") { problemText(it) },
                    NotificationType.ERROR,
                )
                SfUi.edt(project) { ProblemsWindow.show(project, connection) }
            }
            isConflict(result) -> {
                val notification = com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                    .createNotification(
                        "Conflicts Detected",
                        "Conflicts were detected for the following metadata while pushing changes to '$connection'. Retry with the 'ignore-conflicts' option?<br>" +
                            "WARNING: This will replace the org versions of the conflicting metadata.",
                        NotificationType.WARNING,
                    )
                notification.addAction(NotificationAction.createSimpleExpiring("Retry with ignore-conflicts") {
                    submit(request.copy(ignoreConflicts = true))
                })
                notification.notify(project)
            }
            else -> SfUi.notify(project, GROUP, "$operation Failed", result.message, NotificationType.ERROR)
        }
    }

    private fun components(files: List<com.google.gson.JsonObject>): List<ComponentRef> =
        files.filter { it.str("state") != "Failed" }.mapNotNull { file ->
            val type = file.str("type") ?: return@mapNotNull null
            val fullName = file.str("fullName") ?: return@mapNotNull null
            ComponentRef(type, fullName)
        }.distinct()

    private fun testFailures(payload: com.google.gson.JsonObject?): List<com.google.gson.JsonObject> {
        val failures = payload?.getAsJsonObject("details")?.getAsJsonObject("runTestResult")?.get("failures") ?: return emptyList()
        val list = if (failures.isJsonArray) failures.objects() else listOfNotNull(failures.takeIf { it.isJsonObject }?.asJsonObject)
        return list.map { failure ->
            com.google.gson.JsonObject().apply {
                addProperty("fullName", "${failure.str("name")}.${failure.str("methodName")}")
                addProperty("type", "ApexClass")
                addProperty("error", "${failure.str("message").orEmpty()} ${failure.str("stackTrace").orEmpty()}".trim())
                addProperty("problemType", "Error")
            }
        }
    }

    private fun normalize(files: Collection<VirtualFile>): List<VirtualFile> {
        val packageDirectories = SfdxProject.packageDirectories(project)
        val targets = files
            .filter { it.isValid && (it in packageDirectories || SfdxProject.isSourceFile(project, it)) }
            .map { SfdxProject.deployTarget(it) }
            .distinct()
        return targets.filter { candidate -> targets.none { it != candidate && VfsUtilCore.isAncestor(it, candidate, true) } }
    }

    private fun clearProblemsUnder(problems: MutableMap<String, List<DeployProblem>>, targets: List<VirtualFile>, deployedPaths: Set<String>, full: Boolean) {
        val roots = targets.map { Path.of(it.path) }
        problems.keys.removeIf { key ->
            val path = runCatching { Path.of(key) }.getOrNull()
            key in deployedPaths || full || (path != null && roots.any { path.startsWith(it) })
        }
    }

    private fun addProblem(problems: MutableMap<String, List<DeployProblem>>, json: com.google.gson.JsonObject) {
        val path = json.str("filePath") ?: json.str("fullName").orEmpty()
        val message = json.str("error") ?: "Unknown error"
        val kind = when {
            json.str("filePath") == null -> ProblemKind.UNEXPECTED
            TRANSITIVE.containsMatchIn(message) -> ProblemKind.TRANSITIVE
            else -> ProblemKind.FAILURE
        }
        val problem = DeployProblem(
            path = path,
            line = json.int("lineNumber") ?: 0,
            column = json.int("columnNumber") ?: 0,
            message = message,
            component = "${json.str("type").orEmpty()} ${json.str("fullName").orEmpty()}".trim(),
            isError = json.str("problemType") != "Warning",
            kind = kind,
        )
        problems.merge(path, listOf(problem)) { a, b -> a + b }
    }

    private fun problemText(json: com.google.gson.JsonObject): String {
        val file = json.str("filePath")?.substringAfterLast('/') ?: json.str("fullName").orEmpty()
        val line = json.int("lineNumber")
        val column = json.int("columnNumber")
        val location = if (line != null) " at line $line, column ${column ?: 0}" else ""
        return "$file: ${json.str("problemType") ?: "Error"}$location - ${json.str("error").orEmpty()}"
    }

    private fun isConflict(result: SfResult): Boolean {
        val name = result.json?.str("name").orEmpty()
        return name.contains("Conflict", true) || result.message.contains("conflict", true)
    }

    private fun describe(targets: List<VirtualFile>): String = when (targets.size) {
        0 -> "tracked changes"
        1 -> targets.first().name
        else -> "${targets.size} items"
    }

    private fun relative(path: String): String {
        val root = SfdxProject.root(project)?.path ?: return path
        return if (path.startsWith(root)) path.removePrefix(root).trimStart('/') else path
    }

    fun fileFor(problem: DeployProblem): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(problem.path)

    private fun problemsChanged() {
        ApplicationManager.getApplication().invokeLater({
            project.messageBus.syncPublisher(DeployListener.TOPIC).problemsChanged()
            DaemonCodeAnalyzer.getInstance(project).restart("SF Cloud deploy problems changed")
        }, project.disposed)
    }

    override fun dispose() = Unit

    companion object {
        const val GROUP = "SF Cloud"
        private const val FILE_LIST_LIMIT = 20
        private val TRANSITIVE = Regex("Dependent class is invalid|needs recompilation|Invalid type:", RegexOption.IGNORE_CASE)
        private val PAST_TENSE = mapOf("Retrieve" to "Retrieved", "Refresh" to "Refreshed", "Pull" to "Pulled")

        fun getInstance(project: Project): DeployService = project.service()
    }
}
