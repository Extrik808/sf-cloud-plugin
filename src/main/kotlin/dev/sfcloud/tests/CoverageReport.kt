package dev.sfcloud.tests

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import dev.sfcloud.api.SfApi
import dev.sfcloud.core.SfdxProject
import dev.sfcloud.core.int
import dev.sfcloud.core.obj
import dev.sfcloud.core.str

fun percentOf(covered: Int, total: Int): Int = Math.round(covered * 100.0 / total).toInt()

data class CoverageEntry(val name: String, val covered: Int, val total: Int)

data class CoverageFile(val name: String, val path: String) {
    val key: String get() = name.substringBeforeLast('.').lowercase()
}

enum class CoverageSource { RUN, ORG, NONE }

data class CoverageRow(
    val name: String,
    val path: String?,
    val covered: Int,
    val total: Int,
    val source: CoverageSource,
) {
    val percent: Int? get() = if (source == CoverageSource.NONE || total <= 0) null else percentOf(covered, total)
}

data class CoverageReport(
    val rows: List<CoverageRow>,
    val covered: Int,
    val total: Int,
    val files: Int,
    val orgWide: Int?,
) {
    val percent: Int? get() = if (total <= 0) null else percentOf(covered, total)
}

object CoverageReports {
    const val THRESHOLD = 75

    fun build(
        files: List<CoverageFile>,
        run: List<CoverageEntry>,
        org: List<CoverageEntry>,
        orgWide: Int?,
    ): CoverageReport {
        val fromRun = run.associateBy { it.name.lowercase() }
        val fromOrg = org.associateBy { it.name.lowercase() }
        val rows = mutableListOf<CoverageRow>()
        files.forEach { file ->
            val entry = fromRun[file.key]
            val fallback = fromOrg[file.key]
            rows += when {
                entry != null -> CoverageRow(file.name, file.path, entry.covered, entry.total, CoverageSource.RUN)
                fallback != null -> CoverageRow(file.name, file.path, fallback.covered, fallback.total, CoverageSource.ORG)
                else -> CoverageRow(file.name, file.path, 0, 0, CoverageSource.NONE)
            }
        }
        val known = files.map { it.key }.toSet()
        (fromRun.keys + fromOrg.keys).filterNot { it in known }.forEach { key ->
            val entry = fromRun[key]
            val fallback = fromOrg[key]
            val source = if (entry != null) CoverageSource.RUN else CoverageSource.ORG
            val counted = entry ?: fallback!!
            rows += CoverageRow(counted.name, null, counted.covered, counted.total, source)
        }
        val measured = rows.filter { it.percent != null }
        return CoverageReport(
            rows.sortedWith(ORDER),
            measured.sumOf { it.covered },
            measured.sumOf { it.total },
            measured.size,
            orgWide,
        )
    }

    fun scan(project: Project): List<CoverageFile> {
        val files = mutableListOf<CoverageFile>()
        SfdxProject.packageDirectories(project).forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                if (!file.isDirectory && file.extension?.lowercase() in EXTENSIONS) {
                    files += CoverageFile(file.name, file.path)
                }
                true
            }
        }
        return files.distinctBy { it.path }
    }

    private val EXTENSIONS = setOf("cls", "trigger")

    private val ORDER = compareBy<CoverageRow>({ it.percent ?: Int.MAX_VALUE }, { it.name.lowercase() })
}

object ApexCoverage {
    private const val AGGREGATE =
        "SELECT ApexClassOrTrigger.Name, NumLinesCovered, NumLinesUncovered FROM ApexCodeCoverageAggregate"
    private const val ORG_WIDE = "SELECT PercentCovered FROM ApexOrgWideCoverage"

    fun fromOrg(project: Project, org: String?, indicator: ProgressIndicator?): List<CoverageEntry> =
        SfApi.getInstance(project).query(org, AGGREGATE, tooling = true, indicator = indicator)
            .records
            .mapNotNull { record ->
                val name = record.obj("ApexClassOrTrigger")?.str("Name") ?: return@mapNotNull null
                val covered = record.int("NumLinesCovered") ?: 0
                val uncovered = record.int("NumLinesUncovered") ?: 0
                CoverageEntry(name, covered, covered + uncovered)
            }

    fun orgWide(project: Project, org: String?, indicator: ProgressIndicator?): Int? =
        SfApi.getInstance(project).query(org, ORG_WIDE, tooling = true, indicator = indicator)
            .records
            .firstOrNull()
            ?.int("PercentCovered")
}
