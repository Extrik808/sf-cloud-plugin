package dev.sfcloud.metadata

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.sfcloud.api.SfApi
import dev.sfcloud.api.SfApiException
import dev.sfcloud.core.str
import org.w3c.dom.Element
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class MetadataTypeInfo(
    val xmlName: String,
    val directoryName: String?,
    val suffix: String?,
    val inFolder: Boolean,
    val childXmlNames: List<String>,
)

data class ComponentRef(val type: String, val fullName: String) : Comparable<ComponentRef> {
    val member: String get() = "$type:$fullName"

    override fun compareTo(other: ComponentRef): Int =
        compareValuesBy(this, other, { it.type.lowercase() }, { it.fullName.lowercase() })
}

data class ServerComponent(
    val type: String,
    val fullName: String,
    val lastModifiedBy: String?,
    val lastModifiedDate: String?,
    val namespacePrefix: String?,
    val manageableState: String?,
) {
    val ref: ComponentRef get() = ComponentRef(type, fullName)
}

data class OrgMetadata(
    val org: String,
    val refreshed: Long,
    val types: List<MetadataTypeInfo>,
    val components: List<ServerComponent>,
) {
    val refreshedAt: Instant get() = Instant.ofEpochMilli(refreshed)
}

object MetadataXml {
    const val METADATA_NS = "http://soap.sforce.com/2006/04/metadata"

    fun parse(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            runCatching { setAttribute("jdk.xml.maxGeneralEntitySizeLimit", "0") }
            runCatching { setAttribute("jdk.xml.totalEntitySizeLimit", "0") }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    fun children(parent: Element, name: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.localName == name }
    }

    fun text(parent: Element, name: String): String? = children(parent, name).firstOrNull()?.textContent?.takeIf { it.isNotEmpty() }

    fun elements(document: org.w3c.dom.Document, name: String): List<Element> {
        val nodes = document.getElementsByTagNameNS("*", name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    fun packageXml(components: Collection<ComponentRef>, apiVersion: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<Package xmlns="$METADATA_NS">""").append('\n')
        components.groupBy { it.type }.toSortedMap().forEach { (type, refs) ->
            append("    <types>\n")
            refs.map { it.fullName }.distinct().sorted().forEach { append("        <members>${StringUtil.escapeXmlEntities(it)}</members>\n") }
            append("        <name>${StringUtil.escapeXmlEntities(type)}</name>\n")
            append("    </types>\n")
        }
        append("    <version>$apiVersion</version>\n")
        append("</Package>\n")
    }

    fun readPackageXml(xml: String): List<ComponentRef> {
        val document = parse(xml)
        return elements(document, "types").flatMap { types ->
            val name = text(types, "name") ?: return@flatMap emptyList()
            children(types, "members").map { ComponentRef(name, it.textContent) }
        }
    }
}

data class FolderRecord(val id: String, val name: String, val parentId: String?, val type: String)

object FolderPaths {
    private val CONTENT_TYPES = mapOf(
        "Report" to "Report",
        "Dashboard" to "Dashboard",
        "Document" to "Document",
        "Email" to "EmailTemplate",
        "EmailTemplate" to "EmailTemplate",
    )

    fun qualify(components: Collection<ServerComponent>, folders: List<FolderRecord>): List<ServerComponent> {
        val paths = paths(folders)
        if (paths.isEmpty()) return components.toList()
        val contentByFolderType = CONTENT_TYPES.values.distinct().associateBy { MetadataApi.folderTypeName(it) }
        return components.map { component ->
            val folderOwner = contentByFolderType[component.type]
            val qualified = when {
                folderOwner != null -> paths[folderOwner to component.fullName]
                component.type in contentByFolderType.values && '/' in component.fullName ->
                    paths[component.type to component.fullName.substringBefore('/')]?.let { "$it/${component.fullName.substringAfter('/')}" }
                else -> null
            }
            if (qualified == null || qualified == component.fullName) component else component.copy(fullName = qualified)
        }
    }

    private fun paths(folders: List<FolderRecord>): Map<Pair<String, String>, String> {
        val byId = folders.associateBy { it.id }
        val paths = HashMap<Pair<String, String>, String>()
        folders.forEach { folder ->
            val type = CONTENT_TYPES[folder.type] ?: return@forEach
            val chain = generateSequence(folder) { it.parentId?.let(byId::get) }.take(byId.size).map { it.name }.toList().asReversed()
            paths.putIfAbsent(type to folder.name, chain.joinToString("/"))
        }
        return paths
    }
}

class MetadataApi(private val project: Project) {
    private val api get() = SfApi.getInstance(project)

    fun describe(org: String?, indicator: ProgressIndicator?): List<MetadataTypeInfo> {
        val xml = call(org, indicator) { version -> "<met:describeMetadata><met:asOfVersion>$version</met:asOfVersion></met:describeMetadata>" }
        return parseDescribe(xml)
    }

    fun list(org: String?, queries: List<Pair<String, String?>>, indicator: ProgressIndicator?): List<ServerComponent> {
        val xml = call(org, indicator) { version ->
            "<met:listMetadata>" + queries.joinToString("") { (type, folder) ->
                "<met:queries>" + (folder?.let { "<met:folder>${StringUtil.escapeXmlEntities(it)}</met:folder>" }.orEmpty()) +
                    "<met:type>${StringUtil.escapeXmlEntities(type)}</met:type></met:queries>"
            } + "<met:asOfVersion>$version</met:asOfVersion></met:listMetadata>"
        }
        return parseList(xml)
    }

    fun loadAll(org: String?, indicator: ProgressIndicator?): OrgMetadata {
        indicator?.text2 = "Describing metadata"
        val types = describe(org, indicator)
        val topLevel = types.filterNot { it.inFolder }.map { it.xmlName }
        val children = types.flatMap { it.childXmlNames }.distinct().filter { it !in topLevel }
        val folderTypes = types.filter { it.inFolder }
        val batches = (topLevel + children).distinct().sorted().map { it to null as String? }.chunked(3)
        val pool = Executors.newFixedThreadPool(PARALLELISM)
        try {
            val components = ConcurrentHashMap.newKeySet<ServerComponent>()
            fun submitAll(batches: List<List<Pair<String, String?>>>): List<Future<*>> = batches.map { batch ->
                pool.submit {
                    indicator?.text2 = "Listing metadata (${batch.joinToString { it.first }})"
                    components += listSafely(org, batch, indicator)
                }
            }
            submitAll(batches).forEach { it.get() }
            val folderQueries = folderTypes.map { folderTypeName(it.xmlName) to null as String? }
            val folders = folderQueries.chunked(3).flatMap { listSafely(org, it, indicator) }
            components += folders
            val folderItems = folderTypes.flatMap { type ->
                folders.filter { it.type == folderTypeName(type.xmlName) }.map { type.xmlName to it.fullName as String? }
            }
            submitAll(folderItems.chunked(3)).forEach { it.get() }
            val qualified = if (folders.isEmpty()) components.toList() else FolderPaths.qualify(components, folderRecords(org, indicator))
            val visible = qualified.filter { it.manageableState != "installed" && it.fullName.isNotEmpty() }
            return OrgMetadata(org.orEmpty(), System.currentTimeMillis(), types, visible.sortedBy { it.ref })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun folderRecords(org: String?, indicator: ProgressIndicator?): List<FolderRecord> {
        indicator?.text2 = "Resolving folder hierarchy"
        return try {
            api.query(org, "SELECT Id, DeveloperName, ParentId, Type FROM Folder WHERE DeveloperName != null", indicator = indicator).records
                .mapNotNull { record ->
                    FolderRecord(
                        id = record.str("Id") ?: return@mapNotNull null,
                        name = record.str("DeveloperName") ?: return@mapNotNull null,
                        parentId = record.str("ParentId"),
                        type = record.str("Type").orEmpty(),
                    )
                }
        } catch (e: SfApiException) {
            if (e.message == "Cancelled") throw e
            emptyList()
        }
    }

    private fun listSafely(org: String?, batch: List<Pair<String, String?>>, indicator: ProgressIndicator?): List<ServerComponent> {
        indicator?.checkCanceled()
        return try {
            list(org, batch, indicator)
        } catch (e: SfApiException) {
            if (batch.size == 1 || e.message == "Cancelled") return emptyList()
            batch.flatMap { query -> runCatching { list(org, listOf(query), indicator) }.getOrDefault(emptyList()) }
        }
    }

    private fun call(org: String?, indicator: ProgressIndicator?, body: (String) -> String): String =
        api.soap(org, "/services/Soap/m/{version}", { session ->
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/" xmlns:met="${MetadataXml.METADATA_NS}">""" +
                "<env:Header><met:SessionHeader><met:sessionId>${StringUtil.escapeXmlEntities(session.accessToken)}</met:sessionId></met:SessionHeader></env:Header>" +
                "<env:Body>${body(session.apiVersion)}</env:Body></env:Envelope>"
        }, indicator)

    companion object {
        private const val PARALLELISM = 4

        fun folderTypeName(type: String): String = when (type) {
            "EmailTemplate" -> "EmailFolder"
            else -> "${type}Folder"
        }

        fun parseDescribe(xml: String): List<MetadataTypeInfo> =
            MetadataXml.elements(MetadataXml.parse(xml), "metadataObjects").map { element ->
                MetadataTypeInfo(
                    xmlName = MetadataXml.text(element, "xmlName").orEmpty(),
                    directoryName = MetadataXml.text(element, "directoryName"),
                    suffix = MetadataXml.text(element, "suffix"),
                    inFolder = MetadataXml.text(element, "inFolder").toBoolean(),
                    childXmlNames = MetadataXml.children(element, "childXmlNames").map { it.textContent },
                )
            }.filter { it.xmlName.isNotEmpty() }

        fun parseList(xml: String): List<ServerComponent> =
            MetadataXml.elements(MetadataXml.parse(xml), "result").mapNotNull { element ->
                val type = MetadataXml.text(element, "type") ?: return@mapNotNull null
                ServerComponent(
                    type = type,
                    fullName = MetadataXml.text(element, "fullName").orEmpty(),
                    lastModifiedBy = MetadataXml.text(element, "lastModifiedByName"),
                    lastModifiedDate = MetadataXml.text(element, "lastModifiedDate"),
                    namespacePrefix = MetadataXml.text(element, "namespacePrefix"),
                    manageableState = MetadataXml.text(element, "manageableState"),
                )
            }
    }
}

@Service(Service.Level.PROJECT)
class OrgMetadataCache(private val project: Project) {
    private val memory = ConcurrentHashMap<String, OrgMetadata>()
    private val gson = Gson()

    fun get(org: String): OrgMetadata? = memory[org] ?: read(org)?.also { memory[org] = it }

    fun refresh(org: String, indicator: ProgressIndicator?): OrgMetadata {
        val metadata = MetadataApi(project).loadAll(org.ifEmpty { null }, indicator)
        memory[org] = metadata
        write(org, metadata)
        return metadata
    }

    fun clearAll() {
        memory.clear()
        runCatching { directory().toFile().deleteRecursively() }
    }

    private fun directory(): Path = Path.of(PathManager.getSystemPath(), "sf-cloud", "org-metadata")

    private fun file(org: String): Path =
        directory().resolve(sanitize(org.ifEmpty { "default" }) + ".json")

    private fun read(org: String): OrgMetadata? {
        val path = file(org)
        if (!Files.isRegularFile(path)) return null
        return runCatching { gson.fromJson<OrgMetadata>(Files.readString(path), object : TypeToken<OrgMetadata>() {}.type) }.getOrNull()
    }

    private fun write(org: String, metadata: OrgMetadata) {
        runCatching {
            val path = file(org)
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(metadata))
        }
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._@-]"), "_")

    companion object {
        fun getInstance(project: Project): OrgMetadataCache = project.service()
    }
}
