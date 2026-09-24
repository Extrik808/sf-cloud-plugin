package dev.sfcloud.lang

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.sfcloud.ost.OfflineSymbolTable
import java.util.concurrent.ConcurrentHashMap

class SObjectField(val name: String, val type: String)

class SObjectRelationship(val name: String, val target: String)

class SObjectSchema(
    val name: String,
    val fields: List<SObjectField>,
    val parents: List<SObjectRelationship>,
    val children: List<SObjectRelationship>,
) {
    fun field(name: String): SObjectField? = fields.firstOrNull { it.name.equals(name, true) }

    fun parent(name: String): SObjectRelationship? = parents.firstOrNull { it.name.equals(name, true) }

    fun child(name: String): SObjectRelationship? = children.firstOrNull { it.name.equals(name, true) }
}

object SalesforceSchema {
    private val SCALARS = setOf(
        "id", "string", "boolean", "integer", "long", "double", "decimal", "date", "datetime", "time", "blob",
        "address", "location", "object",
    )
    private val TYPE = Regex("""<type>\s*([^<\s]+)\s*</type>""")
    private val REFERENCE_TO = Regex("""<referenceTo>\s*([^<\s]+)\s*</referenceTo>""")
    private val STANDARD_FIELDS = listOf(
        SObjectField("Id", "Id"),
        SObjectField("Name", "String"),
        SObjectField("CreatedDate", "Datetime"),
        SObjectField("CreatedById", "Id"),
        SObjectField("LastModifiedDate", "Datetime"),
        SObjectField("LastModifiedById", "Id"),
        SObjectField("SystemModstamp", "Datetime"),
        SObjectField("IsDeleted", "Boolean"),
        SObjectField("OwnerId", "Id"),
    )
    private val STANDARD_PARENTS = listOf(
        SObjectRelationship("CreatedBy", "User"),
        SObjectRelationship("LastModifiedBy", "User"),
        SObjectRelationship("Owner", "User"),
    )

    fun objectNames(project: Project): List<String> =
        (LwcWorkspaceFiles.objectDirectories(project).map { it.name } + OfflineSymbolTable.getInstance(project).sObjectNames())
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun sObject(project: Project, name: String): SObjectSchema? = cache(project).computeIfAbsent(name.lowercase()) { load(project, name) }.orNull

    fun traverse(project: Project, root: String, path: List<String>): SObjectSchema? =
        path.fold(sObject(project, root)) { current, segment -> current?.parent(segment)?.let { sObject(project, it.target) } }

    fun isScalar(type: String): Boolean = ApexResolver.baseTypeName(type).substringAfterLast('.').lowercase() in SCALARS

    private class Entry(val orNull: SObjectSchema?)

    private fun cache(project: Project): ConcurrentHashMap<String, Entry> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, Entry>(),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.getInstance(),
            )
        }

    private fun load(project: Project, name: String): Entry {
        val ost = OfflineSymbolTable.getInstance(project).file(name)?.let { file ->
            ApexStructure.of(file).firstOrNull { it.kind.isType && it.name.equals(name, true) }
        }
        val directory = LwcWorkspaceFiles.objectDirectories(project).firstOrNull { it.name.equals(name, true) }
        if (ost == null && directory == null) return Entry(null)
        val fields = LinkedHashMap<String, SObjectField>()
        val parents = LinkedHashMap<String, SObjectRelationship>()
        val children = LinkedHashMap<String, SObjectRelationship>()
        ost?.children?.filter { it.kind == ApexMemberKind.FIELD || it.kind == ApexMemberKind.PROPERTY }?.forEach { member ->
            val type = member.type
            when {
                type.startsWith("List<", true) ->
                    children.putIfAbsent(member.name.lowercase(), SObjectRelationship(member.name, ApexResolver.baseTypeName(type.substringAfter('<').substringBeforeLast('>'))))
                isScalar(type) -> fields.putIfAbsent(member.name.lowercase(), SObjectField(member.name, type))
                else -> parents.putIfAbsent(member.name.lowercase(), SObjectRelationship(member.name, type))
            }
        }
        directory?.let { local(it, fields, parents) }
        if (ost == null) {
            STANDARD_FIELDS.forEach { fields.putIfAbsent(it.name.lowercase(), it) }
            STANDARD_PARENTS.forEach { parents.putIfAbsent(it.name.lowercase(), it) }
        }
        val displayName = ost?.name ?: directory?.name ?: name
        return Entry(SObjectSchema(displayName, fields.values.toList(), parents.values.toList(), children.values.toList()))
    }

    private fun local(directory: VirtualFile, fields: MutableMap<String, SObjectField>, parents: MutableMap<String, SObjectRelationship>) {
        directory.findChild("fields")?.children.orEmpty()
            .filter { it.name.endsWith(".field-meta.xml") }
            .forEach { file ->
                val fieldName = file.name.removeSuffix(".field-meta.xml")
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
                val type = TYPE.find(text)?.groupValues?.get(1).orEmpty()
                val target = REFERENCE_TO.find(text)?.groupValues?.get(1)
                fields.putIfAbsent(fieldName.lowercase(), SObjectField(fieldName, apexType(type)))
                if (target != null) {
                    val relationship = relationshipOf(fieldName)
                    parents.putIfAbsent(relationship.lowercase(), SObjectRelationship(relationship, target))
                }
            }
    }

    fun relationshipOf(fieldName: String): String = when {
        fieldName.endsWith("__c", true) -> fieldName.dropLast(3) + "__r"
        fieldName.endsWith("Id", true) && fieldName.length > 2 -> fieldName.dropLast(2)
        else -> fieldName
    }

    private fun apexType(metadataType: String): String = when (metadataType.lowercase()) {
        "lookup", "masterdetail", "hierarchy", "externallookup", "indirectlookup" -> "Id"
        "checkbox" -> "Boolean"
        "number", "currency", "percent" -> "Decimal"
        "date" -> "Date"
        "datetime" -> "Datetime"
        "time" -> "Time"
        "location" -> "Location"
        else -> "String"
    }
}
