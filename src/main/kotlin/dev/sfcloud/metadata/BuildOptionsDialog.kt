package dev.sfcloud.metadata

import com.intellij.execution.RunManager
import com.intellij.execution.impl.EditConfigurationsDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.sfcloud.api.SfApiException
import dev.sfcloud.core.SfCliException
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.deploy.TestLevel
import dev.sfcloud.org.ConnectionComboBox
import dev.sfcloud.org.OrgService
import dev.sfcloud.tests.ApexTestRunConfiguration
import dev.sfcloud.ui.SfUi
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

enum class BuildOperation(val title: String, val okText: String, val okTooltip: String) {
    DEPLOY("Select Deployment Scope", "Deploy", "Deploys the selected metadata to Salesforce."),
    RETRIEVE("Select Retrieval Scope", "Retrieve", "Retrieves the selected metadata from Salesforce into the local source directory."),
    DELETE("Select Delete Scope", "Delete", "Deletes the selected metadata from Salesforce."),
}

enum class BuildScope(val label: String, val icon: Icon, val tooltip: String) {
    PROJECT("Project", AllIcons.Nodes.Project, "The contents of all package directories in the project"),
    CONTEXT("Context", AllIcons.General.Filter, "The contents of the contextually-selected files and directories"),
    CUSTOM("Custom", AllIcons.Actions.Selectall, "The user-defined custom selection"),
}

enum class Presence(val label: String, val description: String, val color: Color?) {
    BOTH("Local + Server", "The metadata is available in the local file system and in the organization.", null),
    LOCAL_ONLY("Local Only", "The metadata is only available in the local file system.", JBColor(0x3B8A3E, 0x6AAB73)),
    SERVER_ONLY("Server Only", "The metadata is only available in the organization.", JBColor(0x2F65CA, 0x6C9CE0));

    val attributes: SimpleTextAttributes
        get() = if (color == null) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
}

private sealed interface BuildNode {
    data class Type(val name: String, val count: Int) : BuildNode
    data class Component(val ref: ComponentRef, val presence: Presence, val server: ServerComponent?, val label: String) : BuildNode
}

class BuildOptionsDialog(
    private val project: Project,
    private val operation: BuildOperation,
    private val contextFiles: List<VirtualFile>,
) : DialogWrapper(project, true) {
    private val orgs = OrgService.getInstance(project)
    private var org: String? = null
    private val connection = ConnectionComboBox(project, disposable, null, allowDefault = true) { key ->
        org = key
        loadServer(refresh = false)
    }
    private val scopeCombo = ComboBox(BuildScope.entries.filter { it != BuildScope.CONTEXT || contextFiles.isNotEmpty() }.toTypedArray())
    private val filters = Presence.entries.associateWith { JBCheckBox(it.label, true).apply { toolTipText = filterTooltip(it) } }
    private val root = CheckedTreeNode("Project")
    private val tree = CheckboxTree(Renderer(), root, CheckboxTreeBase.CheckPolicy(true, true, false, true))
    private val status = JBLabel(" ")
    private val updateLink = ActionLink("Update now") { loadServer(refresh = true) }
    private val unmatchedRoot = ComboBox(SfdxProject.packageDirectories(project).toTypedArray())
    private val ignoreErrors = JBCheckBox("Ignore Errors").apply { toolTipText = "When checked, errors will not cause the deployment to fail and roll back." }
    private val ignoreWarnings = JBCheckBox("Ignore Warnings").apply { toolTipText = "When checked, warnings will not cause the deployment to fail and roll back." }
    private val checkOnly = JBCheckBox("Check Only").apply { toolTipText = "When checked, validate metadata without committing it to the organization." }
    private val testLevel = ComboBox(TestLevel.entries.toTypedArray())
    private val testConfiguration = ComboBox<ApexTestRunConfiguration?>()
    private val purge = JBCheckBox("Purge on Delete").apply {
        toolTipText = "When checked, deleted metadata bypasses the recycle bin and is completely removed from the organization."
    }

    private var local: List<ComponentRef> = emptyList()
    private var context: Set<ComponentRef> = emptySet()
    private var unresolvedContext: List<VirtualFile> = emptyList()
    private var revealed = false
    private var server: OrgMetadata? = null
    private val checked = LinkedHashSet<ComponentRef>()
    private var updatingChecks = false
    private var merge = false

    init {
        title = operation.title
        setOKButtonText(operation.okText)
        setOKButtonTooltip(operation.okTooltip)
        scopeCombo.selectedItem = when {
            contextFiles.isNotEmpty() -> BuildScope.CONTEXT
            operation == BuildOperation.DEPLOY -> BuildScope.PROJECT
            else -> BuildScope.CUSTOM
        }
        init()
        primeContext()
        loadLocal()
        loadServer(refresh = false)
    }

    val selected: List<ComponentRef> get() = checked.toList()

    override fun createCenterPanel(): JComponent {
        connection.toolTipText = "The connection to which metadata should be deployed or from which it should be retrieved."
        scopeCombo.toolTipText = "The metadata which should be included in the deployment or retrieval operation."
        scopeCombo.renderer = object : ColoredListCellRenderer<BuildScope>() {
            override fun customizeCellRenderer(list: JList<out BuildScope>, value: BuildScope?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                icon = value.icon
                append(value.label)
                toolTipText = value.tooltip
            }
        }
        scopeCombo.addActionListener { applyScope() }
        filters.values.forEach { box -> box.addActionListener { rebuild() } }
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "Loading metadata…"
        TreeSpeedSearch.installOn(tree)
        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (updatingChecks) return
                readChecks()
                if (scopeCombo.selectedItem != BuildScope.CUSTOM) {
                    updatingChecks = true
                    scopeCombo.selectedItem = BuildScope.CUSTOM
                    updatingChecks = false
                }
            }
        })

        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.insets = JBUI.insets(2)
        c.fill = GridBagConstraints.HORIZONTAL
        row(form, c, 0, "Connection:", connection)
        row(form, c, 1, "Contents:", scopeCombo)

        val toolbar = SfUi.group(
            SfUi.action("Refresh", "Reloads local and server metadata", AllIcons.Actions.Refresh) { loadLocal(); loadServer(refresh = true) },
            SfUi.action("Expand All", null, AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) },
            SfUi.action("Collapse All", null, AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(tree, 0) },
            SfUi.action("Expand Selected", null, AllIcons.Actions.ShowAsTree) { tree.selectionPaths?.forEach { SfUi.expandSubtree(tree, it) } },
            SfUi.action("Select All", null, AllIcons.Actions.Selectall, { scopeCombo.selectedItem == BuildScope.CUSTOM }) { setAll(true) },
            SfUi.action("Unselect All", null, AllIcons.Actions.Unselectall, { scopeCombo.selectedItem == BuildScope.CUSTOM }) { setAll(false) },
            SfUi.action("Compare with Server", "Compare with server", AllIcons.Actions.DiagramDiff, { selectedComponents().isNotEmpty() }) { compareSelected() },
            SfUi.action("Add to .forceignore", "Adds the selected metadata to .forceignore.", AllIcons.Vcs.Ignore_file, { selectedComponents().isNotEmpty() }) { ignoreSelected() },
        )
        val actionToolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance().createActionToolbar("SfCloudBuildOptions", toolbar, true)
        actionToolbar.targetComponent = tree
        val toolbarRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbarRow.add(actionToolbar.component)
        filters.values.forEach { toolbarRow.add(it) }

        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(status)
            add(updateLink.apply { toolTipText = "Retrieves and caches an up-to-date list of all metadata in the organization for the selected connection." })
        }

        val center = JPanel(BorderLayout())
        center.add(toolbarRow, BorderLayout.NORTH)
        center.add(JBScrollPane(tree), BorderLayout.CENTER)

        val bottom = SfUi.stack(statusRow, legend(), options())
        val panel = JPanel(BorderLayout(0, 4))
        panel.add(form, BorderLayout.NORTH)
        panel.add(center, BorderLayout.CENTER)
        panel.add(bottom, BorderLayout.SOUTH)
        panel.preferredSize = JBUI.size(820, 680)
        return panel
    }

    override fun createActions(): Array<Action> {
        if (operation != BuildOperation.RETRIEVE) return arrayOf(okAction, cancelAction)
        val mergeAction = object : AbstractAction("Retrieve for Merge") {
            override fun actionPerformed(e: ActionEvent) {
                merge = true
                doOKAction()
            }
        }
        mergeAction.putValue(Action.SHORT_DESCRIPTION, "Retrieves the selected metadata from Salesforce into a temporary directory for merge against the local source directory.")
        return arrayOf(okAction, mergeAction, cancelAction)
    }

    override fun doValidateAll(): List<ValidationInfo> {
        val issues = mutableListOf<ValidationInfo>()
        if (orgs.orgKey(org) == null) issues += ValidationInfo("A connection must be selected", connection)
        if (operation == BuildOperation.DEPLOY && testLevel.selectedItem == TestLevel.RUN_SPECIFIED_TESTS && testConfiguration.selectedItem == null) {
            issues += ValidationInfo("An Apex unit test run configuration must be selected when the test level is 'Run Specified Tests'", testConfiguration)
        }
        if (checked.isEmpty()) issues += ValidationInfo("No metadata selected.")
        return issues
    }

    override fun doOKAction() {
        if (doValidateAll().isNotEmpty()) {
            merge = false
            return
        }
        super.doOKAction()
        val refs = selected
        val operations = MetadataOperations(project)
        when (operation) {
            BuildOperation.RETRIEVE -> if (merge) {
                operations.retrieveForMerge(org, refs)
            } else {
                val unmatched = refs.filter { ref -> ref !in local.toSet() }
                operations.retrieve(org, refs, unmatched, unmatchedRoot.selectedItem as VirtualFile?)
            }
            BuildOperation.DEPLOY -> operations.deploy(
                org, refs, checkOnly.isSelected, ignoreWarnings.isSelected, ignoreErrors.isSelected,
                testLevel.selectedItem as TestLevel,
                (testConfiguration.selectedItem as ApexTestRunConfiguration?)?.classes.orEmpty(),
            )
            BuildOperation.DELETE -> {
                val types = server?.types.orEmpty()
                val files = LocalFiles(project, types).let { finder -> refs.flatMap { finder.find(it) } }.distinct()
                operations.delete(org, refs, purge.isSelected, checkOnly = false) {
                    if (files.isNotEmpty()) offerLocalDelete(files)
                }
            }
        }
    }

    private fun offerLocalDelete(files: List<VirtualFile>) {
        val base = SfdxProject.root(project)
        val list = files.take(25).joinToString("\n") { base?.let { root -> VfsUtil.getRelativePath(it, root) } ?: it.path }
        val more = if (files.size > 25) "\n… and ${files.size - 25} more" else ""
        val answer = Messages.showYesNoDialog(project, "The following file(s) will also be deleted:\n\n$list$more", "Delete From '${orgs.orgLabel(org)}'", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        WriteAction.run<RuntimeException> { files.filter { it.isValid }.forEach { it.delete(this) } }
    }

    private fun row(panel: JPanel, c: GridBagConstraints, y: Int, label: String, component: JComponent) {
        c.gridy = y
        c.gridx = 0
        c.weightx = 0.0
        panel.add(JBLabel(label), c)
        c.gridx = 1
        c.weightx = 1.0
        panel.add(component, c)
    }

    private fun options(): JComponent {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.insets = JBUI.insets(2)
        c.fill = GridBagConstraints.HORIZONTAL
        when (operation) {
            BuildOperation.RETRIEVE -> {
                unmatchedRoot.toolTipText = "The source root to which unmatched retrieved files should be added."
                val default = unmatchedRoot.getItemAt(0)
                unmatchedRoot.renderer = SimpleListCellRenderer.create("") { dir ->
                    val base = SfdxProject.root(project)
                    val name = base?.let { VfsUtil.getRelativePath(dir, it) } ?: dir.path
                    if (dir == default) "$name (default)" else name
                }
                row(panel, c, 0, "Source root for unmatched retrieved files:", unmatchedRoot)
            }
            BuildOperation.DEPLOY -> {
                val checks = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(ignoreErrors)
                    add(ignoreWarnings)
                    add(checkOnly)
                }
                c.gridwidth = 2
                c.gridy = 0
                panel.add(checks, c)
                c.gridwidth = 1
                testLevel.toolTipText = "The level at which unit tests should be executed during the deployment."
                testLevel.renderer = SimpleListCellRenderer.create("") { it.label }
                testConfiguration.toolTipText = "The Apex unit test run configuration for Run Specified Tests."
                testConfiguration.renderer = SimpleListCellRenderer.create("<Select>") { it?.name.orEmpty() }
                reloadTestConfigurations()
                testLevel.addActionListener { testConfiguration.isEnabled = testLevel.selectedItem == TestLevel.RUN_SPECIFIED_TESTS }
                testConfiguration.isEnabled = false
                val manage = JButton("...").apply {
                    toolTipText = "Manages Apex unit test run configurations."
                    addActionListener {
                        EditConfigurationsDialog(project).showAndGet()
                        reloadTestConfigurations()
                    }
                }
                val tests = JPanel(BorderLayout(4, 0)).apply {
                    add(testLevel, BorderLayout.WEST)
                    add(testConfiguration, BorderLayout.CENTER)
                    add(manage, BorderLayout.EAST)
                }
                row(panel, c, 1, "Test Level:", tests)
            }
            BuildOperation.DELETE -> {
                c.gridy = 0
                panel.add(purge, c)
            }
        }
        panel.border = IdeBorderFactory.createTitledBorder("Options")
        return panel
    }

    private fun reloadTestConfigurations() {
        val configurations = RunManager.getInstance(project).allConfigurationsList.filterIsInstance<ApexTestRunConfiguration>()
        testConfiguration.removeAllItems()
        testConfiguration.addItem(null)
        configurations.forEach { testConfiguration.addItem(it) }
    }

    private fun legend(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0))
        Presence.entries.forEach { presence ->
            panel.add(JBLabel(presence.label).apply {
                foreground = presence.color ?: foreground
                toolTipText = presence.description
            })
        }
        panel.border = IdeBorderFactory.createTitledBorder("Legend")
        return panel
    }

    private fun filterTooltip(presence: Presence): String = when (presence) {
        Presence.LOCAL_ONLY -> "Toggles display of metadata files which are only found in the local file system."
        Presence.BOTH -> "Toggles display of metadata files which are found in both the local file system and the organization."
        Presence.SERVER_ONLY -> "Toggles display of metadata files which are only found in the organization."
    }

    private fun primeContext() {
        if (contextFiles.isEmpty()) return
        val resolved = contextFiles.map { SfdxProject.deployTarget(it) }.distinct().associateWith { PathComponents.resolve(it) }
        context = resolved.values.filterNotNull().toSet()
        unresolvedContext = resolved.filterValues { it == null }.keys.toList()
        if (context.isEmpty()) return
        local = context.sorted()
        showContext()
    }

    private fun loadLocal() {
        object : Task.Backgroundable(project, "Finding Metadata Files", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val localMetadata = LocalMetadata(project)
                    if (unresolvedContext.isNotEmpty()) {
                        val targets = unresolvedContext.map { it.path }
                        val resolved = localMetadata.components(targets, indicator).toSet()
                        ui {
                            context = context + resolved
                            local = (local + resolved).distinct().sorted()
                            showContext()
                        }
                    }
                    val all = localMetadata.components(localMetadata.packagePaths(), indicator)
                    ui {
                        local = (all + context).distinct().sorted()
                        showContext()
                    }
                } catch (e: SfCliException) {
                    ui { tree.emptyText.text = "Cannot read local metadata: ${e.message}" }
                }
            }
        }.queue()
    }

    private fun showContext() {
        rebuild()
        applyScope()
        if (revealed || scopeCombo.selectedItem != BuildScope.CONTEXT || checked.isEmpty()) return
        revealChecked()
        revealed = true
    }

    private fun loadServer(refresh: Boolean) {
        val key = orgs.orgKey(org).orEmpty()
        val cache = OrgMetadataCache.getInstance(project)
        val cached = if (refresh) null else cache.get(key)
        if (cached != null) {
            server = cached
            status.text = "Organization metadata for '${orgs.orgLabel(org)}' as of ${SfUi.formatDate(cached.refreshedAt)}."
            rebuild()
            return
        }
        server = null
        status.text = "Listing metadata for connection '${orgs.orgLabel(org)}'…"
        updateLink.isEnabled = false
        rebuild()
        object : Task.Backgroundable(project, "Populating Contents", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val loaded = cache.refresh(key, indicator)
                    ui {
                        if (orgs.orgKey(org).orEmpty() != key) return@ui
                        server = loaded
                        status.text = "Organization metadata for '${orgs.orgLabel(org)}' as of ${SfUi.formatDate(loaded.refreshedAt)}."
                        updateLink.isEnabled = true
                        rebuild()
                    }
                } catch (e: SfApiException) {
                    ui {
                        status.text = "Cannot list organization metadata: ${e.message}"
                        updateLink.isEnabled = true
                    }
                }
            }
        }.queue()
    }

    private fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }

    private fun revealChecked() {
        TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java)
            .filter { (it.userObject as? BuildNode.Component)?.ref in checked }
            .forEach { node -> (node.parent as? CheckedTreeNode)?.let { tree.expandPath(TreePath(it.path)) } }
        TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java)
            .firstOrNull { (it.userObject as? BuildNode.Component)?.ref in checked }
            ?.let { tree.scrollPathToVisible(TreePath(it.path)) }
    }

    private fun applyScope() {
        if (updatingChecks) return
        when (scopeCombo.selectedItem as BuildScope) {
            BuildScope.PROJECT -> {
                checked.clear()
                checked += local
            }
            BuildScope.CONTEXT -> {
                checked.clear()
                checked += context
            }
            BuildScope.CUSTOM -> Unit
        }
        syncChecks()
    }

    private fun setAll(value: Boolean) {
        if (value) visibleComponents().forEach { checked += it } else checked.clear()
        syncChecks()
    }

    private fun visibleComponents(): List<ComponentRef> =
        TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java)
            .mapNotNull { (it.userObject as? BuildNode.Component)?.ref }.toList()

    private fun readChecks() {
        TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java).forEach { node ->
            val ref = (node.userObject as? BuildNode.Component)?.ref ?: return@forEach
            if (node.isChecked) checked += ref else checked -= ref
        }
    }

    private fun selectedComponents(): List<BuildNode.Component> =
        tree.selectionPaths.orEmpty().mapNotNull { ((it.lastPathComponent as? CheckedTreeNode)?.userObject as? BuildNode.Component) }

    private fun compareSelected() {
        val finder = LocalFiles(project, server?.types.orEmpty())
        val files = selectedComponents().flatMap { finder.find(it.ref) }.distinct()
        if (files.isEmpty()) {
            Messages.showInfoMessage(project, "The selected metadata has no local files to compare.", SfUi.TITLE)
            return
        }
        MetadataOperations(project).compare(org, files)
    }

    private fun ignoreSelected() {
        val base = SfdxProject.root(project) ?: return
        val finder = LocalFiles(project, server?.types.orEmpty())
        val paths = selectedComponents().flatMap { finder.find(it.ref) }.mapNotNull { VfsUtil.getRelativePath(it, base) }.distinct()
        if (paths.isEmpty()) return
        ApplicationManager.getApplication().runWriteAction {
            val ignore = base.findChild(".forceignore") ?: base.createChildData(this, ".forceignore")
            val existing = VfsUtil.loadText(ignore)
            val additions = paths.filter { it !in existing.lines() }
            if (additions.isEmpty()) return@runWriteAction
            val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
            VfsUtil.saveText(ignore, existing + separator + additions.joinToString("\n") + "\n")
        }
    }

    private fun rebuild() {
        val types = server?.types.orEmpty()
        val parents = types.flatMap { parent -> parent.childXmlNames.map { it to parent.xmlName } }.toMap()
        val serverByRef = server?.components.orEmpty().associateBy { it.ref }
        val localSet = local.toSet()
        val all = (localSet + serverByRef.keys).filter { ref ->
            val presence = presence(ref, localSet, serverByRef)
            filters.getValue(presence).isSelected
        }
        val expanded = TreeUtil.collectExpandedUserObjects(tree).map { it.toString() }.toSet()
        root.removeAllChildren()
        val topLevel = all.filter { it.type !in parents }.groupBy { it.type }
        val children = all.filter { it.type in parents }.groupBy { parents.getValue(it.type) }
        (topLevel.keys + children.keys).distinct().sortedBy { it.lowercase() }.forEach { typeName ->
            val typeRefs = topLevel[typeName].orEmpty().sorted()
            val childRefs = children[typeName].orEmpty()
            val typeNode = CheckedTreeNode(BuildNode.Type(typeName, typeRefs.size + childRefs.size))
            val componentNodes = LinkedHashMap<String, CheckedTreeNode>()
            typeRefs.forEach { ref ->
                val node = CheckedTreeNode(component(ref, localSet, serverByRef, ref.fullName))
                componentNodes[ref.fullName] = node
                typeNode.add(node)
            }
            childRefs.groupBy { it.fullName.substringBefore('.', "") }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (parentName, refs) ->
                val parentNode = componentNodes[parentName] ?: CheckedTreeNode(BuildNode.Type(parentName.ifEmpty { typeName }, refs.size)).also {
                    componentNodes[parentName] = it
                    typeNode.add(it)
                }
                refs.groupBy { it.type }.toSortedMap().forEach { (childType, typed) ->
                    val folder = CheckedTreeNode(BuildNode.Type(childType, typed.size))
                    typed.sorted().forEach { ref ->
                        folder.add(CheckedTreeNode(component(ref, localSet, serverByRef, ref.fullName.substringAfter('.'))))
                    }
                    parentNode.add(folder)
                }
            }
            root.add(typeNode)
        }
        (tree.model as DefaultTreeModel).reload()
        tree.emptyText.text = if (local.isEmpty() && server == null) "Loading metadata…" else "No metadata"
        syncChecks()
        TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java)
            .filter { it.userObject.toString() in expanded }
            .forEach { tree.expandPath(TreePath(it.path)) }
    }

    private fun presence(ref: ComponentRef, localSet: Set<ComponentRef>, serverByRef: Map<ComponentRef, ServerComponent>): Presence = when {
        ref in localSet && (ref in serverByRef || server == null) -> Presence.BOTH
        ref in localSet -> Presence.LOCAL_ONLY
        else -> Presence.SERVER_ONLY
    }

    private fun component(ref: ComponentRef, localSet: Set<ComponentRef>, serverByRef: Map<ComponentRef, ServerComponent>, label: String) =
        BuildNode.Component(ref, presence(ref, localSet, serverByRef), serverByRef[ref], label)

    private fun syncChecks() {
        updatingChecks = true
        try {
            TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java).forEach { node ->
                val component = node.userObject as? BuildNode.Component
                node.isChecked = component != null && component.ref in checked
            }
            TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java).toList().reversed().forEach { node ->
                if (node.userObject is BuildNode.Type && node.childCount > 0) {
                    node.isChecked = (0 until node.childCount).all { (node.getChildAt(it) as CheckedTreeNode).isChecked }
                }
            }
            tree.repaint()
        } finally {
            updatingChecks = false
        }
    }

    private class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val renderer = textRenderer
            when (val node = (value as? CheckedTreeNode)?.userObject) {
                is BuildNode.Type -> {
                    renderer.icon = AllIcons.Nodes.Folder
                    renderer.append(node.name)
                    renderer.append(" (${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is BuildNode.Component -> {
                    renderer.icon = if (node.presence == Presence.SERVER_ONLY) AllIcons.Nodes.Plugin else AllIcons.FileTypes.Xml
                    renderer.append(node.label, node.presence.attributes)
                    node.server?.lastModifiedBy?.let { by ->
                        val date = node.server.lastModifiedDate?.substringBefore('T').orEmpty()
                        renderer.append("  $by $date".trimEnd(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    renderer.toolTipText = "${node.ref.type}: ${node.ref.fullName} — ${node.presence.label}"
                }
                else -> renderer.append(value.toString())
            }
        }
    }
}
