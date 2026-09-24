package dev.sfcloud.org

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComponentWithBrowseButton
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfNotifier
import dev.sfcloud.core.SfResult
import dev.sfcloud.core.str
import dev.sfcloud.deploy.DeployRequest
import dev.sfcloud.deploy.DeployService
import dev.sfcloud.ost.OfflineSymbolTable
import dev.sfcloud.settings.ScratchOrgState
import dev.sfcloud.settings.SfCloudProjectSettings
import dev.sfcloud.ui.SfUi
import java.io.File
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

data class ScratchOrgRequest(
    val devHub: String,
    val alias: String,
    val definitionFile: String,
    val durationDays: Int,
    val useForProject: Boolean,
    val setCliDefault: Boolean,
    val noNamespace: Boolean,
    val noAncestors: Boolean,
    val platformCliSignup: Boolean,
    val generatePassword: Boolean,
    val pushSource: Boolean,
    val permissionSets: List<String>,
    val openAfterCreate: Boolean,
)

object ScratchOrgCommands {
    const val WAIT_MINUTES = 60

    fun create(request: ScratchOrgRequest): List<String> {
        val args = mutableListOf(
            "org", "create", "scratch",
            "--target-dev-hub", request.devHub,
            "--alias", request.alias,
            "--definition-file", request.definitionFile,
            "--duration-days", request.durationDays.toString(),
            "--wait", WAIT_MINUTES.toString(),
        )
        if (request.setCliDefault) args += "--set-default"
        if (request.noNamespace) args += "--no-namespace"
        if (request.noAncestors) args += "--no-ancestors"
        return args
    }

    fun environment(request: ScratchOrgRequest): Map<String, String> =
        if (request.platformCliSignup) {
            mapOf(
                "SF_SCRATCH_SIGNUP_CONNECTED_APP" to "PlatformCLI",
                "SF_SCRATCH_SIGNUP_CALLBACK_URL" to "http://localhost:1717/OauthRedirect",
            )
        } else {
            emptyMap()
        }

    fun assignPermissionSets(alias: String, names: List<String>): List<String> =
        listOf("org", "assign", "permset", "--target-org", alias) + names.flatMap { listOf("--name", it) }

    fun generatePassword(alias: String): List<String> = listOf("org", "generate", "password", "--target-org", alias)

    fun delete(key: String): List<String> = listOf("org", "delete", "scratch", "--target-org", key, "--no-prompt")

    fun splitPermissionSets(text: String): List<String> = text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun suggestedAlias(branch: String?, projectName: String): String {
        val ticket = branch?.let { TICKET.find(it)?.value }
        val base = ticket ?: branch?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "HEAD" } ?: projectName
        return base.replace(Regex("[^A-Za-z0-9_.-]"), "-") + "-sc"
    }

    fun currentBranch(root: File?): String? {
        val head = root?.resolve(".git/HEAD")?.takeIf { it.isFile } ?: return null
        return head.readText().trim().removePrefix("ref:").trim().removePrefix("refs/heads/").takeIf { it.isNotEmpty() }
    }

    private val TICKET = Regex("[A-Z][A-Z0-9]+-\\d+")
}

class ScratchOrgService(private val project: Project) {
    fun create(request: ScratchOrgRequest) {
        SfUi.background(project, "Creating Scratch Org '${request.alias}'", onError = { SfNotifier.error(project, "Cannot create scratch org", it) }) { indicator ->
            indicator.text2 = "Requesting the scratch org from ${request.devHub} (up to ${ScratchOrgCommands.WAIT_MINUTES} minutes)"
            val created = SfCli.run(
                project,
                ScratchOrgCommands.create(request),
                (ScratchOrgCommands.WAIT_MINUTES + 5) * 60 * 1000,
                indicator,
                ScratchOrgCommands.environment(request),
            )
            if (!created.success) {
                SfNotifier.error(project, "Cannot create scratch org '${request.alias}'", hint(created, request))
                return@background
            }
            val username = created.payload?.takeIf { it.isJsonObject }?.asJsonObject?.str("username").orEmpty()
            val problems = mutableListOf<String>()
            if (request.permissionSets.isNotEmpty()) {
                indicator.text2 = "Assigning permission sets"
                val assigned = SfCli.run(project, ScratchOrgCommands.assignPermissionSets(request.alias, request.permissionSets), 10 * 60 * 1000, indicator)
                if (!assigned.success) problems += "Permission sets: ${assigned.message}"
            }
            var password: String? = null
            if (request.generatePassword) {
                indicator.text2 = "Generating a password"
                val generated = SfCli.run(project, ScratchOrgCommands.generatePassword(request.alias), 5 * 60 * 1000, indicator)
                if (generated.success) {
                    password = generated.payload?.takeIf { it.isJsonObject }?.asJsonObject?.str("password")
                } else {
                    problems += "Password: ${generated.message}"
                }
            }
            SfUi.edt(project) {
                val orgs = OrgService.getInstance(project)
                if (request.useForProject) {
                    orgs.select(request.alias)
                    OfflineSymbolTable.getInstance(project).generate()
                }
                orgs.refresh()
                val details = buildString {
                    append(username.ifEmpty { request.alias })
                    append(" · expires in ${request.durationDays} days")
                    password?.let { append("<br/>Password: $it") }
                    problems.forEach { append("<br/>$it") }
                }
                if (problems.isEmpty()) {
                    SfNotifier.info(project, "Scratch org '${request.alias}' created", details)
                } else {
                    SfNotifier.warn(project, "Scratch org '${request.alias}' created with problems", details)
                }
                if (request.pushSource) {
                    DeployService.getInstance(project).submit(DeployRequest(request.alias, label = "project source", tracked = true))
                }
                if (request.openAfterCreate) open(request.alias)
            }
        }
    }

    fun delete(org: SfOrg) {
        val answer = Messages.showYesNoDialog(
            project,
            "Delete scratch org '${org.displayName}' (${org.username})? This cannot be undone.",
            "Delete Scratch Org",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        SfUi.background(project, "Deleting Scratch Org '${org.displayName}'", onError = { SfNotifier.error(project, "Cannot delete scratch org", it) }) { indicator ->
            val result = SfCli.run(project, ScratchOrgCommands.delete(org.key), 5 * 60 * 1000, indicator)
            SfUi.edt(project) {
                if (result.success) {
                    SfNotifier.info(project, "Scratch org '${org.displayName}' deleted")
                } else {
                    SfNotifier.error(project, "Cannot delete scratch org '${org.displayName}'", result.message)
                }
                OrgService.getInstance(project).refresh()
            }
        }
    }

    private fun open(alias: String) {
        SfUi.background(project, "Opening '$alias'", onError = { SfNotifier.error(project, "Cannot open $alias", it) }) { indicator ->
            val result = SfCli.run(project, listOf("org", "open", "--target-org", alias), 120_000, indicator)
            if (!result.success) SfNotifier.error(project, "Cannot open $alias", result.message)
        }
    }

    private fun hint(result: SfResult, request: ScratchOrgRequest): String {
        val signupFailure = result.message.contains("C-1016") || result.json?.str("name") == "RemoteOrgSignupFailed"
        return if (signupFailure && !request.platformCliSignup) {
            "${result.message}<br/>Retry with <b>Use PlatformCLI connected app for signup</b> enabled."
        } else {
            result.message
        }
    }

    companion object {
        fun getInstance(project: Project): ScratchOrgService = ScratchOrgService(project)
    }
}

class CreateScratchOrgDialog(private val project: Project, private val devHubs: List<SfOrg>) : DialogWrapper(project, true) {
    private val settings: ScratchOrgState = SfCloudProjectSettings.getInstance(project).state.scratchOrg
    private val devHubCombo = ComboBox(devHubs.toTypedArray())
    private val aliasField = JBTextField()
    private val definitionCombo = ComboBox(definitionFiles().toTypedArray()).apply { isEditable = true }
    private val definitionField = ComponentWithBrowseButton(definitionCombo) { browseDefinition() }
    private val durationSpinner = JSpinner(SpinnerNumberModel(settings.durationDays.coerceIn(1, 30), 1, 30, 1))
    private val useForProject = JBCheckBox("Use as the connection for '${project.name}'", settings.useForProject)
    private val setCliDefault = JBCheckBox("Set as the Salesforce CLI default org", settings.setCliDefault)
    private val noNamespace = JBCheckBox("No namespace", settings.noNamespace)
    private val noAncestors = JBCheckBox("No 2GP ancestors", settings.noAncestors)
    private val platformCliSignup = JBCheckBox("Use PlatformCLI connected app for signup", settings.platformCliSignup).apply {
        toolTipText = "Sets SF_SCRATCH_SIGNUP_CONNECTED_APP=PlatformCLI. Needed when the Dev Hub is authorized through an External Client App and signup fails with C-1016."
    }
    private val pushSource = JBCheckBox("Push project source after creation", settings.pushSource)
    private val generatePassword = JBCheckBox("Generate a password for the admin user", settings.generatePassword)
    private val permissionSets = JBTextField(settings.permissionSets.orEmpty())
    private val openAfterCreate = JBCheckBox("Open in the browser after creation", settings.openAfterCreate)

    init {
        title = "Create Scratch Org"
        setOKButtonText("Create")
        devHubCombo.renderer = SimpleListCellRenderer.create("") { org ->
            buildString {
                append(org.displayName)
                if (org.alias != null) append(" (${Connections.shortenUsername(org.username)})")
                if (org.isDefaultDevHub) append(" · default")
            }
        }
        val remembered = devHubs.firstOrNull { it.key == settings.devHub || it.username == settings.devHub }
        devHubCombo.selectedItem = remembered ?: devHubs.firstOrNull { it.isDefaultDevHub } ?: devHubs.firstOrNull()
        definitionCombo.selectedItem = settings.definitionFile.orEmpty().ifEmpty { "config/project-scratch-def.json" }
        aliasField.text = ScratchOrgCommands.suggestedAlias(
            ScratchOrgCommands.currentBranch(project.guessProjectDir()?.path?.let(::File)),
            project.name,
        )
        permissionSets.emptyText.text = "Comma-separated permission set names"
        init()
    }

    val request: ScratchOrgRequest
        get() = ScratchOrgRequest(
            devHub = (devHubCombo.selectedItem as SfOrg).key,
            alias = aliasField.text.trim(),
            definitionFile = definitionPath(),
            durationDays = durationSpinner.value as Int,
            useForProject = useForProject.isSelected,
            setCliDefault = setCliDefault.isSelected,
            noNamespace = noNamespace.isSelected,
            noAncestors = noAncestors.isSelected,
            platformCliSignup = platformCliSignup.isSelected,
            generatePassword = generatePassword.isSelected,
            pushSource = pushSource.isSelected,
            permissionSets = ScratchOrgCommands.splitPermissionSets(permissionSets.text),
            openAfterCreate = openAfterCreate.isSelected,
        )

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Dev Hub:", devHubCombo)
        .addLabeledComponent("Alias:", aliasField)
        .addLabeledComponent("Definition file:", definitionField)
        .addLabeledComponent("Duration (days):", durationSpinner)
        .addComponent(SfUi.section("Options"))
        .addComponent(useForProject)
        .addComponent(setCliDefault)
        .addComponent(noNamespace)
        .addComponent(noAncestors)
        .addComponent(platformCliSignup)
        .addComponent(SfUi.section("After Creation"))
        .addComponent(pushSource)
        .addComponent(generatePassword)
        .addLabeledComponent("Permission sets:", permissionSets)
        .addComponent(openAfterCreate)
        .addComponent(JBLabel("Creating a scratch org can take several minutes.").apply { componentStyle = UIUtil.ComponentStyle.SMALL })
        .panel

    override fun getPreferredFocusedComponent(): JComponent = aliasField

    override fun doValidate(): ValidationInfo? {
        val alias = aliasField.text.trim()
        val definition = definitionPath()
        return when {
            devHubCombo.selectedItem == null -> ValidationInfo("No Dev Hub is authorized. Authorize a Dev Hub org first.", devHubCombo)
            alias.isEmpty() -> ValidationInfo("An alias must be specified", aliasField)
            !Regex("[A-Za-z0-9_.-]+").matches(alias) -> ValidationInfo("The alias may contain letters, digits, '.', '_' and '-' only", aliasField)
            OrgService.getInstance(project).find(alias) != null -> ValidationInfo("A connection with alias '$alias' already exists", aliasField)
            definition.isEmpty() -> ValidationInfo("A definition file must be specified", definitionCombo)
            !resolve(definition).isFile -> ValidationInfo("The definition file does not exist", definitionCombo)
            else -> null
        }
    }

    override fun doOKAction() {
        val request = request
        settings.devHub = request.devHub
        settings.definitionFile = request.definitionFile
        settings.durationDays = request.durationDays
        settings.useForProject = request.useForProject
        settings.setCliDefault = request.setCliDefault
        settings.noNamespace = request.noNamespace
        settings.noAncestors = request.noAncestors
        settings.platformCliSignup = request.platformCliSignup
        settings.generatePassword = request.generatePassword
        settings.pushSource = request.pushSource
        settings.permissionSets = request.permissionSets.joinToString(", ")
        settings.openAfterCreate = request.openAfterCreate
        super.doOKAction()
    }

    private fun definitionPath(): String = (definitionCombo.editor.item ?: definitionCombo.selectedItem)?.toString()?.trim().orEmpty()

    private fun root(): File? = project.guessProjectDir()?.path?.let(::File)

    private fun resolve(path: String): File = File(path).takeIf { it.isAbsolute } ?: File(root(), path)

    private fun definitionFiles(): List<String> {
        val config = root()?.resolve("config")?.takeIf { it.isDirectory } ?: return emptyList()
        return config.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it.readText().contains("\"edition\"", ignoreCase = true) }
            .map { "config/${it.name}" }
            .sorted()
    }

    private fun browseDefinition() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Select Scratch Org Definition File")
        val current = LocalFileSystem.getInstance().findFileByIoFile(resolve(definitionPath()))
        val chosen = FileChooser.chooseFile(descriptor, project, current) ?: return
        val base = project.guessProjectDir()
        definitionCombo.selectedItem = base?.let { VfsUtilCore.getRelativePath(chosen, it) } ?: chosen.path
    }
}

class CreateScratchOrgAction : SfdxProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val orgs = OrgService.getInstance(project)
        val devHubs = orgs.orgs.filter { it.isDevHub && it.connected }
        if (devHubs.isEmpty()) {
            SfNotifier.warn(
                project,
                "No Dev Hub connection",
                if (orgs.loading || orgs.orgs.isEmpty()) "Connections are still loading. Try again in a moment." else "Authorize a Dev Hub org first.",
            )
            orgs.ensureLoaded()
            return
        }
        val dialog = CreateScratchOrgDialog(project, devHubs)
        if (dialog.showAndGet()) ScratchOrgService.getInstance(project).create(dialog.request)
    }
}

class DeleteScratchOrgAction : SfdxProjectAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        e.presentation.isEnabled = OrgService.getInstance(project).targetOrg?.kind == OrgKind.SCRATCH
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val org = OrgService.getInstance(project).targetOrg?.takeIf { it.kind == OrgKind.SCRATCH } ?: return
        ScratchOrgService.getInstance(project).delete(org)
    }
}
