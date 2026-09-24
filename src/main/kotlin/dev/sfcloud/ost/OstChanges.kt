package dev.sfcloud.ost

import com.google.gson.JsonObject
import dev.sfcloud.core.arr
import dev.sfcloud.core.objects
import dev.sfcloud.metadata.ComponentRef
import java.util.TreeSet

class OstRequest private constructor(val systemLibrary: Boolean, val sObjects: Set<String>?, val notify: Boolean) {
    val isSelective: Boolean get() = sObjects != null

    fun merge(other: OstRequest): OstRequest = OstRequest(
        systemLibrary || other.systemLibrary,
        if (sObjects == null || other.sObjects == null) null else OstChanges.names(sObjects + other.sObjects),
        notify || other.notify,
    )

    companion object {
        fun everything(notify: Boolean = true) = OstRequest(true, null, notify)

        fun allSObjects(notify: Boolean = true) = OstRequest(false, null, notify)

        fun sObjects(names: Collection<String>, notify: Boolean = true) = OstRequest(false, OstChanges.names(names), notify)
    }
}

object OstChanges {
    private val IDENTIFIER = Regex("""[A-Za-z]\w*""")

    fun names(names: Collection<String>): Set<String> =
        names.map { it.trim() }.filter { IDENTIFIER.matches(it) }.toCollection(TreeSet(String.CASE_INSENSITIVE_ORDER))

    fun parse(text: String): Set<String> = names(text.split(Regex("""[\s,;]+""")))

    fun sObjects(components: Collection<ComponentRef>): Set<String> = names(
        components.mapNotNull { component ->
            when (component.type.lowercase()) {
                "customobject" -> component.fullName
                "customfield" -> component.fullName.substringBefore('.', "")
                else -> null
            }
        },
    )

    fun sObjectsInPaths(paths: Collection<String>): Set<String> = names(
        paths.mapNotNull { path ->
            val segments = path.replace('\\', '/').split('/')
            val index = segments.lastIndexOf("objects")
            segments.getOrNull(index + 1)?.takeIf { index >= 0 }?.substringBefore(".object-meta.xml")
        },
    )

    fun references(describe: JsonObject?): Set<String> = names(
        describe?.arr("fields").objects().flatMap { field ->
            field.arr("referenceTo")?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
        },
    )
}
