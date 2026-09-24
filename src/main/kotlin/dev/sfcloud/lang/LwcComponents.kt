package dev.sfcloud.lang

import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.sfcloud.core.SfdxProject

class LwcComponent(val directory: VirtualFile) {
    val name: String get() = directory.name

    val tag: String get() = "c-" + kebab(name)

    val script: VirtualFile?
        get() = directory.findChild("$name.js") ?: directory.findChild("$name.ts")

    val template: VirtualFile? get() = directory.findChild("$name.html")

    fun apiProperties(): List<String> {
        val text = script?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() } ?: return emptyList()
        return LwcScript.apiMembers(text).map { kebab(it) }
    }

    companion object {
        fun kebab(name: String): String =
            name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").replace('_', '-').lowercase()
    }
}

object LwcComponents {
    fun all(project: Project): List<LwcComponent> {
        if (!SfdxProject.isSfdx(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val directories = FileTypeIndex.getFiles(HtmlFileType.INSTANCE, GlobalSearchScope.projectScope(project))
                .mapNotNull { it.parent }
                .filter { it.parent?.name == "lwc" }
                .distinct()
                .sortedBy { it.name }
            CachedValueProvider.Result.create(directories.map { LwcComponent(it) }, PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.getInstance())
        }
    }

    fun find(project: Project, tag: String): LwcComponent? {
        val normalized = tag.lowercase()
        return all(project).firstOrNull { it.tag == normalized }
    }

    fun of(file: VirtualFile): LwcComponent? {
        val directory = file.parent ?: return null
        return if (directory.parent?.name == "lwc") LwcComponent(directory) else null
    }

    fun isTemplate(file: VirtualFile): Boolean =
        file.extension?.lowercase() == "html" && file.parent?.parent?.name == "lwc"
}

class LwcMember(val name: String, val kind: String)

object LwcScript {
    private const val API = "@api"

    fun members(text: CharSequence): List<LwcMember> {
        val body = classBody(text) ?: return emptyList()
        val found = LinkedHashMap<String, LwcMember>()
        ACCESSOR.findAll(text, body).forEach { found.putIfAbsent(it.groupValues[1], LwcMember(it.groupValues[1], "property")) }
        FIELD.findAll(text, body).forEach { found.putIfAbsent(it.groupValues[1], LwcMember(it.groupValues[1], "field")) }
        METHOD.findAll(text, body).forEach { found.putIfAbsent(it.groupValues[1], LwcMember(it.groupValues[1], "method")) }
        return found.values.filterNot { it.name in RESERVED }
    }

    fun apiMembers(text: CharSequence): List<String> {
        val body = classBody(text) ?: return emptyList()
        return members(text).filter { member ->
            val declaration = declarationOf(text, body, member.name) ?: return@filter false
            declaration.contains(API)
        }.map { it.name }
    }

    private fun declarationOf(text: CharSequence, body: Int, name: String): String? {
        val offset = LwcTemplate.memberOffsets(text, name).firstOrNull()?.takeIf { it > body } ?: return null
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val previous = text.subSequence(maxOf(0, lineStart - 120), lineStart)
        return previous.takeLastWhile { it != ';' && it != '}' && it != '{' }.toString() + text.subSequence(lineStart, offset)
    }

    private fun classBody(text: CharSequence): Int? = CLASS.find(text)?.range?.last

    private val CLASS = Regex("""\bclass\s+[\w$]+[^{]*\{""")
    private val ACCESSOR = Regex("""(?m)^\s*(?:@[\w$]+(?:\([^)]*\))?\s+)*(?:static\s+)?(?:get|set)\s+([\w$]+)\s*\(""")
    private val FIELD = Regex("""(?m)^\s*(?:@[\w$]+(?:\([^)]*\))?\s*)*(?:static\s+)?([\w$]+)\s*(?:=[^=]|;)""")
    private val METHOD = Regex("""(?m)^\s*(?:@[\w$]+(?:\([^)]*\))?\s+)*(?:static\s+)?(?:async\s+)?([\w$]+)\s*\([^)]*\)\s*\{""")
    private val RESERVED = setOf("constructor", "if", "for", "while", "switch", "catch", "return", "get", "set")
}

object LwcTemplateModel {
    val DIRECTIVES: List<String> = listOf(
        "lwc:if", "lwc:elseif", "lwc:else", "lwc:ref", "lwc:spread", "lwc:dom", "lwc:external",
        "lwc:is", "lwc:slot-bind", "lwc:slot-data", "lwc:inner-html", "lwc:render-mode", "lwc:preserve-comments",
        "for:each", "for:item", "for:index", "iterator:it", "key", "if:true", "if:false", "slot", "name",
    )

    val EVENT_HANDLERS: List<String> = listOf(
        "onclick", "ondblclick", "onchange", "oninput", "onsubmit", "onfocus", "onblur", "onkeydown", "onkeyup",
        "onkeypress", "onmouseover", "onmouseout", "onmouseenter", "onmouseleave", "onload", "onerror", "onselect",
    )

    val BASE_COMPONENTS: List<String> = listOf(
        "lightning-accordion", "lightning-accordion-section", "lightning-avatar", "lightning-badge",
        "lightning-breadcrumb", "lightning-breadcrumbs", "lightning-button", "lightning-button-group",
        "lightning-button-icon", "lightning-button-icon-stateful", "lightning-button-menu", "lightning-button-stateful",
        "lightning-card", "lightning-carousel", "lightning-carousel-image", "lightning-checkbox-group",
        "lightning-click-to-dial", "lightning-combobox", "lightning-datatable", "lightning-dual-listbox",
        "lightning-dynamic-icon", "lightning-file-upload", "lightning-formatted-address", "lightning-formatted-date-time",
        "lightning-formatted-email", "lightning-formatted-location", "lightning-formatted-name",
        "lightning-formatted-number", "lightning-formatted-phone", "lightning-formatted-rich-text",
        "lightning-formatted-text", "lightning-formatted-time", "lightning-formatted-url", "lightning-helptext",
        "lightning-icon", "lightning-input", "lightning-input-address", "lightning-input-field", "lightning-input-location",
        "lightning-input-name", "lightning-input-rich-text", "lightning-layout", "lightning-layout-item",
        "lightning-map", "lightning-menu-divider", "lightning-menu-item", "lightning-menu-subheader",
        "lightning-messages", "lightning-navigation", "lightning-output-field", "lightning-pill",
        "lightning-pill-container", "lightning-progress-bar", "lightning-progress-indicator", "lightning-progress-ring",
        "lightning-progress-step", "lightning-radio-group", "lightning-record-edit-form", "lightning-record-form",
        "lightning-record-picker", "lightning-record-view-form", "lightning-relative-date-time",
        "lightning-rich-text-toolbar-button", "lightning-rich-text-toolbar-button-group", "lightning-select",
        "lightning-slider", "lightning-spinner", "lightning-tab", "lightning-tabset", "lightning-textarea",
        "lightning-tile", "lightning-tree", "lightning-tree-grid", "lightning-vertical-navigation",
        "lightning-vertical-navigation-item", "lightning-vertical-navigation-item-badge",
        "lightning-vertical-navigation-item-icon", "lightning-vertical-navigation-overflow",
        "lightning-vertical-navigation-section",
    )

    val TEMPLATE_TAGS: List<String> = listOf("template", "slot")

    fun isComponentTag(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("c-") || lower.startsWith("lightning-") || lower.startsWith("lightningsnapin-")
    }

    fun isDirective(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("lwc:") || lower.startsWith("for:") || lower.startsWith("iterator:") ||
            lower.startsWith("if:") || lower == "key"
    }
}
