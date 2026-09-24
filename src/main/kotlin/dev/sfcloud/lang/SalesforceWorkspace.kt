package dev.sfcloud.lang

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.sfcloud.core.SfdxProject

object LwcWorkspaceFiles {
    private const val MAX_DEPTH = 4

    fun folders(project: Project, name: String): List<VirtualFile> =
        SfdxProject.packageDirectories(project).flatMap { find(it, name, 0) }

    fun inFolder(project: Project, folder: String, suffix: String): List<VirtualFile> =
        folders(project, folder).flatMap { directory -> directory.children.filter { it.name.endsWith(suffix) } }

    fun labelFiles(project: Project): List<VirtualFile> =
        folders(project, "labels").flatMap { directory -> directory.children.filter { it.name.endsWith(".labels-meta.xml") } }

    fun objectDirectories(project: Project): List<VirtualFile> =
        folders(project, "objects").flatMap { directory -> directory.children.filter { it.isDirectory } }.sortedBy { it.name }

    private fun find(directory: VirtualFile, name: String, depth: Int): List<VirtualFile> {
        if (!directory.isDirectory || depth > MAX_DEPTH) return emptyList()
        val match = directory.findChild(name)?.takeIf { it.isDirectory }
        if (match != null) return listOf(match)
        return directory.children.filter { it.isDirectory && !it.name.startsWith(".") }
            .flatMap { find(it, name, depth + 1) }
    }
}

object ApexClasses {
    fun files(project: Project): List<VirtualFile> =
        FileTypeIndex.getFiles(ApexFileType, GlobalSearchScope.projectScope(project)).sortedBy { it.name }

    fun names(project: Project): List<String> {
        if (!SfdxProject.isSfdx(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val names = files(project).map { it.nameWithoutExtension }.distinct().sorted()
            CachedValueProvider.Result.create(names, PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.getInstance())
        }
    }
}
