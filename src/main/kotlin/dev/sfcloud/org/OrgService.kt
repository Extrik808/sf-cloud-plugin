package dev.sfcloud.org

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.messages.Topic
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.bool
import dev.sfcloud.core.objects
import dev.sfcloud.core.str
import dev.sfcloud.settings.SfCloudProjectSettings

enum class OrgKind(val label: String) {
    SCRATCH("Scratch"),
    SANDBOX("Sandbox"),
    PRODUCTION("Production"),
}

data class SfOrg(
    val alias: String?,
    val username: String,
    val kind: OrgKind,
    val isDefault: Boolean,
    val instanceUrl: String?,
    val connected: Boolean,
    val isDevHub: Boolean = false,
    val isDefaultDevHub: Boolean = false,
    val expirationDate: String? = null,
) {
    val key: String get() = alias ?: username
    val displayName: String get() = alias ?: username
}

fun interface OrgListener {
    fun orgsChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("SF Cloud orgs", OrgListener::class.java)
    }
}

@Service(Service.Level.PROJECT)
class OrgService(private val project: Project) {
    @Volatile
    var orgs: List<SfOrg> = emptyList()
        private set

    @Volatile
    var loading: Boolean = false
        private set

    @Volatile
    private var loaded = false

    val targetOrgKey: String?
        get() = SfCloudProjectSettings.getInstance(project).state.targetOrg?.takeIf { it.isNotBlank() }
            ?: orgs.firstOrNull { it.isDefault }?.key

    val targetOrg: SfOrg?
        get() = targetOrgKey?.let { find(it) }

    fun find(key: String): SfOrg? = orgs.firstOrNull { it.alias == key || it.username == key }

    fun targetOrgArgs(): List<String> = targetOrgKey?.let { listOf("--target-org", it) }.orEmpty()

    fun orgKey(key: String?): String? = key?.takeIf { it.isNotBlank() } ?: targetOrgKey

    fun org(key: String?): SfOrg? = orgKey(key)?.let { find(it) }

    fun orgLabel(key: String?): String = orgKey(key) ?: "default org"

    fun orgArgs(key: String?): List<String> = orgKey(key)?.let { listOf("--target-org", it) }.orEmpty()

    fun select(org: SfOrg) {
        select(org.key)
    }

    fun select(key: String) {
        SfCloudProjectSettings.getInstance(project).state.targetOrg = key
        fireChanged()
    }

    fun ensureLoaded() {
        if (!loaded && !loading) refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        fireChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                orgs = load()
                loaded = true
            } catch (e: Exception) {
                SfNotifier.error(project, "Cannot list Salesforce orgs", e.message.orEmpty())
            } finally {
                loading = false
                fireChanged()
            }
        }
    }

    private fun load(): List<SfOrg> {
        val result = SfCli.run(project, listOf("org", "list"), timeoutMs = 120_000)
        val payload = result.payload?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException(result.message)
        val scratch = payload.get("scratchOrgs").objects()
        val sandboxes = payload.get("sandboxes").objects()
        val sandboxUsers = sandboxes.mapNotNull { it.str("username") }.toSet()
        val all = scratch + payload.get("nonScratchOrgs").objects() + sandboxes + payload.get("other").objects()
        val scratchUsers = scratch.mapNotNull { it.str("username") }.toSet()
        return all
            .mapNotNull { json ->
                val username = json.str("username") ?: return@mapNotNull null
                val kind = when {
                    username in scratchUsers || json.bool("isScratch") == true -> OrgKind.SCRATCH
                    username in sandboxUsers || json.bool("isSandbox") == true -> OrgKind.SANDBOX
                    else -> OrgKind.PRODUCTION
                }
                val status = json.str("connectedStatus") ?: json.str("status")
                SfOrg(
                    alias = json.str("alias"),
                    username = username,
                    kind = kind,
                    isDefault = json.bool("isDefaultUsername") == true,
                    instanceUrl = json.str("instanceUrl"),
                    connected = status == null || status.equals("Connected", true) || status.equals("Active", true),
                    isDevHub = json.bool("isDevHub") == true,
                    isDefaultDevHub = json.bool("isDefaultDevHubUsername") == true,
                    expirationDate = json.str("expirationDate"),
                )
            }
            .distinctBy { it.username }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.displayName.lowercase() }))
    }

    fun confirmWrite(actionName: String, key: String? = null): Boolean {
        val org = org(key) ?: return true
        if (org.kind != OrgKind.PRODUCTION) return true
        return Messages.showYesNoDialog(
            project,
            "${org.displayName} is not a sandbox or scratch org. $actionName anyway?",
            "SF Cloud: Production Org",
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    private fun fireChanged() {
        ApplicationManager.getApplication().invokeLater({
            project.messageBus.syncPublisher(OrgListener.TOPIC).orgsChanged()
        }, project.disposed)
    }

    companion object {
        fun getInstance(project: Project): OrgService = project.service()
    }
}
