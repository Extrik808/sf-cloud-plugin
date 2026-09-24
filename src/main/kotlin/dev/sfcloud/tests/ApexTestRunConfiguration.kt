package dev.sfcloud.tests

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.sfcloud.lang.SfCloudIcons
import dev.sfcloud.log.LogAnalyzerWindow
import dev.sfcloud.log.LogLevels
import dev.sfcloud.log.LogLevelsPanel
import dev.sfcloud.org.ConnectionComboBox
import dev.sfcloud.ui.SfUi
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel

class ApexTestConfigurationType : ConfigurationTypeBase(
    ID,
    "Apex Unit Tests",
    "Apex unit tests run configuration",
    NotNullLazyValue.createValue { SfCloudIcons.ToolWindow },
) {
    init {
        addFactory(ApexTestConfigurationFactory(this))
    }

    companion object {
        const val ID = "SfCloudApexTest"
    }
}

class ApexTestConfigurationFactory(type: ApexTestConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = ApexTestConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        ApexTestRunConfiguration(project, this, "Apex Unit Tests")

    override fun getOptionsClass(): Class<out BaseState> = ApexTestOptions::class.java
}

class ApexTestOptions : LocatableRunConfigurationOptions() {
    var classNames by string("")
    var methodName by string("")
    var testMethods by string("")
    var allTests by property(false)
    var changedOnly by property(false)
    var targetOrg by string("")
    var logLevels by string("")
}

class ApexTestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<ApexTestOptions>(project, factory, name) {

    public override fun getOptions(): ApexTestOptions = super.getOptions() as ApexTestOptions

    val classes: List<String>
        get() = if (options.allTests) emptyList() else split(options.classNames)

    val method: String? get() = options.methodName?.trim()?.takeIf { it.isNotEmpty() }

    val methods: List<String>
        get() = if (options.allTests) emptyList() else buildList {
            method?.let { add("${classes.firstOrNull().orEmpty()}.$it") }
            addAll(split(options.testMethods))
        }

    val levels: LogLevels? get() = LogLevels.decode(options.logLevels)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = ApexTestSettingsEditor(project)

    override fun checkConfiguration() {
        if (method != null && classes.size != 1) {
            throw RuntimeConfigurationError("A test method needs exactly one test class")
        }
    }

    override fun suggestedName(): String = when {
        options.allTests || (classes.isEmpty() && methods.isEmpty()) -> if (options.changedOnly) "Changed Tests" else "All Tests"
        method != null -> "${classes.first()}.$method"
        classes.size == 1 && methods.isEmpty() -> classes.first()
        classes.isEmpty() && methods.size == 1 -> methods.first()
        else -> "${(classes + methods).first()} and ${classes.size + methods.size - 1} more"
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        ApexTestRunState(this, executor)

    companion object {
        fun split(value: String?): List<String> = value.orEmpty().split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

class ApexTestRunState(
    private val configuration: ApexTestRunConfiguration,
    private val executor: Executor,
) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val collectCoverage = this.executor.id == COVERAGE_EXECUTOR_ID
        val handler = ApexTestProcessHandler(configuration, collectCoverage)
        val project = configuration.project
        val properties = object : SMTRunnerConsoleProperties(configuration, FRAMEWORK, this.executor) {
            override fun getTestLocator(): SMTestLocator = ApexTestLocator

            override fun appendAdditionalActions(actionGroup: DefaultActionGroup, parent: JComponent, target: TestConsoleProperties) {
                super.appendAdditionalActions(actionGroup, parent, target)
                actionGroup.add(SfUi.toggle(
                    "Debug Only",
                    "When selected, only USER_DEBUG level log entries are displayed.",
                    AllIcons.General.Filter,
                    { handler.debugOnly },
                ) { handler.debugOnly = it })
                actionGroup.add(SfUi.action("Show in Log Analyzer", "Opens the current log in the Log Analyzer.", AllIcons.ToolbarDecorator.Export, { handler.logs.isNotEmpty() }) { event ->
                    val logs = handler.logs
                    if (logs.size == 1) {
                        val (name, body) = logs.entries.single()
                        LogAnalyzerWindow.openLog(project, "Apex log for $name", body, handler.org)
                        return@action
                    }
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(logs.keys.toList())
                        .setTitle("Apex Unit Test Results")
                        .setItemChosenCallback { name -> LogAnalyzerWindow.openLog(project, "Apex log for $name", logs.getValue(name), handler.org) }
                        .createPopup()
                        .showInBestPositionFor(event.dataContext)
                })
            }
        }
        properties.isIdBasedTestTree = false
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(FRAMEWORK, handler, properties)
        if (collectCoverage) {
            (console as? SMTRunnerConsoleView)?.resultsViewer?.addEventsListener(object : TestResultsViewer.EventsListener {
                override fun onTestingFinished(sender: TestResultsViewer) {
                    if (handler.coveragePublished) CoverageWindow.show(project)
                }
            })
        }
        return DefaultExecutionResult(console, handler)
    }

    companion object {
        const val FRAMEWORK = "SFCloudApex"
        const val COVERAGE_EXECUTOR_ID = "Coverage"
    }
}

class ApexCoverageProgramRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "SfCloudApexCoverage"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == ApexTestRunState.COVERAGE_EXECUTOR_ID && profile is ApexTestRunConfiguration

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val result = state.execute(environment.executor, this) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }
}

class ApexTestSettingsEditor(private val project: Project) : SettingsEditor<ApexTestRunConfiguration>() {
    private val holder = Disposer.newDisposable("SF Cloud Apex test editor").also { Disposer.register(this, it) }
    private var targetOrg: String? = null
    private val connection = ConnectionComboBox(project, holder, null, allowDefault = true) { targetOrg = it }
    private var levels: LogLevels? = null
    private val useLevels = JBCheckBox("Configure trace logging").apply {
        toolTipText = "Configure trace logging for unit test execution."
    }
    private val levelsPanel = LogLevelsPanel(LogLevels.DEFAULT) { levels = it }
    private val allTests = JBCheckBox("All Tests").apply {
        toolTipText = "When checked, the unit test run configuration will always include all Apex unit test classes and methods."
    }
    private val changedOnly = JBCheckBox("Changed Only").apply {
        toolTipText = "When checked, only changed tests and tests for changed production types are included."
    }
    private val root = CheckedTreeNode("All")
    private val tree = CheckboxTree(TestRenderer(), root, CheckboxTreeBase.CheckPolicy(true, true, true, true))
    private var selectedClasses: Set<String> = emptySet()
    private var selectedMethods: Set<String> = emptySet()

    private val component: JComponent = run {
        tree.putClientProperty("JTree.lineStyle", "Angled")
        tree.emptyText.text = "Finding Apex Unit Tests…"
        TreeSpeedSearch.installOn(tree)
        allTests.addActionListener { updateEnabled() }
        useLevels.addActionListener { updateEnabled() }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(allTests)
            add(changedOnly)
        }
        val testsPanel = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(tree).apply { preferredSize = JBUI.size(520, 260) }, BorderLayout.CENTER)
            toolTipText = "The unit test classes which should be executed."
        }
        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.insets = JBUI.insets(3)
        c.fill = GridBagConstraints.BOTH
        fun row(y: Int, label: String, content: JComponent, weight: Double = 0.0) {
            c.gridy = y
            c.gridx = 0
            c.weightx = 0.0
            c.weighty = 0.0
            c.anchor = GridBagConstraints.NORTHWEST
            form.add(JBLabel(label), c)
            c.gridx = 1
            c.weightx = 1.0
            c.weighty = weight
            form.add(content, c)
        }
        connection.toolTipText = "The Salesforce connection for which unit tests should be run."
        row(0, "Test Classes:", testsPanel, 1.0)
        row(1, "Connection:", connection)
        row(2, "Log Levels:", SfUi.stack(useLevels, levelsPanel))
        loadTests()
        form
    }

    private fun updateEnabled() {
        tree.isEnabled = !allTests.isSelected
        changedOnly.isEnabled = true
        levelsPanel.isEnabled = useLevels.isSelected
    }

    private fun loadTests() {
        ReadAction.nonBlocking<Map<String, List<String>>> { ApexTests.find(project) }
            .expireWith(holder)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.any()) { tests ->
                root.removeAllChildren()
                tests.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (className, methods) ->
                    val classNode = CheckedTreeNode(TestNode(className, null))
                    methods.forEach { classNode.add(CheckedTreeNode(TestNode(className, it))) }
                    root.add(classNode)
                }
                (tree.model as DefaultTreeModel).reload()
                tree.emptyText.text = "No test classes found."
                applyChecks()
                TreeUtil.expand(tree, 1)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun applyChecks() {
        TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java).forEach { node ->
            val test = node.userObject as? TestNode ?: return@forEach
            node.isChecked = if (test.method == null) {
                test.className in selectedClasses
            } else {
                test.className in selectedClasses || "${test.className}.${test.method}" in selectedMethods
            }
        }
        tree.repaint()
    }

    override fun resetEditorFrom(configuration: ApexTestRunConfiguration) {
        val options = configuration.options
        allTests.isSelected = options.allTests
        changedOnly.isSelected = options.changedOnly
        targetOrg = options.targetOrg?.takeIf { it.isNotBlank() }
        connection.select(targetOrg)
        val configured = configuration.levels
        useLevels.isSelected = configured != null
        levels = configured
        levelsPanel.setLevels(configured ?: LogLevels.DEFAULT)
        selectedClasses = if (configuration.method != null) emptySet() else ApexTestRunConfiguration.split(options.classNames).toSet()
        selectedMethods = configuration.methods.toSet()
        applyChecks()
        updateEnabled()
    }

    override fun applyEditorTo(configuration: ApexTestRunConfiguration) {
        val options = configuration.options
        options.allTests = allTests.isSelected
        options.changedOnly = changedOnly.isSelected
        options.targetOrg = targetOrg.orEmpty()
        options.logLevels = if (useLevels.isSelected) (levels ?: levelsPanel.levels).encode() else ""
        if (root.childCount == 0) return
        val classes = mutableListOf<String>()
        val methods = mutableListOf<String>()
        (0 until root.childCount).map { root.getChildAt(it) as CheckedTreeNode }.forEach { classNode ->
            val test = classNode.userObject as TestNode
            val children = (0 until classNode.childCount).map { classNode.getChildAt(it) as CheckedTreeNode }
            when {
                children.isNotEmpty() && children.all { it.isChecked } -> classes += test.className
                children.isEmpty() && classNode.isChecked -> classes += test.className
                else -> children.filter { it.isChecked }.forEach { methods += "${test.className}.${(it.userObject as TestNode).method}" }
            }
        }
        options.classNames = classes.joinToString(",")
        options.methodName = ""
        options.testMethods = methods.joinToString(",")
    }

    override fun createEditor(): JComponent = component

    private data class TestNode(val className: String, val method: String?)

    private class TestRenderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = (value as? CheckedTreeNode)?.userObject
            when (node) {
                is TestNode -> {
                    textRenderer.icon = if (node.method == null) AllIcons.Nodes.Class else AllIcons.Nodes.Method
                    textRenderer.append(node.method ?: node.className)
                }
                else -> {
                    textRenderer.icon = AllIcons.RunConfigurations.Junit
                    textRenderer.append(node.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
            }
        }
    }
}
