package dev.sfcloud.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import java.io.File

object ProjectScratch {
    const val DIRECTORY = ".sf-cloud"
    const val RETRIEVE_DIRECTORY = "sf-cloud-retrieve"

    fun directory(project: Project, prefix: String): File {
        val root = SfdxProject.root(project)?.path ?: return FileUtil.createTempDirectory(prefix, null, true)
        val parent = File(root, DIRECTORY)
        return if (parent.isDirectory || parent.mkdirs()) {
            FileUtil.createTempDirectory(parent, prefix, null, true)
        } else {
            FileUtil.createTempDirectory(prefix, null, true)
        }
    }

    fun retrieveDirectory(project: Project, prefix: String): File? {
        val root = SfdxProject.root(project)?.path ?: return null
        val parent = File(root, RETRIEVE_DIRECTORY)
        if (!parent.isDirectory && !parent.mkdirs()) return null
        return runCatching { FileUtil.createTempDirectory(parent, prefix, null, true) }.getOrNull()
    }

    fun delete(directory: File) {
        FileUtil.delete(directory)
        directory.parentFile?.takeIf { it.name == DIRECTORY || it.name == RETRIEVE_DIRECTORY }?.delete()
    }
}
