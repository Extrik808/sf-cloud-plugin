package dev.sfcloud.ost

import com.google.gson.JsonObject
import dev.sfcloud.core.arr
import dev.sfcloud.core.bool
import dev.sfcloud.core.obj
import dev.sfcloud.core.objects
import dev.sfcloud.core.str

class OstStubSet(val files: Map<String, String>, val types: List<String>, val sObjects: List<String>)

object OstStubs {
    private const val SYSTEM = "System"

    private class StubType(var name: String) {
        var isEnum = false
        var supertype: String? = null
        val constructors = LinkedHashSet<String>()
        val methods = LinkedHashSet<String>()
        val fields = LinkedHashMap<String, String>()
        val constants = mutableListOf<String>()
        val nested = LinkedHashMap<String, StubType>()
    }

    fun render(completions: JsonObject?, sObjects: List<JsonObject>): OstStubSet {
        val types = LinkedHashMap<String, StubType>()
        val systemNames = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        completions?.obj("publicDeclarations")?.entrySet()?.forEach { (namespace, value) ->
            val declarations = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (namespace.equals(SYSTEM, true)) {
                declarations.entrySet().forEach { (name, declaration) ->
                    if (!declaration.isJsonObject) return@forEach
                    val type = top(types, canonical(name))
                    add(type, declaration.asJsonObject)
                    systemNames += type.name
                }
            } else {
                val container = top(types, namespace)
                systemNames += container.name
                declarations.entrySet().forEach { (name, declaration) ->
                    if (!declaration.isJsonObject) return@forEach
                    add(container.nested.getOrPut(name.lowercase()) { StubType(name) }, declaration.asJsonObject)
                }
                nest(container, namespace, declarations)
            }
        }
        synthetic(types)
        systemNames += listOf("Object", "Trigger")
        val objectNames = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        sObjects.forEach { describe ->
            val name = describe.str("name")?.takeIf { IDENTIFIER.matches(it) } ?: return@forEach
            val existing = types[name.lowercase()]
            val type = existing ?: top(types, name).also { it.supertype = "SObject" }
            sObject(type, describe)
            objectNames += name
        }
        val files = types.values.associate { it.name to buildString { write(it, "", this) } }
        return OstStubSet(files, systemNames.filter { it !in objectNames }, objectNames.toList())
    }

    fun apexType(field: JsonObject): String {
        val soap = field.str("soapType")?.substringAfter(':')?.lowercase()
        return when (soap) {
            "id" -> "Id"
            "string" -> "String"
            "boolean" -> "Boolean"
            "int" -> "Integer"
            "long" -> "Long"
            "double" -> "Decimal"
            "date" -> "Date"
            "datetime" -> "Datetime"
            "time" -> "Time"
            "base64binary" -> "Blob"
            "address" -> "Address"
            "location" -> "Location"
            "json" -> "String"
            else -> "Object"
        }
    }

    private fun top(types: MutableMap<String, StubType>, name: String): StubType =
        types.getOrPut(name.lowercase()) { StubType(name) }

    private fun canonical(name: String): String = if (name.equals("list", true)) "List" else name

    private fun add(type: StubType, declaration: JsonObject) {
        declaration.arr("constructors").objects().filter { hasApexTypes(it) }.forEach {
            type.constructors += "global ${type.name}(${parameters(it)}) {}"
        }
        declaration.arr("methods").objects().filter { hasApexTypes(it) }.forEach { method ->
            val name = method.str("name")?.takeIf { isDeclarable(it) } ?: return@forEach
            val returnType = method.str("returnType")?.takeIf { it.isNotBlank() } ?: "void"
            val static = if (method.bool("isStatic") == true) "static " else ""
            type.methods += "global $static$returnType $name(${parameters(method)}) {}"
        }
        val properties = declaration.arr("properties").objects().mapNotNull { it.str("name") }.filter { isDeclarable(it) }
        if (isEnum(type.name, declaration) && properties.isNotEmpty()) {
            type.isEnum = true
            properties.forEach { if (it !in type.constants) type.constants += it }
        } else {
            properties.forEach { type.fields.putIfAbsent(it.lowercase(), "global Object $it;") }
        }
    }

    private fun isEnum(name: String, declaration: JsonObject): Boolean =
        declaration.arr("constructors").objects().isEmpty() &&
            declaration.arr("methods").objects().any { method ->
                method.str("name") == "values" && method.bool("isStatic") == true &&
                    method.str("returnType").orEmpty().removePrefix("List<").removeSuffix(">").substringAfterLast('.').equals(name, true)
            }

    private fun hasApexTypes(method: JsonObject): Boolean =
        (listOfNotNull(method.str("returnType")) + method.arr("parameters").objects().mapNotNull { it.str("type") })
            .all { TYPE.matches(it) }

    private fun parameters(method: JsonObject): String =
        method.arr("parameters").objects().mapIndexed { index, parameter ->
            val type = parameter.str("type")?.takeIf { it.isNotBlank() } ?: "Object"
            val name = parameter.str("name")?.takeIf { IDENTIFIER.matches(it) } ?: "arg$index"
            "$type $name"
        }.joinToString(", ")

    private fun nest(container: StubType, namespace: String, declarations: JsonObject) {
        val pattern = Regex("""\b${Regex.escape(namespace)}\.(\w+)\.(\w+)\b""", RegexOption.IGNORE_CASE)
        val parents = LinkedHashMap<String, String>()
        pattern.findAll(declarations.toString()).forEach { match ->
            val parent = match.groupValues[1].lowercase()
            val child = match.groupValues[2].lowercase()
            if (parent != child && parent in container.nested && child in container.nested) parents.putIfAbsent(child, parent)
        }
        parents.filter { (_, parent) -> parent !in parents }.forEach { (child, parent) ->
            val type = container.nested.remove(child) ?: return@forEach
            container.nested[parent]?.nested?.putIfAbsent(child, type)
        }
    }

    private fun synthetic(types: MutableMap<String, StubType>) {
        top(types, "Object").apply {
            methods += "global Boolean equals(Object obj) {}"
            methods += "global Integer hashCode() {}"
            methods += "global String toString() {}"
        }
        top(types, "Trigger").apply {
            listOf("new", "old").forEach { fields.putIfAbsent(it, "global static List<SObject> $it;") }
            listOf("newMap", "oldMap").forEach { fields.putIfAbsent(it.lowercase(), "global static Map<Id, SObject> $it;") }
            listOf("isExecuting", "isInsert", "isUpdate", "isDelete", "isUndelete", "isBefore", "isAfter").forEach {
                fields.putIfAbsent(it.lowercase(), "global static Boolean $it;")
            }
            fields.putIfAbsent("size", "global static Integer size;")
            fields.putIfAbsent("operationtype", "global static System.TriggerOperation operationType;")
        }
    }

    private fun sObject(type: StubType, describe: JsonObject) {
        describe.arr("fields").objects().forEach { field ->
            val name = field.str("name")?.takeIf { IDENTIFIER.matches(it) && isDeclarable(it) } ?: return@forEach
            type.fields.putIfAbsent(name.lowercase(), "global ${apexType(field)} $name;")
            val relationship = field.str("relationshipName")?.takeIf { IDENTIFIER.matches(it) && isDeclarable(it) } ?: return@forEach
            val target = field.arr("referenceTo")?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }?.singleOrNull() ?: "SObject"
            type.fields.putIfAbsent(relationship.lowercase(), "global $target $relationship;")
        }
        describe.arr("childRelationships").objects().forEach { child ->
            val name = child.str("relationshipName")?.takeIf { IDENTIFIER.matches(it) && isDeclarable(it) } ?: return@forEach
            val target = child.str("childSObject")?.takeIf { IDENTIFIER.matches(it) } ?: return@forEach
            type.fields.putIfAbsent(name.lowercase(), "global List<$target> $name;")
        }
    }

    private fun write(type: StubType, indent: String, out: StringBuilder) {
        if (type.isEnum) {
            out.append(indent).append("global enum ").append(type.name).append(" {\n")
            out.append(indent).append("    ").append(type.constants.joinToString(",\n$indent    ")).append('\n')
            out.append(indent).append("}\n")
            return
        }
        out.append(indent).append("global class ").append(type.name)
        type.supertype?.let { out.append(" extends ").append(it) }
        out.append(" {\n")
        val inner = "$indent    "
        (type.constructors + type.methods + type.fields.values).forEach { out.append(inner).append(it).append('\n') }
        type.nested.values.forEach { write(it, inner, out) }
        out.append(indent).append("}\n")
    }

    private fun isDeclarable(name: String): Boolean = IDENTIFIER.matches(name) && name.lowercase() !in RESERVED

    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

    private val TYPE = Regex("""[\w.<>,\[\] ]*""")

    private val RESERVED = setOf(
        "abstract", "break", "catch", "class", "continue", "do", "else", "enum", "extends", "false", "final",
        "finally", "for", "global", "if", "implements", "instanceof", "interface", "null", "override", "private",
        "protected", "public", "return", "static", "super", "switch", "testmethod", "this", "throw", "transient",
        "trigger", "true", "try", "virtual", "void", "webservice", "when", "while", "with", "without",
    )
}
