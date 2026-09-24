package dev.sfcloud.ost

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sfcloud.api.OrgSession
import dev.sfcloud.api.SfApi
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.core.arr
import dev.sfcloud.core.int
import dev.sfcloud.core.objects
import dev.sfcloud.core.str
import dev.sfcloud.lang.SfCloudIcons
import dev.sfcloud.metadata.ComponentRef
import dev.sfcloud.org.OrgListener
import dev.sfcloud.org.OrgService
import dev.sfcloud.settings.SfCloudProjectSettings
import dev.sfcloud.ui.SfUi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

class OstInfo(val org: String, val generated: Instant?, val types: Int, val sObjects: Int)

@Service(Service.Level.PROJECT)
class OfflineSymbolTable(private val project: Project) : Disposable {
    private class Index(val key: String, val root: VirtualFile, val files: Map<String, VirtualFile>, val types: List<String>, val sObjects: List<String>, val info: OstInfo)

    @Volatile
    private var index: Index? = null

    @Volatile
    private var missingKey: String? = null

    @Volatile
    private var lastKey: String? = null

    @Volatile
    var generating: Boolean = false
        private set

    private val lock = Any()

    private var pending: Pair<String, OstRequest>? = null

    private val attempted = ConcurrentHashMap.newKeySet<String>()

    init {
        project.messageBus.connect(this).subscribe(OrgListener.TOPIC, OrgListener { orgsChanged() })
    }

    val isAvailable: Boolean get() = current() != null

    fun root(): VirtualFile? = current()?.root

    fun info(): OstInfo? = current()?.info

    fun file(name: String): PsiFile? =
        current()?.files?.get(name.lowercase())?.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) }

    fun typeNames(): List<String> = current()?.types.orEmpty()

    fun sObjectNames(): List<String> = current()?.sObjects.orEmpty()

    fun ensure() {
        if (ApplicationManager.getApplication().isUnitTestMode || !SfdxProject.isSfdx(project) || generating) return
        val orgs = OrgService.getInstance(project)
        val key = orgs.targetOrgKey ?: return
        if (orgs.org(key)?.connected == false || current() != null || !attempted.add(key)) return
        generate(notifyOnSuccess = true)
    }

    fun generate(notifyOnSuccess: Boolean = true) = submit(OstRequest.everything(notifyOnSuccess))

    fun submit(request: OstRequest) {
        val key = OrgService.getInstance(project).targetOrgKey
        if (key == null) {
            SfNotifier.warn(project, "Cannot generate the offline symbol table", "Select a Salesforce org for the project first.")
            return
        }
        synchronized(lock) {
            if (generating) {
                val queued = pending?.takeIf { it.first == key }?.second
                pending = key to (queued?.merge(request) ?: request)
                return
            }
            generating = true
        }
        start(key, request)
    }

    fun componentsChanged(org: String?, components: Collection<ComponentRef>) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (!SfCloudProjectSettings.getInstance(project).state.ostAutoUpdate) return
        val names = OstChanges.sObjects(components)
        if (names.isEmpty() || !isTarget(org)) return
        if (!generating && current() == null) return
        submit(OstRequest.sObjects(names, notify = false))
    }

    private fun isTarget(org: String?): Boolean {
        val orgs = OrgService.getInstance(project)
        val target = orgs.targetOrgKey ?: return false
        val key = orgs.orgKey(org) ?: return false
        if (key == target) return true
        val username = orgs.find(key)?.username ?: return false
        return username == orgs.find(target)?.username
    }

    private fun start(key: String, request: OstRequest) {
        val title = if (request.isSelective) "Updating Offline Symbol Table" else "Generating Offline Symbol Table"
        SfUi.background(project, title, onError = { message ->
            SfNotifier.error(project, "Offline symbol table generation failed", message)
        }) { indicator ->
            try {
                val summary = build(key, request, indicator)
                SfUi.edt(project) {
                    reload(key)
                    if (request.notify) {
                        SfNotifier.info(project, if (request.isSelective) "Offline symbol table updated" else "Offline symbol table generated", summary)
                    } else {
                        WindowManager.getInstance().getStatusBar(project)?.info = "Offline symbol table updated: $summary"
                    }
                }
            } finally {
                finished()
            }
        }
    }

    private fun finished() {
        val next = synchronized(lock) {
            val queued = pending
            pending = null
            if (queued == null) generating = false
            queued
        } ?: return
        if (next.first != OrgService.getInstance(project).targetOrgKey) {
            synchronized(lock) { generating = false }
            return
        }
        ApplicationManager.getApplication().invokeLater({ start(next.first, next.second) }, project.disposed)
    }

    private fun build(key: String, request: OstRequest, indicator: ProgressIndicator): String {
        val api = SfApi.getInstance(project)
        indicator.isIndeterminate = false
        indicator.fraction = 0.0
        indicator.text2 = "Logging in to $key"
        val session = api.session(key, indicator)
        val sources = sourcesDirectory(key)
        val completions = (if (request.systemLibrary) null else readObject(sources.resolve(COMPLETIONS))) ?: run {
            indicator.text2 = "Loading the Apex system library"
            api.getJson(key, api.dataPath(session, true, "/completions?type=apex"), indicator).asJsonObject
        }
        indicator.fraction = 0.1
        indicator.text2 = "Listing SObjects"
        val names = api.getJson(key, api.dataPath(session, false, "/sobjects"), indicator).asJsonObject
            .arr("sobjects").objects().mapNotNull { it.str("name") }
        val failed = AtomicInteger()
        val cached = request.sObjects?.let { readDescribes(sources.resolve(DESCRIBES)) }
        val describes: Map<String, JsonObject>
        val summary: String
        if (request.sObjects != null && cached != null) {
            val existing = OstChanges.names(names)
            val kept = TreeMap<String, JsonObject>(String.CASE_INSENSITIVE_ORDER).apply { putAll(cached.filterKeys { it in existing }) }
            val changed = request.sObjects.filter { it in existing }
            val first = OstChanges.names(changed + changed.flatMap { OstChanges.references(kept[it]) }).filter { it in existing }
            val described = describeAll(api, key, session, first, indicator, failed).associateBy { it.str("name").orEmpty() }
            val related = OstChanges.names(described.values.flatMap { OstChanges.references(it) }).filter { it in existing && it !in first }
            val more = describeAll(api, key, session, related, indicator, failed).associateBy { it.str("name").orEmpty() }
            kept.putAll(described)
            kept.putAll(more)
            describes = kept
            val removed = cached.keys.count { it !in existing }
            summary = buildString {
                append("${session.displayName}: refreshed ${changed.size} ${if (changed.size == 1) "SObject" else "SObjects"}")
                if (changed.isNotEmpty()) append(" (${changed.take(5).joinToString(", ")}${if (changed.size > 5) ", …" else ""})")
                val relatedCount = first.size - changed.size + related.size
                if (relatedCount > 0) append(" and $relatedCount related")
                append('.')
                if (removed > 0) append(" Removed $removed deleted SObjects.")
                request.sObjects.filter { it !in existing }.takeIf { it.isNotEmpty() }?.let { append(" Not found in the org: ${it.joinToString(", ")}.") }
            }
        } else {
            describes = describeAll(api, key, session, names, indicator, failed).associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.str("name").orEmpty() }
            summary = ""
        }
        indicator.checkCanceled()
        indicator.text2 = "Writing symbol files"
        indicator.isIndeterminate = true
        val stubs = OstStubs.render(completions, describes.values.toList())
        write(directory(key), stubs, session)
        writeSources(sources, completions, describes.values)
        val skipped = failed.get().takeIf { it > 0 }?.let { " $it SObjects could not be described." }.orEmpty()
        return summary.ifEmpty { "${session.displayName}: ${stubs.types.size} Apex types and namespaces, ${stubs.sObjects.size} SObjects." } + skipped
    }

    private fun describeAll(api: SfApi, key: String, session: OrgSession, names: List<String>, indicator: ProgressIndicator, failed: AtomicInteger): List<JsonObject> {
        if (names.isEmpty()) return emptyList()
        val batches = names.chunked(BATCH_SIZE)
        val done = AtomicInteger()
        val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("SF Cloud Offline Symbol Table", PARALLEL_BATCHES)
        try {
            val futures: List<Future<List<JsonObject>>> = batches.map { batch ->
                executor.submit<List<JsonObject>> {
                    val described = try {
                        describeBatch(api, key, session, batch, indicator)
                    } catch (e: Exception) {
                        if (indicator.isCanceled) throw e
                        emptyList()
                    }
                    failed.addAndGet(batch.size - described.size)
                    val finished = done.incrementAndGet()
                    indicator.text2 = "Describing SObjects ($finished of ${batches.size} batches)"
                    indicator.fraction = 0.1 + 0.85 * finished / batches.size
                    described
                }
            }
            return futures.flatMap { await(it, indicator) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun <T> await(future: Future<T>, indicator: ProgressIndicator): T {
        while (true) {
            indicator.checkCanceled()
            try {
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                continue
            }
        }
    }

    private fun describeBatch(api: SfApi, key: String, session: OrgSession, batch: List<String>, indicator: ProgressIndicator): List<JsonObject> {
        val requests = JsonArray()
        batch.forEach { name ->
            requests.add(JsonObject().apply {
                addProperty("method", "GET")
                addProperty("url", "v${session.apiVersion}/sobjects/$name/describe")
            })
        }
        val body = JsonObject().apply { add("batchRequests", requests) }.toString()
        val response = JsonParser.parseString(api.send(key, "POST", api.dataPath(session, false, "/composite/batch"), body, indicator)).asJsonObject
        return response.arr("results").objects()
            .filter { (it.int("statusCode") ?: 0) in 200..299 }
            .mapNotNull { it.get("result")?.takeIf { result -> result.isJsonObject }?.asJsonObject }
            .map { slim(it) }
    }

    private fun slim(describe: JsonObject): JsonObject = JsonObject().apply {
        addProperty("name", describe.str("name"))
        add("fields", JsonArray().also { fields ->
            describe.arr("fields").objects().forEach { field ->
                fields.add(JsonObject().apply {
                    listOf("name", "soapType", "relationshipName").forEach { addProperty(it, field.str(it)) }
                    field.arr("referenceTo")?.let { add("referenceTo", it) }
                })
            }
        })
        add("childRelationships", JsonArray().also { children ->
            describe.arr("childRelationships").objects().forEach { child ->
                children.add(JsonObject().apply {
                    listOf("childSObject", "relationshipName").forEach { addProperty(it, child.str(it)) }
                })
            }
        })
    }

    private fun write(target: Path, stubs: OstStubSet, session: OrgSession) {
        val staging = target.resolveSibling(target.fileName.toString() + ".tmp")
        FileUtil.delete(staging.toFile())
        Files.createDirectories(staging)
        stubs.files.forEach { (name, text) ->
            val file = staging.resolve("$name.cls")
            Files.writeString(file, text)
            file.toFile().setReadOnly()
        }
        val manifest = JsonObject().apply {
            addProperty("org", session.displayName)
            addProperty("username", session.username)
            addProperty("apiVersion", session.apiVersion)
            addProperty("generated", System.currentTimeMillis())
            add("types", JsonArray().also { array -> stubs.types.forEach(array::add) })
            add("sObjects", JsonArray().also { array -> stubs.sObjects.forEach(array::add) })
        }
        Files.writeString(staging.resolve(MANIFEST), manifest.toString())
        FileUtil.delete(target.toFile())
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeSources(target: Path, completions: JsonObject, describes: Collection<JsonObject>) {
        Files.createDirectories(target)
        replace(target.resolve(COMPLETIONS), completions.toString())
        replace(target.resolve(DESCRIBES), JsonArray().also { array -> describes.forEach(array::add) }.toString())
    }

    private fun replace(file: Path, text: String) {
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readObject(file: Path): JsonObject? =
        runCatching { JsonParser.parseString(Files.readString(file)).asJsonObject }.getOrNull()

    private fun readDescribes(file: Path): Map<String, JsonObject>? = runCatching {
        JsonParser.parseString(Files.readString(file)).objects()
            .filter { it.str("name") != null }
            .associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.str("name")!! }
    }.getOrNull()

    private fun reload(key: String) {
        val old = listOfNotNull(index?.root?.takeIf { it.isValid })
        index = null
        missingKey = null
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory(key))?.refresh(false, true)
        librariesChanged(old)
    }

    private fun orgsChanged() {
        val key = OrgService.getInstance(project).targetOrgKey
        if (key != lastKey) {
            lastKey = key
            val old = listOfNotNull(index?.root?.takeIf { it.isValid })
            index = null
            missingKey = null
            if (old.isNotEmpty() || current() != null) SfUi.edt(project) { librariesChanged(old) }
        }
        ensure()
    }

    private fun librariesChanged(old: List<VirtualFile>) {
        val new = listOfNotNull(root())
        WriteAction.run<RuntimeException> {
            AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, LIBRARY_NAME, old, new, LIBRARY_NAME)
        }
        PsiManager.getInstance(project).dropPsiCaches()
        DaemonCodeAnalyzer.getInstance(project).restart(LIBRARY_NAME)
    }

    private fun current(): Index? {
        val key = OrgService.getInstance(project).targetOrgKey ?: return null
        index?.takeIf { it.key == key && it.root.isValid }?.let { return it }
        if (missingKey == key) return null
        val directory = directory(key)
        val manifestPath = directory.resolve(MANIFEST)
        val root = if (Files.isRegularFile(manifestPath)) LocalFileSystem.getInstance().findFileByNioFile(directory) else null
        if (root == null) {
            missingKey = key
            return null
        }
        val manifest = readObject(manifestPath) ?: JsonObject()
        val files = root.children.filter { it.extension == "cls" }.associateBy { it.nameWithoutExtension.lowercase() }
        val strings = { name: String -> manifest.arr(name)?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty() }
        val types = strings("types")
        val sObjects = strings("sObjects")
        val generated = runCatching { manifest.get("generated")?.asLong?.let(Instant::ofEpochMilli) }.getOrNull()
        val info = OstInfo(manifest.str("org") ?: key, generated, types.size, sObjects.size)
        return Index(key, root, files, types, sObjects, info).also { index = it }
    }

    override fun dispose() = Unit

    companion object {
        const val LIBRARY_NAME = "Salesforce Offline Symbol Table"
        private const val MANIFEST = "ost.json"
        private const val COMPLETIONS = "completions.json"
        private const val DESCRIBES = "sobjects.json"
        private const val BATCH_SIZE = 25
        private const val PARALLEL_BATCHES = 6

        fun getInstance(project: Project): OfflineSymbolTable = project.service()

        fun directory(key: String): Path =
            Path.of(PathManager.getSystemPath(), "sfcloud", "ost", key.replace(Regex("[^A-Za-z0-9._@-]"), "_"))

        fun sourcesDirectory(key: String): Path = directory(key).let { it.resolveSibling(it.fileName.toString() + ".sources") }
    }
}

class OfflineSymbolTableLibraryProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val root = OfflineSymbolTable.getInstance(project).root() ?: return emptyList()
        return listOf(OfflineSymbolTableLibrary(root))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> = listOfNotNull(OfflineSymbolTable.getInstance(project).root())
}

class OfflineSymbolTableLibrary(private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)

    override fun equals(other: Any?): Boolean = other is OfflineSymbolTableLibrary && other.root == root

    override fun hashCode(): Int = root.hashCode()

    override fun getPresentableText(): String = "${OfflineSymbolTable.LIBRARY_NAME} (${root.name})"

    override fun getIcon(unused: Boolean): Icon = SfCloudIcons.Apex
}

class OfflineSymbolTableStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        OfflineSymbolTable.getInstance(project).ensure()
    }
}

class GenerateOfflineSymbolTableAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && SfdxProject.isSfdx(project) && OrgService.getInstance(project).targetOrgKey != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty().map { it.path }
        val dialog = GenerateOfflineSymbolTableDialog(project, OstChanges.sObjectsInPaths(selected))
        if (dialog.showAndGet()) OfflineSymbolTable.getInstance(project).submit(dialog.request())
    }
}

class UpdateOfflineSymbolTableAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val visible = project != null && SfdxProject.isSfdx(project) && names(e).isNotEmpty()
        e.presentation.isVisible = visible
        e.presentation.isEnabled = visible && OrgService.getInstance(project).targetOrgKey != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val names = names(e).takeIf { it.isNotEmpty() } ?: return
        OfflineSymbolTable.getInstance(project).submit(OstRequest.sObjects(names))
    }

    private fun names(e: AnActionEvent): Set<String> {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return OstChanges.sObjectsInPaths(files.map { it.path })
    }
}
