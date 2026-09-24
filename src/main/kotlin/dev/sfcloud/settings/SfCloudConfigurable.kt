package dev.sfcloud.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import dev.sfcloud.lsp.LanguageServers
import dev.sfcloud.metadata.OrgMetadataCache
import dev.sfcloud.org.ConnectionComboBox
import dev.sfcloud.org.Connections
import dev.sfcloud.org.LoginOrgAction
import dev.sfcloud.org.OrgService
import javax.swing.JComponent
import javax.swing.SwingConstants

class SfCloudConfigurable(private val project: Project) : SearchableConfigurable {
    private var disposable: Disposable? = null
    private val panels = mutableListOf<DialogPanel>()
    private var connection: ConnectionComboBox? = null
    private var selectedConnection: String? = null

    override fun getId(): String = "dev.sfcloud.settings"

    override fun getDisplayName(): String = "SF Cloud"

    override fun createComponent(): JComponent {
        val parent = Disposer.newDisposable("SF Cloud settings")
        disposable = parent
        panels.clear()
        val app = SfCloudSettings.getInstance().state
        val local = SfCloudProjectSettings.getInstance(project).state
        val combo = ConnectionComboBox(project, parent, OrgService.getInstance(project).targetOrgKey) { selectedConnection = it }
        combo.toolTipText = "The Salesforce connection used by the project."
        connection = combo
        selectedConnection = combo.selectedKey

        val tabs = JBTabbedPane(SwingConstants.LEFT)
        tabs.addTab("Connections", null, tab(panel {
            group("Project Connection") {
                row("Connection:") {
                    cell(combo).align(AlignX.FILL)
                }
                row {
                    button("Authorize OAuth Org...") { LoginOrgAction.authorize(project) }
                    button("Refresh Salesforce CLI Connections") { OrgService.getInstance(project).refresh() }
                }
            }
            group("Recent Connections") {
                lateinit var remember: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
                row {
                    remember = checkBox("Remember recent connections")
                        .bindSelected({ local.rememberRecentOrgs }, { local.rememberRecentOrgs = it })
                }
                indent {
                    row("Maximum recent connections:") {
                        intTextField(1..Connections.MAX_RECENT)
                            .bindIntText({ local.maxRecentOrgs }, { local.maxRecentOrgs = it })
                    }.enabledIf(remember.selected)
                    row {
                        link("Clear recent connections for project '${project.name}'") { local.recentOrgs.clear() }
                    }
                }
            }
        }), "Configure Salesforce connections.")
        tabs.addTab("Deployment", null, tab(panel {
            group("Behavior") {
                row {
                    checkBox("Deploy on save")
                        .bindSelected({ local.deployOnSave }, { local.deployOnSave = it })
                        .comment("Deploys the current file and any unsaved Salesforce source files on Save (Cmd+S / Ctrl+S); never runs against production orgs")
                }
            }
            group("Organization Metadata") {
                row {
                    link("Clear cached organization metadata") { OrgMetadataCache.getInstance(project).clearAll() }
                }
            }
        }), "Configure Salesforce deployment settings.")
        tabs.addTab("Salesforce DX", null, tab(panel {
            group("Executable") {
                row("Salesforce CLI (sf):") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select sf Executable"))
                        .bindText({ app.sfPath.orEmpty() }, { app.sfPath = it })
                        .align(AlignX.FILL)
                        .comment("The path to the Salesforce CLI (sf) executable. Leave empty to use the one on the execution path.")
                }
            }
        }), "Configure Salesforce DX settings.")
        tabs.addTab("Apex", null, tab(panel {
            group("Unit Tests") {
                row("Test run timeout, minutes:") {
                    intTextField(1..240).bindIntText({ local.testWaitMinutes }, { local.testWaitMinutes = it })
                }
            }
            group("Offline Symbol Table") {
                row {
                    checkBox("Update after deploying or retrieving objects and fields")
                        .bindSelected({ local.ostAutoUpdate }, { local.ostAutoUpdate = it })
                        .comment("Re-describes only the changed SObjects and the objects they look up to, from the project connection")
                }
            }
            group("Code Coverage") {
                row {
                    checkBox("Highlight code coverage after test runs")
                        .bindSelected({ local.showCoverage }, { local.showCoverage = it })
                }
            }
            group("Apex Language Server") {
                row {
                    checkBox("Enable")
                        .bindSelected({ app.apexLspEnabled }, { app.apexLspEnabled = it })
                }
                row {
                    checkBox("Install language servers automatically")
                        .bindSelected({ app.autoInstallServers }, { app.autoInstallServers = it })
                        .comment("Downloads the Apex language server and installs the LWC one with npm on first use")
                }
                row {
                    button("Download Apex Language Server") { LanguageServers.installApex(project) }
                    button("Install LWC Language Server") { LanguageServers.installLwc(project) }
                }
                row("Server jar:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withExtensionFilter("jar"))
                        .bindText({ app.apexLspJarPath.orEmpty() }, { app.apexLspJarPath = it })
                        .align(AlignX.FILL)
                        .comment("Empty uses the downloaded copy: ${LanguageServers.apexJar()}")
                }
                row("Java:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .bindText({ app.apexLspJavaPath.orEmpty() }, { app.apexLspJavaPath = it })
                        .align(AlignX.FILL)
                        .comment("Empty uses the IDE runtime")
                }
                row("Max heap, MB:") {
                    intTextField(256..16384).bindIntText({ app.apexLspMaxHeapMb }, { app.apexLspMaxHeapMb = it })
                }
                row {
                    checkBox("Report semantic errors")
                        .bindSelected({ app.apexSemanticErrors }, { app.apexSemanticErrors = it })
                }
            }
        }), "Configure Apex settings.")
        tabs.addTab("UI Frameworks", null, tab(panel {
            group("Editor") {
                row {
                    checkBox("Show bundle file tabs below the editor")
                        .bindSelected({ app.bundleEditorTabs }, { app.bundleEditorTabs = it })
                        .comment("Switches between the files of an LWC or Aura bundle and between a source file and its -meta.xml")
                }
            }
            group("LWC") {
                row {
                    checkBox("Enable the LWC language server")
                        .bindSelected({ app.lwcLspEnabled }, { app.lwcLspEnabled = it })
                }
                row("node executable:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile())
                        .bindText({ app.nodePath.orEmpty() }, { app.nodePath = it })
                        .align(AlignX.FILL)
                        .comment("Leave empty to find node on the execution path")
                }
            }
        }), "Configure Salesforce UI framework settings.")
        return tabs
    }

    private fun tab(panel: DialogPanel): DialogPanel {
        panels += panel
        disposable?.let { panel.registerValidators(it) }
        return panel
    }

    override fun isModified(): Boolean =
        panels.any { it.isModified() } || selectedConnection != OrgService.getInstance(project).targetOrgKey

    override fun apply() {
        panels.forEach { it.apply() }
        val service = OrgService.getInstance(project)
        val key = selectedConnection
        if (key != null && key != service.targetOrgKey) {
            service.find(key)?.let { service.select(it) }
        }
        LanguageServers.restart(project)
    }

    override fun reset() {
        panels.forEach { it.reset() }
        connection?.select(OrgService.getInstance(project).targetOrgKey)
        selectedConnection = OrgService.getInstance(project).targetOrgKey
    }

    override fun disposeUIResources() {
        disposable?.let { Disposer.dispose(it) }
        disposable = null
        panels.clear()
        connection = null
    }
}
