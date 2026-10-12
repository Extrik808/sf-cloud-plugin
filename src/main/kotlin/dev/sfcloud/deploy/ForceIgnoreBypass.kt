package dev.sfcloud.deploy

import com.intellij.openapi.util.io.FileUtil
import java.io.File

class StagedDeploy(val workspace: File, val sourceDirs: List<File>, private val originals: Map<String, String>) {
    fun original(path: String): String {
        val normalized = FileUtil.toSystemIndependentName(File(path).let { if (it.isAbsolute) it else File(workspace, path) }.path)
        originals[normalized]?.let { return it }
        return originals.entries.firstOrNull { (staged, _) -> normalized.startsWith("$staged/") }
            ?.let { (staged, original) -> original + normalized.removePrefix(staged) }
            ?: path
    }
}

object ForceIgnoreBypass {
    const val SOURCE = "force-app"
    private const val NO_SOURCE = "No source-backed components present in the package"

    fun isExcluded(message: String): Boolean = message.contains(NO_SOURCE, ignoreCase = true)

    fun stage(targets: List<File>, packageDirectories: List<File>, workspace: File, sourceApiVersion: String?): StagedDeploy {
        val source = File(workspace, SOURCE)
        val version = sourceApiVersion?.let { ""","sourceApiVersion":"$it"""" }.orEmpty()
        File(workspace, "sfdx-project.json").writeText("""{"packageDirectories":[{"path":"$SOURCE","default":true}]$version}""")
        val originals = linkedMapOf<String, String>()
        val sourceDirs = targets.map { target ->
            val packageDirectory = packageDirectories.firstOrNull { FileUtil.isAncestor(it, target, false) }
            val relative = packageDirectory?.let { FileUtil.getRelativePath(it, target) } ?: target.name
            val staged = File(source, relative)
            if (target.isDirectory) {
                FileUtil.copyDir(target, staged)
            } else {
                companions(target).forEach { file ->
                    val copy = File(staged.parentFile, file.name)
                    FileUtil.copy(file, copy)
                    originals[FileUtil.toSystemIndependentName(copy.path)] = FileUtil.toSystemIndependentName(file.path)
                }
            }
            originals[FileUtil.toSystemIndependentName(staged.path)] = FileUtil.toSystemIndependentName(target.path)
            staged
        }
        return StagedDeploy(workspace, sourceDirs, originals)
    }

    private fun companions(file: File): List<File> {
        val parent = file.parentFile ?: return listOf(file)
        val partner = if (file.name.endsWith("-meta.xml")) File(parent, file.name.removeSuffix("-meta.xml")) else File(parent, "${file.name}-meta.xml")
        return listOfNotNull(file, partner.takeIf { it.isFile })
    }
}
