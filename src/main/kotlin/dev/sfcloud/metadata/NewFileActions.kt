package dev.sfcloud.metadata

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.deploy.DeployService
import dev.sfcloud.ui.SfUi
import javax.swing.JComponent

object NewFiles {
    val TRIGGER_EVENTS = listOf(
        "before insert",
        "before update",
        "before delete",
        "after insert",
        "after update",
        "after delete",
        "after undelete",
    )

    fun targetFolder(project: Project, context: VirtualFile?, kind: SfFileKind): String? =
        NewFileTemplates.location(project, context, kind)?.path

    fun create(
        project: Project,
        kind: SfFileKind,
        name: String,
        options: SfNewFileOptions,
        context: VirtualFile?,
    ): VirtualFile? {
        val location = NewFileTemplates.location(project, context, kind) ?: return null
        val files = NewFileTemplates.files(kind, name, options, NewFileTemplates.apiVersion(project))
        return WriteCommandAction.writeCommandAction(project).withName("Create ${kind.title}").compute<VirtualFile?, RuntimeException> {
            val folder = if (location.relative.isEmpty()) {
                location.directory
            } else {
                VfsUtil.createDirectoryIfMissing(location.directory, location.relative)
            } ?: return@compute null
            files.forEach { file ->
                val directory = file.path.substringBeforeLast('/', "")
                val parent = if (directory.isEmpty()) folder else VfsUtil.createDirectoryIfMissing(folder, directory)
                    ?: return@compute null
                val created = parent.findChild(file.path.substringAfterLast('/')) ?: parent.createChildData(this, file.path.substringAfterLast('/'))
                VfsUtil.saveText(created, file.content)
            }
            folder.findFileByRelativePath(NewFileTemplates.primary(kind, name))
        }
    }

    fun exists(project: Project, kind: SfFileKind, name: String, context: VirtualFile?): Boolean {
        val location = NewFileTemplates.location(project, context, kind) ?: return false
        val folder = if (location.relative.isEmpty()) location.directory else location.directory.findFileByRelativePath(location.relative)
        folder ?: return false
        val bundle = kind == SfFileKind.LWC || kind == SfFileKind.AURA
        return folder.findFileByRelativePath(if (bundle) name else NewFileTemplates.primary(kind, name)) != null
    }
}

class NewSalesforceFileDialog(project: Project, private val kind: SfFileKind, private val location: String? = null) : DialogWrapper(project, true) {
    private val nameField = JBTextField(24)
    private val sobjectField = JBTextField(24)
    private val labelField = JBTextField(24)
    private val events = NewFiles.TRIGGER_EVENTS.map { event ->
        JBCheckBox(event, event == "before insert" || event == "before update")
    }
    private val exposed = JBCheckBox("Expose in Lightning App Builder", false)
    private val stylesheet = JBCheckBox("Create stylesheet", false)
    private val jest = JBCheckBox("Create Jest test", false)
    private val controller = JBCheckBox("Create controller", true)
    private val helper = JBCheckBox("Create helper", false)

    init {
        title = "New ${kind.title}"
        setOKButtonText("Create")
        init()
    }

    val fileName: String get() = nameField.text.trim()

    fun options(): SfNewFileOptions = SfNewFileOptions(
        sobject = sobjectField.text.trim(),
        events = events.filter { it.isSelected }.map { it.text },
        exposed = exposed.isSelected,
        withCss = stylesheet.isSelected,
        withTest = jest.isSelected,
        withController = controller.isSelected,
        withHelper = helper.isSelected,
        label = labelField.text.trim(),
    )

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField) }
        location?.let { path -> row("Directory:") { comment(path) } }
        when (kind) {
            SfFileKind.APEX_TRIGGER -> {
                row("SObject:") { cell(sobjectField) }
                group("Events") {
                    events.chunked(4).forEach { chunk ->
                        row { chunk.forEach { cell(it) } }
                    }
                }
            }

            SfFileKind.LWC -> {
                row { cell(exposed) }
                row { cell(stylesheet) }
                row { cell(jest) }
            }

            SfFileKind.AURA -> {
                row { cell(controller) }
                row { cell(helper) }
                row { cell(stylesheet) }
            }

            SfFileKind.VISUALFORCE_PAGE, SfFileKind.VISUALFORCE_COMPONENT -> row("Label:") { cell(labelField) }

            else -> Unit
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = fileName
        if (name.isEmpty()) return ValidationInfo("Enter a name", nameField)
        val valid = when (kind) {
            SfFileKind.LWC -> name.matches(LWC_NAME)
            else -> name.matches(APEX_NAME)
        }
        if (!valid) {
            val message = if (kind == SfFileKind.LWC) {
                "A Lightning web component name must start with a lowercase letter and contain only letters and digits"
            } else {
                "A name must start with a letter and contain only letters, digits and underscores"
            }
            return ValidationInfo(message, nameField)
        }
        if (kind == SfFileKind.APEX_TRIGGER) {
            if (sobjectField.text.trim().isEmpty()) return ValidationInfo("Enter the SObject", sobjectField)
            if (events.none { it.isSelected }) return ValidationInfo("Select at least one event", events.first())
        }
        return null
    }

    companion object {
        private val APEX_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
        private val LWC_NAME = Regex("[a-z][a-zA-Z0-9]*")
    }
}

abstract class CreateSalesforceFileAction(private val kind: SfFileKind) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { SfdxProject.isSfdx(it) } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val base = SfdxProject.root(project)
        val target = NewFiles.targetFolder(project, context, kind)?.let { path ->
            base?.path?.let { root -> path.removePrefix("$root/") } ?: path
        }
        val dialog = NewSalesforceFileDialog(project, kind, target)
        if (!dialog.showAndGet()) return
        val name = dialog.fileName
        if (NewFiles.exists(project, kind, name, context)) {
            SfUi.notify(
                project,
                DeployService.GROUP,
                "Cannot create ${kind.title}",
                "'$name' already exists in ${NewFiles.targetFolder(project, context, kind)}.",
                NotificationType.ERROR,
            )
            return
        }
        val created = NewFiles.create(project, kind, name, dialog.options(), context)
        if (created == null) {
            SfUi.notify(
                project,
                DeployService.GROUP,
                "Cannot create ${kind.title}",
                "No package directory was found in sfdx-project.json.",
                NotificationType.ERROR,
            )
            return
        }
        FileEditorManager.getInstance(project).openFile(created, true)
    }
}

class CreateApexClassAction : CreateSalesforceFileAction(SfFileKind.APEX_CLASS)

class CreateApexTestClassAction : CreateSalesforceFileAction(SfFileKind.APEX_TEST_CLASS)

class CreateApexTriggerAction : CreateSalesforceFileAction(SfFileKind.APEX_TRIGGER)

class CreateLwcAction : CreateSalesforceFileAction(SfFileKind.LWC)

class CreateAuraAction : CreateSalesforceFileAction(SfFileKind.AURA)

class CreateVisualforcePageAction : CreateSalesforceFileAction(SfFileKind.VISUALFORCE_PAGE)

class CreateVisualforceComponentAction : CreateSalesforceFileAction(SfFileKind.VISUALFORCE_COMPONENT)
