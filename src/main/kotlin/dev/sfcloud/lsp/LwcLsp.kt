package dev.sfcloud.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.settings.SfCloudSettings
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.SAXParserFactory

class LwcLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (!isLwcFile(file) || !SfCloudSettings.getInstance().state.lwcLspEnabled || !SfdxProject.isSfdx(project)) return
        if (!LanguageServers.lwcAvailable()) {
            LanguageServers.requestLwc(project)
            return
        }
        LwcWorkspaceXml.check(project)
        serverStarter.ensureServerStarted(LwcLspServerDescriptor(project))
    }

    companion object {
        private val EXTENSIONS = setOf("js", "ts", "html")

        fun isLwcFile(file: VirtualFile): Boolean {
            if (file.extension?.lowercase() !in EXTENSIONS) return false
            return file.parent?.parent?.name == "lwc"
        }
    }
}

class LwcLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "LWC") {
    override fun isSupportedFile(file: VirtualFile): Boolean = LwcLspServerSupportProvider.isLwcFile(file)

    override fun createCommandLine(): GeneralCommandLine {
        val command = mutableListOf(LanguageServers.node())
        LanguageServers.lwcGuardScript()?.let { command += listOf("--require", it.toString()) }
        command += listOf(LanguageServers.lwcServerScript().toString(), "--stdio")
        return GeneralCommandLine(command)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(roots.firstOrNull()?.path)
    }

    override fun startServerProcess(): OSProcessHandler =
        LanguageServers.watch(project, "LWC", super.startServerProcess())

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverStopped(normally: Boolean) = LanguageServers.reportStopped(project, "LWC", normally)
    }
}

object LwcWorkspaceXml {
    private val checked = ConcurrentHashMap<String, Boolean>()

    fun check(project: Project) {
        if (checked.putIfAbsent(project.locationHash, true) != null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val broken = ApplicationManager.getApplication().runReadAction<List<String>> { scan(project) }
            if (broken.isEmpty() || project.isDisposed) return@executeOnPooledThread
            SfNotifier.warn(
                project,
                "Salesforce metadata is not valid XML",
                "The LWC language server parses these files as strict XML. sf-cloud keeps it alive and repairs " +
                    "unescaped ampersands, but anything else it cannot read leaves completion incomplete:\n" +
                    broken.joinToString("\n"),
            )
        }
    }

    fun forget(project: Project) {
        checked.remove(project.locationHash)
    }

    fun indexedFiles(project: Project): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        SfdxProject.packageDirectories(project).forEach { directory ->
            VfsUtilCore.iterateChildrenRecursively(directory, null) { file ->
                if (!file.isDirectory && isIndexed(file)) files += file
                true
            }
        }
        return files
    }

    private fun isIndexed(file: VirtualFile): Boolean {
        val name = file.name
        val folder = file.parent?.name
        return when {
            name == "CustomLabels.labels-meta.xml" -> true
            folder == "staticresources" && name.endsWith(".resource-meta.xml") -> true
            folder == "contentassets" && name.endsWith(".asset-meta.xml") -> true
            folder == "messageChannels" && name.endsWith(".messageChannel-meta.xml") -> true
            else -> false
        }
    }

    private fun scan(project: Project): List<String> = indexedFiles(project).mapNotNull { file ->
        val problem = parseError(file) ?: return@mapNotNull null
        "${file.path}:${problem.lineNumber}:${problem.columnNumber} — ${problem.message}"
    }

    private fun parseError(file: VirtualFile): SAXParseException? = try {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val parser = factory.newSAXParser()
        parser.parse(ByteArrayInputStream(file.contentsToByteArray()), org.xml.sax.helpers.DefaultHandler())
        null
    } catch (e: SAXParseException) {
        e
    } catch (_: Exception) {
        null
    }
}
