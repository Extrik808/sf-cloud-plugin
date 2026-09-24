package dev.sfcloud.metadata

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.sfcloud.api.SfApi
import org.w3c.dom.Element
import dev.sfcloud.core.ProjectScratch
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfCliException
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.core.objects
import dev.sfcloud.core.str
import dev.sfcloud.deploy.DeployRequest
import dev.sfcloud.deploy.DeployService
import dev.sfcloud.deploy.TestLevel
import dev.sfcloud.org.OrgService
import dev.sfcloud.ost.OfflineSymbolTable
import dev.sfcloud.ui.SfUi
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files

data class RetrievedFile(val server: File, val local: VirtualFile?, val relative: String)

class LocalMetadata(private val project: Project) {
    fun components(paths: List<String>, indicator: ProgressIndicator?): List<ComponentRef> {
        if (paths.isEmpty()) return emptyList()
        indicator?.text2 = "Finding Metadata Files"
        val dir = ProjectScratch.directory(project, "sf-cloud-manifest")
        try {
            val args = mutableListOf("project", "generate", "manifest", "--output-dir", dir.path, "--name", "package.xml")
            paths.forEach { args += listOf("--source-dir", it) }
            val result = SfCli.run(project, args, 10 * 60 * 1000, indicator)
            val file = File(dir, "package.xml")
            if (!file.isFile) throw SfCliException(result.message)
            return MetadataXml.readPackageXml(file.readText()).sorted()
        } finally {
            ProjectScratch.delete(dir)
        }
    }

    fun packagePaths(): List<String> = SfdxProject.packageDirectories(project).map { it.path }
}

object PathComponents {
    private val BUNDLES = mapOf("lwc" to "LightningComponentBundle", "aura" to "AuraDefinitionBundle")
    private val SOURCES = mapOf(
        "cls" to "ApexClass",
        "trigger" to "ApexTrigger",
        "page" to "ApexPage",
        "component" to "ApexComponent",
    )
    private val SUFFIXES = mapOf(
        "permissionset" to "PermissionSet",
        "permissionsetgroup" to "PermissionSetGroup",
        "profile" to "Profile",
        "layout" to "Layout",
        "flow" to "Flow",
        "flexipage" to "FlexiPage",
        "tab" to "CustomTab",
        "app" to "CustomApplication",
        "resource" to "StaticResource",
        "md" to "CustomMetadata",
        "globalValueSet" to "GlobalValueSet",
        "quickAction" to "QuickAction",
        "remoteSite" to "RemoteSiteSetting",
        "namedCredential" to "NamedCredential",
        "customPermission" to "CustomPermission",
        "object" to "CustomObject",
    )
    private val CHILDREN = LocalFiles.CHILD_DIRECTORIES.entries.associate { (type, folder) -> folder to type }
    private val CHILD_SUFFIXES = mapOf(
        "fields" to "field",
        "listViews" to "listView",
        "recordTypes" to "recordType",
        "validationRules" to "validationRule",
        "webLinks" to "webLink",
        "compactLayouts" to "compactLayout",
        "businessProcesses" to "businessProcess",
        "fieldSets" to "fieldSet",
        "sharingReasons" to "sharingReason",
        "indexes" to "index",
    )

    fun resolve(file: VirtualFile): ComponentRef? {
        var current = file
        while (true) {
            val up = current.parent ?: break
            val type = BUNDLES[up.name]
            if (type != null) return ComponentRef(type, current.name)
            current = up
        }
        val parent = file.parent
        if (file.isDirectory || parent == null) return null
        val name = file.name.removeSuffix("-meta.xml")
        val extension = name.substringAfterLast('.', "")
        val base = name.substringBeforeLast('.')
        SOURCES[extension]?.let { return ComponentRef(it, base) }
        CHILDREN[parent.name]?.let { type ->
            val owner = parent.parent?.takeIf { it.parent?.name == "objects" } ?: return null
            if (extension != CHILD_SUFFIXES[parent.name]) return null
            return ComponentRef(type, "${owner.name}.$base")
        }
        if (extension == "resource" || parent.name == "staticresources") return ComponentRef("StaticResource", name.substringBefore('.'))
        val type = SUFFIXES[extension] ?: return null
        if (!file.name.endsWith("-meta.xml")) return null
        return ComponentRef(type, base)
    }
}

class MetadataOperations(private val project: Project) {
    private val service get() = OrgService.getInstance(project)

    fun retrieve(org: String?, refs: List<ComponentRef>, unmatched: List<ComponentRef>, unmatchedRoot: VirtualFile?) {
        if (refs.isEmpty()) return
        val connection = service.orgLabel(org)
        object : Task.Backgroundable(project, "Retrieving metadata from '$connection'", true) {
            override fun run(indicator: ProgressIndicator) {
                val defaultRoot = SfdxProject.packageDirectories(project).firstOrNull()
                val separate = unmatchedRoot != null && unmatchedRoot != defaultRoot
                val matched = if (separate) refs - unmatched.toSet() else refs
                if (matched.isNotEmpty()) retrieveInto(org, matched, null, indicator)
                if (separate && unmatched.isNotEmpty()) retrieveInto(org, unmatched, unmatchedRoot!!.path, indicator)
                SfdxProject.root(project)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
            }
        }.queue()
    }

    private fun retrieveInto(org: String?, refs: List<ComponentRef>, outputDir: String?, indicator: ProgressIndicator) {
        indicator.text2 = "Creating retrieve request"
        val manifest = manifest(org, refs, indicator)
        try {
            val args = mutableListOf("project", "retrieve", "start", "--wait", "60", "--manifest", manifest.path)
            if (outputDir != null) args += listOf("--output-dir", outputDir)
            DeployService.getInstance(project).runRetrieve(org, args, "Retrieve", indicator)
        } finally {
            FileUtil.delete(manifest.parentFile)
        }
    }

    fun retrieveForMerge(org: String?, refs: List<ComponentRef>) {
        if (refs.isEmpty()) return
        val connection = service.orgLabel(org)
        object : Task.Backgroundable(project, "Retrieving metadata from '$connection' for merge", true) {
            override fun run(indicator: ProgressIndicator) {
                val output = ProjectScratch.retrieveDirectory(project, "merge")
                if (output == null) {
                    SfUi.notify(
                        project,
                        DeployService.GROUP,
                        "Retrieve Failed",
                        "Cannot create a temporary retrieve directory inside the project.",
                        NotificationType.ERROR,
                    )
                    return
                }
                val manifest = manifest(org, refs, indicator)
                try {
                    indicator.text2 = "Submitting retrieve request"
                    val args = listOf("project", "retrieve", "start", "--wait", "60", "--manifest", manifest.path, "--output-dir", output.path) +
                        service.orgArgs(org)
                    val result = SfCli.run(project, args, 70 * 60 * 1000, indicator)
                    if (!result.success) {
                        SfUi.notify(project, DeployService.GROUP, "Retrieve Failed", result.message, NotificationType.ERROR)
                        return
                    }
                    indicator.text2 = "Comparing retrieved metadata with local metadata"
                    val changed = pairUp(output).filterNot { sameContent(it) }
                    if (changed.isEmpty()) {
                        SfUi.notify(project, DeployService.GROUP, "Retrieve Complete", "All files are up-to-date.")
                        return
                    }
                    val missing = changed.filter { it.local == null }
                    val (binary, mergeable) = changed.filter { it.local != null }.partition { isBinary(it) }
                    val entries = diffEntries(mergeable)
                    ApplicationManager.getApplication().invokeAndWait {
                        val added = missing.mapNotNull { copyInto(project, it.server, null, it.relative) }
                        if (added.isNotEmpty()) {
                            SfUi.notify(
                                project,
                                DeployService.GROUP,
                                "Retrieved Files Missing Locally",
                                "${added.size} file(s) had no local copy and were written into the project: ${names(added.map { it.name })}",
                            )
                        }
                        if (binary.isNotEmpty()) {
                            SfUi.notify(
                                project,
                                DeployService.GROUP,
                                "Binary Files Skipped",
                                "${binary.size} retrieved file(s) are binary and cannot be merged in the editor: " +
                                    names(binary.map { it.server.name }),
                            )
                        }
                        MetadataDiff.open(project, connection, "Merge with '$connection'", entries)
                    }
                } catch (e: SfCliException) {
                    SfUi.notify(project, DeployService.GROUP, "Retrieve Failed", e.message.orEmpty(), NotificationType.ERROR)
                } finally {
                    FileUtil.delete(manifest.parentFile)
                    ProjectScratch.delete(output)
                }
            }
        }.queue()
    }

    fun compare(org: String?, files: List<VirtualFile>) {
        if (files.isEmpty()) return
        val connection = service.orgLabel(org)
        object : Task.Backgroundable(project, "Comparing '${files.singleOrNull()?.name ?: "${files.size} files"}' with '$connection'", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val targets = files.map { SfdxProject.deployTarget(it) }.distinct()
                    val refs = LocalMetadata(project).components(targets.map { it.path }, indicator)
                    if (refs.isEmpty()) return
                    val output = ProjectScratch.retrieveDirectory(project, "compare")
                    if (output == null) {
                        SfUi.notify(
                            project,
                            DeployService.GROUP,
                            "Compare Failed",
                            "Cannot create a temporary retrieve directory inside the project.",
                            NotificationType.ERROR,
                        )
                        return
                    }
                    val manifest = manifest(org, refs, indicator)
                    try {
                        val args = listOf("project", "retrieve", "start", "--wait", "60", "--manifest", manifest.path, "--output-dir", output.path) +
                            service.orgArgs(org)
                        val result = SfCli.run(project, args, 70 * 60 * 1000, indicator)
                        if (!result.success) {
                            SfUi.notify(project, DeployService.GROUP, "Compare Failed", result.message, NotificationType.ERROR)
                            return
                        }
                        val pairs = pairUp(output).filter { retrieved ->
                            val local = retrieved.local
                            local != null && targets.any { target ->
                                local.path == target.path || local.path.startsWith(target.path + "/") || local.path == target.path + "-meta.xml"
                            }
                        }
                        if (pairs.isEmpty()) {
                            SfUi.notify(
                                project,
                                DeployService.GROUP,
                                "Compare Complete",
                                "No matching files were retrieved from '$connection'.",
                            )
                            return
                        }
                        val entries = diffEntries(pairs.filterNot { isBinary(it) })
                        if (entries.isEmpty()) {
                            SfUi.notify(
                                project,
                                DeployService.GROUP,
                                "Compare Complete",
                                "Only binary files were retrieved from '$connection'; they cannot be compared in the editor.",
                            )
                            return
                        }
                        ApplicationManager.getApplication().invokeAndWait {
                            MetadataDiff.open(project, connection, "Compare with '$connection'", entries)
                        }
                    } finally {
                        FileUtil.delete(manifest.parentFile)
                        ProjectScratch.delete(output)
                    }
                } catch (e: SfCliException) {
                    SfUi.notify(project, DeployService.GROUP, "Compare Failed", e.message.orEmpty(), NotificationType.ERROR)
                }
            }
        }.queue()
    }

    fun deploy(org: String?, refs: List<ComponentRef>, checkOnly: Boolean, ignoreWarnings: Boolean, ignoreErrors: Boolean, testLevel: TestLevel, tests: List<String>) {
        if (refs.isEmpty()) return
        object : Task.Backgroundable(project, "Creating deployment archive", true) {
            override fun run(indicator: ProgressIndicator) {
                val manifest = manifest(org, refs, indicator)
                SfUi.edt(project) {
                    DeployService.getInstance(project).submit(
                        DeployRequest(
                            org = org,
                            manifest = manifest,
                            label = if (refs.size == 1) refs.single().fullName else "${refs.size} components",
                            checkOnly = checkOnly,
                            ignoreWarnings = ignoreWarnings,
                            ignoreErrors = ignoreErrors,
                            testLevel = testLevel,
                            tests = tests,
                        ),
                    )
                }
            }
        }.queue()
    }

    fun delete(org: String?, refs: List<ComponentRef>, purge: Boolean, checkOnly: Boolean, onSuccess: () -> Unit = {}) {
        if (refs.isEmpty()) return
        if (!checkOnly && !service.confirmWrite("Delete metadata", org)) return
        val connection = service.orgLabel(org)
        object : Task.Backgroundable(project, "Deleting metadata", true) {
            override fun run(indicator: ProgressIndicator) {
                val dir = FileUtil.createTempDirectory("sf-cloud-delete", null, true)
                try {
                    val version = SfApi.getInstance(project).session(org, indicator).apiVersion
                    File(dir, "package.xml").writeText(MetadataXml.packageXml(emptyList(), version))
                    File(dir, "destructiveChanges.xml").writeText(MetadataXml.packageXml(refs, version))
                    val args = mutableListOf(
                        "project", "deploy", "start", "--wait", "60",
                        "--manifest", File(dir, "package.xml").path,
                        "--post-destructive-changes", File(dir, "destructiveChanges.xml").path,
                    )
                    if (purge) args += "--purge-on-delete"
                    if (checkOnly) args += "--dry-run"
                    args += service.orgArgs(org)
                    indicator.text2 = "Submitting deletion request"
                    val result = SfCli.run(project, args, 70 * 60 * 1000, indicator)
                    val title = if (checkOnly) "Delete (check-only)" else "Delete"
                    if (result.success) {
                        SfUi.notify(project, DeployService.GROUP, "$title Complete", "Deleted ${refs.size} components from `$connection`.")
                        if (!checkOnly) {
                            OfflineSymbolTable.getInstance(project).componentsChanged(org, refs)
                            SfUi.edt(project, onSuccess)
                        }
                    } else {
                        val failures = result.payload?.takeIf { it.isJsonObject }?.asJsonObject?.get("files").objects()
                            .filter { it.str("state") == "Failed" }
                            .joinToString("<br>") { "${it.str("fullName")}: ${it.str("error")}" }
                        SfUi.notify(project, DeployService.GROUP, "$title Failed", failures.ifEmpty { result.message }, NotificationType.ERROR)
                    }
                } catch (e: Exception) {
                    SfUi.notify(project, DeployService.GROUP, "Delete Failed", e.message.orEmpty(), NotificationType.ERROR)
                } finally {
                    FileUtil.delete(dir)
                }
            }
        }.queue()
    }

    private fun manifest(org: String?, refs: List<ComponentRef>, indicator: ProgressIndicator): File {
        val version = runCatching { SfApi.getInstance(project).session(org, indicator).apiVersion }.getOrDefault(SfApi.DEFAULT_API_VERSION)
        val dir = FileUtil.createTempDirectory("sf-cloud-manifest", null, true)
        val file = File(dir, "package.xml")
        file.writeText(MetadataXml.packageXml(refs, version))
        return file
    }

    private fun pairUp(output: File): List<RetrievedFile> {
        val index = HashMap<String, VirtualFile>()
        SfdxProject.packageDirectories(project).forEach { root ->
            VfsUtil.iterateChildrenRecursively(root, null) { file ->
                if (!file.isDirectory) VfsUtil.getRelativePath(file, root)?.let { index.putIfAbsent(normalize(it), file) }
                true
            }
        }
        return output.walkTopDown().filter { it.isFile }.filterNot { isEmptyShell(it) }.map { server ->
            val relative = normalize(relativePath(output, server))
            RetrievedFile(server, index[relative], relative)
        }.toList()
    }

    private fun diffEntries(pairs: List<RetrievedFile>): List<MetadataDiffEntry> =
        pairs.map { MetadataDiffEntry(it.relative, it.local, it.server.name, it.server.readText()) }

    private fun isEmptyShell(server: File): Boolean {
        if (!server.name.endsWith(META_SUFFIX)) return false
        val document = runCatching { MetadataXml.parse(server.readText()) }.getOrNull() ?: return false
        val children = document.documentElement?.childNodes ?: return false
        return (0 until children.length).none { children.item(it) is Element }
    }

    private fun sameContent(pair: RetrievedFile): Boolean {
        val local = pair.local?.takeIf { it.isValid } ?: return false
        val server = runCatching { pair.server.readBytes() }.getOrNull() ?: return false
        val current = runCatching { local.contentsToByteArray() }.getOrNull() ?: return false
        if (server.contentEquals(current)) return true
        if (isBinary(pair)) return false
        return normalized(server, StandardCharsets.UTF_8) == normalized(current, local.charset)
    }

    private fun normalized(bytes: ByteArray, charset: Charset): String =
        StringUtil.convertLineSeparators(String(bytes, charset)).trimEnd()

    private fun isBinary(pair: RetrievedFile): Boolean {
        val type = pair.local?.fileType ?: FileTypeManager.getInstance().getFileTypeByFileName(pair.server.name)
        return type.isBinary
    }

    private fun names(all: List<String>): String =
        all.take(5).joinToString(", ") + if (all.size > 5) " and ${all.size - 5} more" else ""

    companion object {
        private const val DEFAULT_LAYOUT = "main/default/"
        private const val META_SUFFIX = "-meta.xml"

        fun normalize(relative: String): String = relative.removePrefix(DEFAULT_LAYOUT)

        fun relativePath(base: File, file: File): String =
            FileUtil.getRelativePath(base, file)?.replace(File.separatorChar, '/').orEmpty()

        fun copyInto(project: Project, server: File, local: VirtualFile?, relative: String): VirtualFile? {
            return ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                if (local != null) {
                    local.setBinaryContent(Files.readAllBytes(server.toPath()))
                    local
                } else {
                    val root = SfdxProject.packageDirectories(project).firstOrNull() ?: return@runWriteAction null
                    val target = File(root.path, DEFAULT_LAYOUT + normalize(relative))
                    target.parentFile.mkdirs()
                    server.copyTo(target, overwrite = true)
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
                }
            }
        }
    }
}
