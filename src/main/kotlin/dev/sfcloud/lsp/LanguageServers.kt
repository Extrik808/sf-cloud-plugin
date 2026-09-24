package dev.sfcloud.lsp

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.util.io.HttpRequests
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.obj
import dev.sfcloud.core.str
import dev.sfcloud.settings.SfCloudSettings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.io.path.exists

object LanguageServers {
    private const val APEX_EXTENSION_API = "https://open-vsx.org/api/salesforce/salesforcedx-vscode-apex"
    private const val APEX_JAR_ENTRY = "extension/dist/apex-jorje-lsp.jar"
    private const val LWC_PACKAGE = "@salesforce/lwc-language-server"
    private const val LWC_GUARD = "lwc-guard.js"
    private const val STDERR_LINES = 40

    private val apexRequested = AtomicBoolean(false)
    private val lwcRequested = AtomicBoolean(false)
    private val installingApex = AtomicBoolean(false)
    private val installingLwc = AtomicBoolean(false)
    private val crashReported = ConcurrentHashMap<String, Boolean>()
    private val stderr = ConcurrentHashMap<String, ArrayDeque<String>>()

    private val home: Path get() = PathManager.getSystemDir().resolve("sf-cloud")

    fun apexJar(): Path {
        val configured = SfCloudSettings.getInstance().state.apexLspJarPath.orEmpty().trim()
        return if (configured.isNotEmpty()) Path.of(configured) else home.resolve("apex").resolve("apex-jorje-lsp.jar")
    }

    fun apexJava(): String {
        val configured = SfCloudSettings.getInstance().state.apexLspJavaPath.orEmpty().trim()
        if (configured.isNotEmpty()) return configured
        return Path.of(System.getProperty("java.home"), "bin", "java").toString()
    }

    fun lwcServerScript(): Path =
        home.resolve("lwc").resolve("node_modules").resolve(LWC_PACKAGE).resolve("lib").resolve("server.js")

    fun lwcGuardScript(): Path? = try {
        val bytes = javaClass.getResourceAsStream("/lsp/$LWC_GUARD")?.use { it.readBytes() }
        val target = home.resolve("lwc").resolve(LWC_GUARD)
        when {
            bytes == null -> null
            target.exists() && Files.readAllBytes(target).contentEquals(bytes) -> target
            else -> {
                Files.createDirectories(target.parent)
                val temp = Files.createTempFile(target.parent, "lwc-guard", ".js")
                Files.write(temp, bytes)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                target
            }
        }
    } catch (e: Exception) {
        thisLogger().warn("Cannot unpack $LWC_GUARD", e)
        null
    }

    fun node(): String {
        val configured = SfCloudSettings.getInstance().state.nodePath.orEmpty().trim()
        if (configured.isNotEmpty()) return configured
        return PathEnvironmentVariableUtil.findInPath("node")?.absolutePath ?: "node"
    }

    private fun npm(): String {
        val nodeFile = File(node())
        val sibling = nodeFile.parentFile?.resolve("npm")
        if (sibling != null && sibling.canExecute()) return sibling.absolutePath
        return PathEnvironmentVariableUtil.findInPath("npm")?.absolutePath ?: "npm"
    }

    fun apexAvailable(): Boolean = apexJar().exists()

    fun lwcAvailable(): Boolean = lwcServerScript().exists()

    fun requestApex(project: Project) {
        if (!apexRequested.compareAndSet(false, true)) return
        if (SfCloudSettings.getInstance().state.autoInstallServers) {
            installApex(project)
            return
        }
        SfNotifier.warn(
            project,
            "Apex language server is not installed",
            "Completion, navigation and error checking for Apex need Salesforce's Apex language server (BSD-3, from Open VSX).",
            NotificationAction.createSimpleExpiring("Download") { installApex(project) },
        )
    }

    fun requestLwc(project: Project) {
        if (!lwcRequested.compareAndSet(false, true)) return
        if (SfCloudSettings.getInstance().state.autoInstallServers) {
            installLwc(project)
            return
        }
        SfNotifier.warn(
            project,
            "LWC language server is not installed",
            "Lightning Web Component completion needs $LWC_PACKAGE from npm.",
            NotificationAction.createSimpleExpiring("Install with npm") { installLwc(project) },
        )
    }

    fun installApex(project: Project?) {
        if (!installingApex.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Downloading Apex language server", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Looking up the latest Salesforce Apex extension"
                    val meta = JsonParser.parseString(HttpRequests.request(APEX_EXTENSION_API).readString(indicator)).asJsonObject
                    val url = meta.obj("files")?.str("download") ?: error("Open VSX returned no download URL")
                    val version = meta.str("version").orEmpty()
                    val vsix = Files.createTempFile("sf-cloud-apex", ".vsix")
                    try {
                        indicator.text = "Downloading salesforcedx-vscode-apex $version"
                        HttpRequests.request(url).saveToFile(vsix.toFile(), indicator)
                        val target = home.resolve("apex").resolve("apex-jorje-lsp.jar")
                        Files.createDirectories(target.parent)
                        ZipFile(vsix.toFile()).use { zip ->
                            val entry = zip.getEntry(APEX_JAR_ENTRY) ?: error("$APEX_JAR_ENTRY not found in the extension")
                            val temp = Files.createTempFile(target.parent, "apex", ".jar")
                            zip.getInputStream(entry).use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
                            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        }
                    } finally {
                        Files.deleteIfExists(vsix)
                    }
                    SfNotifier.info(project, "Apex language server $version installed")
                    project?.let { restart(it) }
                } catch (e: Exception) {
                    SfNotifier.error(project, "Cannot download Apex language server", e.message.orEmpty())
                } finally {
                    installingApex.set(false)
                }
            }
        }.queue()
    }

    fun installLwc(project: Project?) {
        if (!installingLwc.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Installing LWC language server", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val prefix = home.resolve("lwc")
                    Files.createDirectories(prefix)
                    indicator.text = "npm install $LWC_PACKAGE"
                    val command = GeneralCommandLine(npm(), "install", "--prefix", prefix.toString(), "--no-audit", "--no-fund", LWC_PACKAGE)
                        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                        .withCharset(StandardCharsets.UTF_8)
                    val output = try {
                        CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, 10 * 60 * 1000)
                    } catch (e: Exception) {
                        SfNotifier.error(project, "Cannot run npm", e.message.orEmpty())
                        return
                    }
                    if (output.exitCode == 0 && lwcAvailable()) {
                        SfNotifier.info(project, "LWC language server installed")
                        project?.let { restart(it) }
                    } else {
                        SfNotifier.error(project, "npm install failed", output.stderr.takeLast(2000))
                    }
                } finally {
                    installingLwc.set(false)
                }
            }
        }.queue()
    }

    fun watch(project: Project, server: String, handler: OSProcessHandler): OSProcessHandler {
        val key = key(project, server)
        stderr[key] = ArrayDeque()
        crashReported.remove(key)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputType.STDERR) return
                val lines = stderr.getOrPut(key) { ArrayDeque() }
                synchronized(lines) {
                    event.text.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.forEach { lines.addLast(it) }
                    while (lines.size > STDERR_LINES) lines.removeFirst()
                }
            }
        })
        return handler
    }

    fun reportStopped(project: Project, server: String, normally: Boolean) {
        if (normally || project.isDisposed) return
        val key = key(project, server)
        if (crashReported.putIfAbsent(key, true) != null) return
        val lines = stderr[key]
        val details = synchronized(lines ?: ArrayDeque<String>()) { lines?.toList().orEmpty() }
        SfNotifier.error(
            project,
            "$server language server stopped unexpectedly",
            details.takeLast(8).joinToString("\n").ifEmpty { "See idea.log for the server output." },
            NotificationAction.createSimpleExpiring("Restart") { restart(project) },
        )
    }

    fun restart(project: Project) {
        crashReported.clear()
        val manager = LspServerManager.getInstance(project)
        manager.stopAndRestartIfNeeded(ApexLspServerSupportProvider::class.java)
        manager.stopAndRestartIfNeeded(LwcLspServerSupportProvider::class.java)
    }

    private fun key(project: Project, server: String): String = "${project.locationHash}/$server"
}
