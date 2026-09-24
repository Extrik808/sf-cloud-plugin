package dev.sfcloud.lang

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.sfcloud.ost.OfflineSymbolTable
import dev.sfcloud.tests.ApexTestTarget

object LwcImport {
    private const val QUOTES = "\"'`\n"
    private val IDENTIFIER = Regex("""[A-Za-z_$][\w$]*""")

    fun specifierAt(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        var start = offset
        while (start > 0 && text[start - 1] !in QUOTES) start--
        var end = offset
        while (end < text.length && text[end] !in QUOTES) end++
        if (start == 0 || end >= text.length || text[start - 1] == '\n' || text[start - 1] != text[end]) return null
        val before = text.subSequence(maxOf(0, start - 256), start - 1).toString().trimEnd()
        if (!before.endsWith("from") && !before.endsWith("import") && !before.endsWith("import(")) return null
        return text.subSequence(start, end).toString().takeIf { it.isNotBlank() }
    }

    fun defaultImportOf(text: CharSequence, name: String): String? {
        if (!IDENTIFIER.matches(name)) return null
        val n = Regex.escape(name)
        return Regex("""\bimport\s+$n\s*(?:,\s*\{[^}]*\}\s*)?from\s*(['"])([^'"]+)\1""").find(text)?.groupValues?.get(2)
    }

    fun identifierAt(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        var start = offset
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        var end = offset
        while (end < text.length && isIdentifierPart(text[end])) end++
        if (start == end || text[start].isDigit()) return null
        var previous = start - 1
        while (previous >= 0 && text[previous].isWhitespace()) previous--
        if (previous >= 0 && text[previous] == '.') return null
        return text.subSequence(start, end).toString()
    }

    fun componentName(specifier: String): String? =
        Regex("""c/([A-Za-z][\w]*)""").matchEntire(specifier)?.groupValues?.get(1)

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}

object LwcModules {
    fun components(project: Project): List<String> =
        LwcWorkspaceFiles.folders(project, "lwc")
            .flatMap { folder -> folder.children.filter { it.isDirectory && (it.findChild("${it.name}.js") != null || it.findChild("${it.name}.ts") != null) } }
            .map { it.name }
            .distinct()
            .sorted()
}

object LwcModuleTargets {
    private const val PREFIX = "@salesforce/"
    private val REFERENCE_TO = Regex("""<referenceTo>\s*([^<\s]+)\s*</referenceTo>""")

    fun componentScript(project: Project, name: String): VirtualFile? {
        val scope = GlobalSearchScope.projectScope(project)
        return listOf("$name.js", "$name.ts").asSequence()
            .flatMap { FilenameIndex.getVirtualFilesByName(it, scope).asSequence() }
            .filter { it.parent?.name == name && it.parent?.parent?.name == "lwc" }
            .sortedBy { it.path }
            .firstOrNull()
    }

    fun resolve(project: Project, specifier: String): List<PsiElement> {
        LwcImport.componentName(specifier)?.let { name ->
            return listOfNotNull(componentScript(project, name)?.let { PsiManager.getInstance(project).findFile(it) })
        }
        if (!specifier.startsWith(PREFIX)) return emptyList()
        val rest = specifier.removePrefix(PREFIX)
        val kind = rest.substringBefore('/')
        val value = rest.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return emptyList()
        return when (kind) {
            "apex", "apexContinuation" -> apex(project, value)
            "label" -> label(project, value.substringAfter('.'))
            "schema" -> schema(project, value).mapNotNull { psi(project, it) }.ifEmpty { offlineSchema(project, value) }
            "resourceUrl" -> resource(project, value)
            "contentAssetUrl" -> byName(project, "$value.asset-meta.xml")
            "messageChannel" -> byName(project, value.removeSuffix("__c") + ".messageChannel-meta.xml")
            "customPermission" -> byName(project, "$value.customPermission-meta.xml")
                .ifEmpty { byName(project, value.substringAfter("__") + ".customPermission-meta.xml") }
            else -> emptyList()
        }
    }

    private fun apex(project: Project, value: String): List<PsiElement> {
        val className = value.substringBefore('.').substringAfter("__")
        val method = value.substringAfter('.', "").takeIf { it.isNotEmpty() }
        val classFile = ApexTestTarget.findClassFile(project, className) ?: return emptyList()
        val members = ApexStructure.of(classFile).asSequence()
            .flatMap { it.flatten() }
            .filter { if (method == null) it.kind.isType && it.name.equals(className, true) else it.kind.isCallable && it.name.equals(method, true) }
            .mapNotNull { ApexResolver.declarationElement(classFile, it) }
            .toList()
        return members.ifEmpty { listOf(classFile) }
    }

    private fun label(project: Project, name: String): List<PsiElement> {
        val marker = "<fullName>$name</fullName>"
        return LwcWorkspaceFiles.labelFiles(project).mapNotNull { file ->
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
            val offset = psi.viewProvider.contents.indexOf(marker)
            if (offset < 0) null else psi.findElementAt(offset + "<fullName>".length)
        }
    }

    private fun schema(project: Project, value: String): List<VirtualFile> {
        val parts = value.split('.')
        var objects = objectDirectories(project, parts[0])
        if (objects.isEmpty()) return emptyList()
        var target = objectFiles(objects)
        for (index in 1 until parts.size) {
            val last = index == parts.size - 1
            val fieldName = if (last) parts[index] else relationshipField(parts[index])
            val fields = objects.mapNotNull { directory ->
                directory.findChild("fields")?.children?.firstOrNull { it.name.equals("$fieldName.field-meta.xml", true) }
            }
            if (fields.isEmpty()) break
            target = fields
            if (last) break
            val referenced = fields.firstNotNullOfOrNull { REFERENCE_TO.find(load(it))?.groupValues?.get(1) } ?: break
            objects = objectDirectories(project, referenced)
            if (objects.isEmpty()) break
            target = objectFiles(objects)
        }
        return target
    }

    private fun offlineSchema(project: Project, value: String): List<PsiElement> {
        val parts = value.split('.')
        val owner = SalesforceSchema.traverse(project, parts.first(), parts.drop(1).dropLast(1)) ?: return emptyList()
        val file = OfflineSymbolTable.getInstance(project).file(owner.name) ?: return emptyList()
        val type = ApexStructure.of(file).firstOrNull { it.kind.isType && it.name.equals(owner.name, true) } ?: return listOf(file)
        val member = if (parts.size == 1) type else type.children.firstOrNull { it.name.equals(parts.last(), true) } ?: type
        return listOf(ApexResolver.declarationElement(file, member) ?: file)
    }

    private fun relationshipField(name: String): String =
        if (name.endsWith("__r", true)) name.dropLast(3) + "__c" else name + "Id"

    private fun objectDirectories(project: Project, name: String): List<VirtualFile> =
        LwcWorkspaceFiles.objectDirectories(project).filter { it.name.equals(name, true) }

    private fun objectFiles(directories: List<VirtualFile>): List<VirtualFile> =
        directories.mapNotNull { it.findChild("${it.name}.object-meta.xml") }.ifEmpty { directories }

    private fun resource(project: Project, name: String): List<PsiElement> {
        val scope = GlobalSearchScope.projectScope(project)
        val metas = FilenameIndex.getVirtualFilesByName("$name.resource-meta.xml", false, scope)
        val content = metas.flatMap { meta ->
            meta.parent?.children.orEmpty().filter {
                it != meta && (it.name.equals(name, true) || it.name.startsWith("$name.", true)) && !it.name.endsWith("-meta.xml")
            }
        }
        return content.ifEmpty { metas.toList() }.mapNotNull { psi(project, it) }
    }

    private fun byName(project: Project, fileName: String): List<PsiElement> =
        FilenameIndex.getVirtualFilesByName(fileName, false, GlobalSearchScope.projectScope(project))
            .sortedBy { it.path }
            .mapNotNull { psi(project, it) }

    private fun psi(project: Project, file: VirtualFile): PsiElement? {
        val manager = PsiManager.getInstance(project)
        return if (file.isDirectory) manager.findDirectory(file) else manager.findFile(file)
    }

    private fun load(file: VirtualFile): String = runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
}

class LwcImportGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val file = source?.containingFile ?: return null
        if (file.virtualFile?.extension?.lowercase() !in SCRIPTS) return null
        val text = editor.document.charsSequence
        val specifier = LwcImport.specifierAt(text, offset)
            ?: LwcImport.identifierAt(text, offset)?.let { LwcImport.defaultImportOf(text, it) }
            ?: return null
        if (!specifier.startsWith("@salesforce/")) return null
        return LwcModuleTargets.resolve(file.project, specifier).takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    companion object {
        private val SCRIPTS = setOf("js", "ts", "mjs", "cjs")
    }
}
