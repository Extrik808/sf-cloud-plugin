package dev.sfcloud.ost

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import dev.sfcloud.org.OrgService
import dev.sfcloud.ui.SfUi
import javax.swing.JComponent

class GenerateOfflineSymbolTableDialog(private val project: Project, preselected: Set<String>) : DialogWrapper(project) {
    enum class Scope { EVERYTHING, ALL_SOBJECTS, SELECTED_SOBJECTS }

    private val table = OfflineSymbolTable.getInstance(project)

    private var scope = when {
        !table.isAvailable -> Scope.EVERYTHING
        preselected.isNotEmpty() -> Scope.SELECTED_SOBJECTS
        else -> Scope.EVERYTHING
    }

    private val names = TextFieldWithAutoCompletion.create(project, table.sObjectNames(), false, preselected.joinToString(" "))

    private lateinit var selectedButton: JBRadioButton

    init {
        title = "Offline Symbol Table"
        setOKButtonText(if (table.generating) "Queue" else "Generate")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Connection:") { label(OrgService.getInstance(project).targetOrgKey ?: "<none>") }
        row("Current table:") { label(state()) }
        if (table.generating) {
            row { comment("A generation is running; this request will run after it finishes.") }
        }
        buttonsGroup("Generate") {
            row {
                radioButton("Everything: Apex system library and all SObjects", Scope.EVERYTHING)
            }
            row {
                radioButton("All SObjects, keeping the cached Apex system library", Scope.ALL_SOBJECTS)
                    .enabled(table.isAvailable)
            }
            row {
                selectedButton = radioButton("Selected SObjects:", Scope.SELECTED_SOBJECTS)
                    .enabled(table.isAvailable)
                    .component
            }
            indent {
                row {
                    cell(names)
                        .align(AlignX.FILL)
                        .comment("API names separated by spaces or commas, e.g. Account Invoice__c. Objects they look up to are refreshed too.")
                        .enabledIf(selectedButton.selected)
                }
            }
        }.bind(::scope)
    }

    override fun doValidate(): ValidationInfo? {
        if (selectedButton.isSelected && OstChanges.parse(names.text).isEmpty()) {
            return ValidationInfo("Enter at least one SObject API name.", names)
        }
        return null
    }

    fun request(): OstRequest = when (scope) {
        Scope.EVERYTHING -> OstRequest.everything()
        Scope.ALL_SOBJECTS -> OstRequest.allSObjects()
        Scope.SELECTED_SOBJECTS -> OstRequest.sObjects(OstChanges.parse(names.text))
    }

    private fun state(): String {
        val info = table.info() ?: return "Not generated yet"
        return "${info.org} · ${SfUi.formatDate(info.generated)} · ${info.types} Apex types, ${info.sObjects} SObjects"
    }
}
