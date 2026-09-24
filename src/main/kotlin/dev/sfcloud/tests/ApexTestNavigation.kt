package dev.sfcloud.tests

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.elementType
import dev.sfcloud.lang.ApexTokens

object ApexTestLocator : SMTestLocator {
    const val PROTOCOL = "apex_test"

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val className = path.substringBefore('/')
        val methodName = path.substringAfter('/', "").takeIf { it.isNotEmpty() }
        val file = ApexTestTarget.findClassFile(project, className) ?: return emptyList()
        val element: PsiElement = methodName?.let { ApexTestTarget.findMethodElement(file, it) } ?: file
        return listOf(PsiLocation.fromPsiElement(element))
    }
}

class ApexTestRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != ApexTokens.IDENTIFIER) return null
        val target = ApexTestTarget.fromElement(element) ?: return null
        val icon = if (target.methodName == null) AllIcons.RunConfigurations.TestState.Run_run else AllIcons.RunConfigurations.TestState.Run
        return withExecutorActions(icon)
    }
}

class ApexTestRunConfigurationProducer : LazyRunConfigurationProducer<ApexTestRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(ApexTestConfigurationType::class.java).configurationFactories.first()

    override fun setupConfigurationFromContext(
        configuration: ApexTestRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = targetFor(context) ?: return false
        configuration.options.classNames = target.className
        configuration.options.methodName = target.methodName.orEmpty()
        configuration.name = target.displayName
        return true
    }

    override fun isConfigurationFromContext(configuration: ApexTestRunConfiguration, context: ConfigurationContext): Boolean {
        val target = targetFor(context) ?: return false
        return configuration.classes == listOf(target.className) &&
            configuration.method.orEmpty() == target.methodName.orEmpty()
    }

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean = true

    private fun targetFor(context: ConfigurationContext): ApexTestTarget? {
        val element = context.psiLocation ?: return null
        if (element is PsiFile) return ApexTestTarget.fromFile(element)
        return ApexTestTarget.fromElement(element) ?: ApexTestTarget.enclosing(element)
    }
}
