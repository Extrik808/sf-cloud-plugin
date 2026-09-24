package dev.sfcloud.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.PlatformIcons
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.settings.SfCloudSettings

class LwcTemplateCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!SfCloudSettings.getInstance().state.lwcCompletion) return
        val file = parameters.originalFile.virtualFile ?: return
        if (!LwcComponents.isTemplate(file)) return
        val project = parameters.originalFile.project
        val caret = LwcCaret.at(parameters.editor.document.charsSequence, parameters.offset)
        val variants = when (caret) {
            is LwcCaret.TagName -> if (caret.closing) emptyList() else tags(project)
            is LwcCaret.Attribute -> attributes(project, caret.tag)
            is LwcCaret.Expression -> expressionMembers(parameters.originalFile)
            LwcCaret.Nowhere -> emptyList()
        }
        if (variants.isEmpty()) return
        result.withPrefixMatcher(caret.prefix).addAllElements(variants)
    }

    private fun tags(project: Project): List<LookupElement> {
        val own = LwcComponents.all(project).map {
            LookupElementBuilder.create(it.tag).withIcon(SfCloudIcons.ToolWindow).withTypeText(it.name)
        }
        val base = LwcTemplateModel.BASE_COMPONENTS.map { LookupElementBuilder.create(it).withTypeText("Lightning base") }
        val template = LwcTemplateModel.TEMPLATE_TAGS.map { LookupElementBuilder.create(it).withTypeText("LWC") }
        return template + own + base
    }

    private fun attributes(project: Project, tag: String): List<LookupElement> {
        val directives = LwcTemplateModel.DIRECTIVES.map { LookupElementBuilder.create(it).withTypeText("LWC directive") }
        val handlers = LwcTemplateModel.EVENT_HANDLERS.map { LookupElementBuilder.create(it).withTypeText("handler") }
        val component = LwcComponents.find(project, tag) ?: return directives + handlers
        val api = component.apiProperties().map {
            LookupElementBuilder.create(it).withIcon(PlatformIcons.PROPERTY_ICON).withTypeText("@api ${component.name}")
        }
        return api + directives + handlers
    }

    private fun expressionMembers(template: PsiFile): List<LookupElement> {
        val component = LwcComponents.of(template.virtualFile ?: return emptyList()) ?: return emptyList()
        val script = component.script ?: return emptyList()
        val text = runCatching { VfsUtilCore.loadText(script) }.getOrNull() ?: return emptyList()
        return LwcScript.members(text).map { member ->
            LookupElementBuilder.create(member.name)
                .withIcon(if (member.kind == "method") PlatformIcons.METHOD_ICON else PlatformIcons.PROPERTY_ICON)
                .withTypeText(member.kind)
        }
    }
}

class SalesforceModuleCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!SfCloudSettings.getInstance().state.lwcCompletion) return
        val file = parameters.originalFile.virtualFile ?: return
        if (file.extension?.lowercase() !in SCRIPTS) return
        val project = parameters.originalFile.project
        if (!SfdxProject.isSfdx(project)) return
        val text = parameters.editor.document.charsSequence
        specifierAt(text, parameters.offset)?.let { specifier ->
            val variants = SalesforceModules.completions(project, specifier)
            if (variants.isNotEmpty()) result.withPrefixMatcher(specifier).addAllElements(variants)
            return
        }
        val typed = componentSpecifierAt(text, parameters.offset) ?: return
        val current = LwcComponents.of(file)?.name
        val variants = LwcModules.components(project).filter { it != current }.map { component(it) }
        result.withPrefixMatcher(typed).addAllElements(variants)
    }

    private fun specifierAt(text: CharSequence, offset: Int): String? {
        var start = offset
        while (start > 0 && text[start - 1] !in QUOTES) start--
        if (start == 0) return null
        val typed = text.subSequence(start, offset).toString()
        if (!typed.startsWith("@")) return null
        return typed.takeIf { PREFIX.startsWith(it.take(PREFIX.length)) || it.startsWith(PREFIX) }
    }

    private fun componentSpecifierAt(text: CharSequence, offset: Int): String? {
        var start = offset
        while (start > 0 && text[start - 1] !in QUOTES) start--
        if (start == 0 || text[start - 1] == '\n') return null
        val typed = text.subSequence(start, offset).toString()
        if (!(typed.startsWith(COMPONENT_PREFIX) || COMPONENT_PREFIX.startsWith(typed))) return null
        val before = text.subSequence(maxOf(0, start - 256), start - 1).toString().trimEnd()
        return typed.takeIf { before.endsWith("from") || before.endsWith("import") || before.endsWith("import(") }
    }

    private fun component(name: String): LookupElement =
        LookupElementBuilder.create(COMPONENT_PREFIX + name).withIcon(SfCloudIcons.ToolWindow).withTypeText("Lightning web component")

    companion object {
        private const val PREFIX = "@salesforce/"
        private const val COMPONENT_PREFIX = "c/"
        private const val QUOTES = "\"'`\n"
        private val SCRIPTS = setOf("js", "ts", "mjs", "cjs")
    }
}

object SalesforceModules {
    private const val PREFIX = "@salesforce/"

    private val KINDS = listOf(
        "apex", "apexContinuation", "client/formFactor", "community/Id", "community/basePath",
        "contentAssetUrl", "customPermission", "i18n", "label", "messageChannel", "resourceUrl",
        "schema", "site/Id", "site/baseUrl", "user/Id", "user/isGuest", "userPermission",
    )

    private val I18N = listOf(
        "lang", "dir", "locale", "currency", "timeZone", "firstDayOfWeek", "calendarData",
        "numberFormat", "percentFormat", "currencyFormat", "defaultCalendar",
    )

    fun completions(project: Project, specifier: String): List<LookupElement> {
        if (!specifier.startsWith(PREFIX)) return KINDS.map { module(PREFIX + it) }
        val rest = specifier.removePrefix(PREFIX)
        val kind = rest.substringBefore('/')
        val typed = rest.substringAfter('/', "")
        if (!rest.contains('/')) return KINDS.map { module(PREFIX + it) }
        return when (kind) {
            "apex", "apexContinuation" -> apex(project, kind, typed)
            "label" -> labels(project).map { module("${PREFIX}label/$it") }
            "resourceUrl" -> names(project, "staticresources", ".resource-meta.xml").map { module("${PREFIX}resourceUrl/$it") }
            "contentAssetUrl" -> names(project, "contentassets", ".asset-meta.xml").map { module("${PREFIX}contentAssetUrl/$it") }
            "messageChannel" -> names(project, "messageChannels", ".messageChannel-meta.xml").map { module("${PREFIX}messageChannel/$it") }
            "schema" -> schema(project, typed)
            "i18n" -> I18N.map { module("${PREFIX}i18n/$it") }
            else -> emptyList()
        }
    }

    fun labels(project: Project): List<String> = LwcWorkspaceFiles.labelFiles(project)
        .flatMap { file -> FULL_NAME.findAll(load(file)).map { "c." + it.groupValues[1] } }
        .distinct()
        .sorted()

    private fun apex(project: Project, kind: String, typed: String): List<LookupElement> {
        val className = typed.substringBefore('.')
        if (!typed.contains('.')) {
            return ApexClasses.names(project).map { module("$PREFIX$kind/$it") }
        }
        val file = dev.sfcloud.tests.ApexTestTarget.findClassFile(project, className) ?: return emptyList()
        return ApexStructure.of(file).asSequence()
            .flatMap { it.flatten() }
            .filter { it.kind == ApexMemberKind.METHOD && it.annotations.any { annotation -> annotation.contains("AuraEnabled", true) } }
            .map { module("$PREFIX$kind/$className.${it.name}") }
            .toList()
    }

    private fun schema(project: Project, typed: String): List<LookupElement> {
        if (!typed.contains('.')) return SalesforceSchema.objectNames(project).map { module("${PREFIX}schema/$it") }
        val parts = typed.split('.')
        val path = parts.drop(1).dropLast(1)
        val root = SalesforceSchema.sObject(project, parts.first()) ?: return emptyList()
        val target = SalesforceSchema.traverse(project, root.name, path) ?: return emptyList()
        val base = "${PREFIX}schema/" + (listOf(root.name) + path).joinToString(".")
        return (target.fields.map { it.name } + target.parents.map { it.name })
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { module("$base.$it") }
    }

    private fun names(project: Project, folder: String, suffix: String): List<String> =
        LwcWorkspaceFiles.inFolder(project, folder, suffix).map { it.name.removeSuffix(suffix) }.distinct().sorted()

    private fun module(path: String): LookupElement =
        LookupElementBuilder.create(path).withIcon(SfCloudIcons.ToolWindow).withTypeText("Salesforce module")

    private fun load(file: VirtualFile): String = runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")

    private val FULL_NAME = Regex("""<fullName>([^<]+)</fullName>""")
}
