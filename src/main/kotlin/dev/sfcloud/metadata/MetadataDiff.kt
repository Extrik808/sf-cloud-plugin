package dev.sfcloud.metadata

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

data class MetadataDiffEntry(
    val relative: String,
    val local: VirtualFile?,
    val serverName: String,
    val serverText: String,
)

object MetadataDiff {
    fun open(project: Project, connection: String, tabName: String, entries: List<MetadataDiffEntry>) {
        val file = chainFile(project, connection, tabName, entries) ?: return
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }

    fun chainFile(
        project: Project,
        connection: String,
        tabName: String,
        entries: List<MetadataDiffEntry>,
    ): ChainDiffVirtualFile? {
        if (entries.isEmpty()) return null
        return ChainDiffVirtualFile(SimpleDiffRequestChain(requests(project, connection, entries)), tabName)
    }

    fun requests(project: Project, connection: String, entries: List<MetadataDiffEntry>): List<DiffRequest> =
        entries.map { request(project, connection, it) }

    private fun request(project: Project, connection: String, entry: MetadataDiffEntry): DiffRequest {
        val factory = DiffContentFactory.getInstance()
        val local = entry.local
        val server = factory.create(project, entry.serverText, local?.fileType)
        val mine = local?.let { factory.create(project, it) } ?: factory.createEmpty()
        val request = SimpleDiffRequest(
            entry.relative,
            server,
            mine,
            connection,
            if (local == null) "Not in the project" else "Local (editable)",
        )
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, local == null))
        return request
    }
}
