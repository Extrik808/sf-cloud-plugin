package dev.sfcloud.metadata

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.sfcloud.core.SfdxProject

class LocalFiles(private val project: Project, private val types: List<MetadataTypeInfo>) {
    private val byName = types.associateBy { it.xmlName }
    private val parents = types.flatMap { parent -> parent.childXmlNames.map { it to parent } }.toMap()

    fun find(ref: ComponentRef): List<VirtualFile> = runReadAction {
        SfdxProject.packageDirectories(project).flatMap { root -> find(root, ref) }
    }

    private fun find(root: VirtualFile, ref: ComponentRef): List<VirtualFile> {
        val parent = parents[ref.type]
        val directories = if (parent != null) {
            val parentDir = parent.directoryName ?: return emptyList()
            val childDir = CHILD_DIRECTORIES[ref.type] ?: return emptyList()
            val objectName = ref.fullName.substringBefore('.', "")
            if (objectName.isEmpty()) return emptyList()
            listOf("$parentDir/$objectName/$childDir" to ref.fullName.substringAfter('.'))
        } else {
            val info = byName[ref.type] ?: return emptyList()
            val dir = info.directoryName ?: return emptyList()
            val name = ref.fullName
            listOf(dir + (if (name.contains('/')) "/" + name.substringBeforeLast('/') else "") to name.substringAfterLast('/'))
        }
        val result = mutableListOf<VirtualFile>()
        directories.forEach { (relativeDir, name) ->
            val candidates = listOfNotNull(root.findFileByRelativePath(relativeDir), root.findFileByRelativePath("main/default/$relativeDir"))
            candidates.forEach { dir ->
                dir.children.filter { child -> child.name == name || child.name.startsWith("$name.") }.forEach { child ->
                    if (child.isDirectory) {
                        VfsUtil.iterateChildrenRecursively(child, null) { file ->
                            if (!file.isDirectory) result += file
                            true
                        }
                    } else {
                        result += child
                    }
                }
            }
        }
        return result.distinct()
    }

    companion object {
        val CHILD_DIRECTORIES = mapOf(
            "CustomField" to "fields",
            "ListView" to "listViews",
            "RecordType" to "recordTypes",
            "ValidationRule" to "validationRules",
            "WebLink" to "webLinks",
            "CompactLayout" to "compactLayouts",
            "BusinessProcess" to "businessProcesses",
            "FieldSet" to "fieldSets",
            "SharingReason" to "sharingReasons",
            "Index" to "indexes",
        )
    }
}
