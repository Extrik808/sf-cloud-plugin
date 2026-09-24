package dev.sfcloud.core

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object SfdxProject {
    private const val CONFIG = "sfdx-project.json"

    fun root(project: Project): VirtualFile? =
        project.guessProjectDir()?.takeIf { it.findChild(CONFIG) != null }

    fun isSfdx(project: Project): Boolean = root(project) != null

    fun packageDirectories(project: Project): List<VirtualFile> {
        val root = root(project) ?: return emptyList()
        val config = root.findChild(CONFIG) ?: return emptyList()
        val json = try {
            JsonParser.parseString(VfsUtilCore.loadText(config)).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        return json.arr("packageDirectories").objects()
            .mapNotNull { it.str("path") }
            .mapNotNull { root.findFileByRelativePath(it.trimEnd('/')) }
    }

    fun isSourceFile(project: Project, file: VirtualFile): Boolean {
        if (file.fileSystem.protocol != "file") return false
        return packageDirectories(project).any { VfsUtilCore.isAncestor(it, file, false) }
    }

    fun deployTarget(file: VirtualFile): VirtualFile {
        val parent = file.parent ?: return file
        val bundleType = parent.parent?.name
        if (!file.isDirectory && (bundleType == "lwc" || bundleType == "aura")) return parent
        if (file.name.endsWith("-meta.xml")) {
            val source = parent.findChild(file.name.removeSuffix("-meta.xml"))
            if (source != null && !source.isDirectory) return source
        }
        return file
    }
}
