package dev.sfcloud.metadata

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.sfcloud.api.SfApi
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.core.str

enum class SfFileKind(val title: String, val folder: String) {
    APEX_CLASS("Apex Class", "classes"),
    APEX_TEST_CLASS("Apex Test Class", "classes"),
    APEX_TRIGGER("Apex Trigger", "triggers"),
    LWC("Lightning Web Component", "lwc"),
    AURA("Aura Component", "aura"),
    VISUALFORCE_PAGE("Visualforce Page", "pages"),
    VISUALFORCE_COMPONENT("Visualforce Component", "components"),
}

data class SfNewFileOptions(
    val sobject: String = "",
    val events: List<String> = listOf("before insert", "before update"),
    val exposed: Boolean = false,
    val withCss: Boolean = false,
    val withTest: Boolean = false,
    val withController: Boolean = true,
    val withHelper: Boolean = false,
    val label: String = "",
)

data class SfNewFile(val path: String, val content: String)

data class SfFileLocation(val directory: VirtualFile, val relative: String) {
    val path: String get() = listOf(directory.path, relative).filter { it.isNotEmpty() }.joinToString("/")
}

object NewFileTemplates {
    private const val META = "-meta.xml"
    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val NAMESPACE = "http://soap.sforce.com/2006/04/metadata"
    private val METADATA_FOLDERS = SfFileKind.entries.map { it.folder }.toSet() + setOf(
        "objects", "layouts", "permissionsets", "profiles", "flows", "flexipages", "tabs", "applications", "labels",
        "staticresources", "customMetadata", "email", "quickActions", "globalValueSets", "permissionsetgroups",
        "customPermissions", "remoteSiteSettings", "namedCredentials", "reports", "dashboards", "messageChannels",
    )

    fun files(kind: SfFileKind, name: String, options: SfNewFileOptions, apiVersion: String): List<SfNewFile> =
        when (kind) {
            SfFileKind.APEX_CLASS -> apexClass(name, apiVersion, "public with sharing class $name {\n}\n")
            SfFileKind.APEX_TEST_CLASS -> apexClass(name, apiVersion, testClass(name))
            SfFileKind.APEX_TRIGGER -> trigger(name, options, apiVersion)
            SfFileKind.LWC -> lwc(name, options, apiVersion)
            SfFileKind.AURA -> aura(name, options, apiVersion)
            SfFileKind.VISUALFORCE_PAGE -> visualforce(name, options, apiVersion, "page", "ApexPage", "apex:page")
            SfFileKind.VISUALFORCE_COMPONENT ->
                visualforce(name, options, apiVersion, "component", "ApexComponent", "apex:component")
        }

    fun primary(kind: SfFileKind, name: String): String = when (kind) {
        SfFileKind.APEX_CLASS, SfFileKind.APEX_TEST_CLASS -> "$name.cls"
        SfFileKind.APEX_TRIGGER -> "$name.trigger"
        SfFileKind.LWC -> "$name/$name.js"
        SfFileKind.AURA -> "$name/$name.cmp"
        SfFileKind.VISUALFORCE_PAGE -> "$name.page"
        SfFileKind.VISUALFORCE_COMPONENT -> "$name.component"
    }

    fun apiVersion(project: Project): String {
        val root = SfdxProject.root(project) ?: return SfApi.DEFAULT_API_VERSION
        val config = root.findChild("sfdx-project.json") ?: return SfApi.DEFAULT_API_VERSION
        return runCatching {
            JsonParser.parseString(VfsUtilCore.loadText(config)).asJsonObject.str("sourceApiVersion")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: SfApi.DEFAULT_API_VERSION
    }

    fun baseDirectory(project: Project, context: VirtualFile?): VirtualFile? {
        val packages = SfdxProject.packageDirectories(project)
        if (packages.isEmpty()) return null
        val owner = context?.let { file -> packages.firstOrNull { VfsUtilCore.isAncestor(it, file, false) } }
        return owner ?: packages.first()
    }

    fun location(project: Project, context: VirtualFile?, kind: SfFileKind): SfFileLocation? {
        val base = baseDirectory(project, context) ?: return null
        val directory = context?.let { if (it.isDirectory) it else it.parent }
        if (directory == null || !VfsUtilCore.isAncestor(base, directory, false)) {
            return SfFileLocation(base, listOf(relativeBase(base), kind.folder).filter { it.isNotEmpty() }.joinToString("/"))
        }
        val typed = typedFolder(directory, base)
        val bundle = kind == SfFileKind.LWC || kind == SfFileKind.AURA
        return when {
            typed?.name == kind.folder -> SfFileLocation(if (bundle) typed else directory, "")
            typed != null -> SfFileLocation(typed.parent, kind.folder)
            directory == base -> SfFileLocation(base, listOf(relativeBase(base), kind.folder).filter { it.isNotEmpty() }.joinToString("/"))
            bundle -> SfFileLocation(directory, kind.folder)
            holds(directory, kind) -> SfFileLocation(directory, "")
            isSourceContainer(directory) -> SfFileLocation(directory, kind.folder)
            else -> SfFileLocation(directory, "")
        }
    }

    private fun typedFolder(directory: VirtualFile, base: VirtualFile): VirtualFile? {
        var current: VirtualFile? = directory
        while (current != null && current != base) {
            if (current.name in METADATA_FOLDERS) return current
            current = current.parent
        }
        return null
    }

    private fun holds(directory: VirtualFile, kind: SfFileKind): Boolean {
        val extension = primary(kind, "").substringAfterLast('.')
        return directory.children.any { !it.isDirectory && it.extension == extension }
    }

    private fun isSourceContainer(directory: VirtualFile): Boolean =
        directory.name == "default" || directory.name == "main" || directory.children.any { it.isDirectory && it.name in METADATA_FOLDERS }

    fun relativeBase(base: VirtualFile): String =
        if (base.findFileByRelativePath("main/default") != null || base.children.isEmpty()) "main/default" else ""

    fun className(name: String): String = name.replaceFirstChar { it.uppercaseChar() }

    fun elementName(name: String): String =
        "c-" + name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

    private fun apexClass(name: String, apiVersion: String, body: String): List<SfNewFile> = listOf(
        SfNewFile("$name.cls", body),
        SfNewFile("$name.cls$META", metaXml("ApexClass", apiVersion, "    <status>Active</status>")),
    )

    private fun testClass(name: String): String =
        "@IsTest\nprivate class $name {\n    @IsTest\n    static void itWorks() {\n    }\n}\n"

    private fun trigger(name: String, options: SfNewFileOptions, apiVersion: String): List<SfNewFile> {
        val sobject = options.sobject.ifBlank { "SObject" }
        val events = options.events.ifEmpty { listOf("before insert") }.joinToString(", ")
        return listOf(
            SfNewFile("$name.trigger", "trigger $name on $sobject ($events) {\n}\n"),
            SfNewFile("$name.trigger$META", metaXml("ApexTrigger", apiVersion, "    <status>Active</status>")),
        )
    }

    private fun lwc(name: String, options: SfNewFileOptions, apiVersion: String): List<SfNewFile> {
        val files = mutableListOf(
            SfNewFile(
                "$name/$name.js",
                "import { LightningElement } from 'lwc';\n\n" +
                    "export default class ${className(name)} extends LightningElement {}\n",
            ),
            SfNewFile("$name/$name.html", "<template>\n</template>\n"),
            SfNewFile("$name/$name.js$META", lwcMeta(apiVersion, options.exposed)),
        )
        if (options.withCss) files += SfNewFile("$name/$name.css", ":host {\n}\n")
        if (options.withTest) files += SfNewFile("$name/__tests__/$name.test.js", jestTest(name))
        return files
    }

    private fun lwcMeta(apiVersion: String, exposed: Boolean): String {
        val targets = if (exposed) {
            "\n    <targets>\n" +
                "        <target>lightning__AppPage</target>\n" +
                "        <target>lightning__RecordPage</target>\n" +
                "        <target>lightning__HomePage</target>\n" +
                "    </targets>"
        } else {
            ""
        }
        return metaXml("LightningComponentBundle", apiVersion, "    <isExposed>$exposed</isExposed>$targets")
    }

    private fun jestTest(name: String): String {
        val element = elementName(name)
        return "import { createElement } from 'lwc';\n" +
            "import ${className(name)} from 'c/$name';\n\n" +
            "describe('$element', () => {\n" +
            "    afterEach(() => {\n" +
            "        while (document.body.firstChild) {\n" +
            "            document.body.removeChild(document.body.firstChild);\n" +
            "        }\n" +
            "    });\n\n" +
            "    it('renders', () => {\n" +
            "        const element = createElement('$element', { is: ${className(name)} });\n" +
            "        document.body.appendChild(element);\n" +
            "        expect(element.shadowRoot).not.toBeNull();\n" +
            "    });\n" +
            "});\n"
    }

    private fun aura(name: String, options: SfNewFileOptions, apiVersion: String): List<SfNewFile> {
        val files = mutableListOf(
            SfNewFile("$name/$name.cmp", "<aura:component>\n</aura:component>\n"),
            SfNewFile(
                "$name/$name.cmp$META",
                metaXml("AuraDefinitionBundle", apiVersion, "    <description>$name</description>"),
            ),
        )
        if (options.withController) {
            files += SfNewFile(
                "$name/${name}Controller.js",
                "({\n    doInit: function (component, event, helper) {\n    },\n})\n",
            )
        }
        if (options.withHelper) files += SfNewFile("$name/${name}Helper.js", "({\n})\n")
        if (options.withCss) files += SfNewFile("$name/$name.css", ".THIS {\n}\n")
        return files
    }

    private fun visualforce(
        name: String,
        options: SfNewFileOptions,
        apiVersion: String,
        extension: String,
        type: String,
        tag: String,
    ): List<SfNewFile> {
        val label = options.label.ifBlank { name }
        return listOf(
            SfNewFile("$name.$extension", "<$tag>\n</$tag>\n"),
            SfNewFile("$name.$extension$META", metaXml(type, apiVersion, "    <label>$label</label>")),
        )
    }

    private fun metaXml(type: String, apiVersion: String, body: String): String =
        "$HEADER\n<$type xmlns=\"$NAMESPACE\">\n    <apiVersion>$apiVersion</apiVersion>\n$body\n</$type>\n"
}
