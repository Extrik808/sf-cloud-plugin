package dev.sfcloud.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsDisabled
import com.intellij.platform.lsp.api.customization.LspFindReferencesDisabled
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.lang.ApexLanguage
import dev.sfcloud.lang.AnonymousApexFileType
import dev.sfcloud.settings.SfCloudSettings
import java.nio.charset.StandardCharsets

class ApexLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (!isApex(file) || !SfCloudSettings.getInstance().state.apexLspEnabled || !SfdxProject.isSfdx(project)) return
        if (!LanguageServers.apexAvailable()) {
            LanguageServers.requestApex(project)
            return
        }
        serverStarter.ensureServerStarted(ApexLspServerDescriptor(project))
    }

    companion object {
        fun isApex(file: VirtualFile): Boolean =
            (file.fileType as? com.intellij.openapi.fileTypes.LanguageFileType)?.language == ApexLanguage
    }
}

class ApexLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Apex") {
    override fun isSupportedFile(file: VirtualFile): Boolean = ApexLspServerSupportProvider.isApex(file)

    override fun getLanguageId(file: VirtualFile): String =
        if (file.fileType == AnonymousApexFileType) "apex-anon" else "apex"

    override fun createCommandLine(): GeneralCommandLine {
        val settings = SfCloudSettings.getInstance().state
        return GeneralCommandLine(
            LanguageServers.apexJava(),
            "-cp",
            LanguageServers.apexJar().toString(),
            "-Ddebug.internal.errors=true",
            "-Ddebug.semantic.errors=${settings.apexSemanticErrors}",
            "-Ddebug.completion.statistics=false",
            "-Dlwc.typegeneration.disabled=true",
            "-Xmx${settings.apexLspMaxHeapMb}M",
            "apex.jorje.lsp.ApexLanguageServerLauncher",
        )
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(roots.firstOrNull()?.path)
    }

    override fun startServerProcess(): OSProcessHandler =
        LanguageServers.watch(project, "Apex", super.startServerProcess())

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverStopped(normally: Boolean) = LanguageServers.reportStopped(project, "Apex", normally)
    }

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val findReferencesCustomizer = LspFindReferencesDisabled

        override val documentHighlightsCustomizer = LspDocumentHighlightsDisabled
    }

    override fun createInitializationOptions(): Any = mapOf(
        "enableEmbeddedSoqlCompletion" to false,
        "enableErrorToTelemetry" to false,
        "enableSynchronizedInitJobs" to true,
    )
}
