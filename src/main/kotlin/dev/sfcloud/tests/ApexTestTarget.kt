package dev.sfcloud.tests

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import dev.sfcloud.lang.ApexFile
import dev.sfcloud.lang.ApexTokens

data class ApexTestTarget(val className: String, val methodName: String?) {
    val displayName: String get() = if (methodName == null) className else "$className.$methodName"

    companion object {
        private val TEST_MARKER = Regex("@istest\\b|\\btestmethod\\b", RegexOption.IGNORE_CASE)

        fun isTestFile(file: PsiFile): Boolean =
            file is ApexFile && file.virtualFile?.extension.equals("cls", true) && TEST_MARKER.containsMatchIn(file.viewProvider.contents)

        fun fromFile(file: PsiFile?): ApexTestTarget? {
            if (file == null || !isTestFile(file)) return null
            return ApexTestTarget(file.virtualFile.nameWithoutExtension, null)
        }

        fun fromElement(element: PsiElement?): ApexTestTarget? {
            if (element == null) return null
            val file = element.containingFile ?: return null
            if (!isTestFile(file)) return null
            val className = file.virtualFile.nameWithoutExtension
            val identifier = if (element.elementType == ApexTokens.IDENTIFIER) element else return null
            val previous = previousCode(identifier)
            if (previous?.elementType == ApexTokens.KEYWORD && previous.text.equals("class", true)) {
                return if (identifier.text.equals(className, true)) ApexTestTarget(className, null) else null
            }
            if (nextCode(identifier)?.elementType != ApexTokens.LPAREN) return null
            return if (isTestMethodDeclaration(identifier)) ApexTestTarget(className, identifier.text) else null
        }

        fun enclosing(element: PsiElement?): ApexTestTarget? {
            val file = element?.containingFile ?: return null
            if (!isTestFile(file)) return null
            var leaf: PsiElement? = if (element is PsiFile) null else PsiTreeUtil.getDeepestFirst(element)
            while (leaf != null) {
                fromElement(leaf)?.let { if (it.methodName != null) return it }
                leaf = PsiTreeUtil.prevLeaf(leaf)
            }
            return fromFile(file)
        }

        private fun isTestMethodDeclaration(identifier: PsiElement): Boolean {
            var parenDepth = 0
            var hasMarker = false
            var leaf = previousCode(identifier)
            while (leaf != null) {
                val type = leaf.elementType
                val text = leaf.text
                when {
                    type == ApexTokens.RPAREN -> parenDepth++
                    type == ApexTokens.LPAREN -> parenDepth--
                    parenDepth > 0 -> Unit
                    type == ApexTokens.SEMICOLON || type == ApexTokens.LBRACE || type == ApexTokens.RBRACE -> return hasMarker
                    type == ApexTokens.ANNOTATION && text.equals("@istest", true) -> hasMarker = true
                    type == ApexTokens.KEYWORD && text.equals("testmethod", true) -> hasMarker = true
                    type == ApexTokens.KEYWORD && (text.equals("new", true) || text.equals("return", true) || text.equals("class", true)) -> return false
                    type == ApexTokens.DOT || type == ApexTokens.STRING -> return false
                    type == ApexTokens.OPERATOR && text != "<" && text != ">" && text != ">>" -> return false
                }
                leaf = previousCode(leaf)
            }
            return hasMarker
        }

        private fun previousCode(element: PsiElement): PsiElement? {
            var leaf = PsiTreeUtil.prevLeaf(element)
            while (leaf is PsiWhiteSpace || leaf is PsiComment) leaf = PsiTreeUtil.prevLeaf(leaf)
            return leaf
        }

        private fun nextCode(element: PsiElement): PsiElement? {
            var leaf = PsiTreeUtil.nextLeaf(element)
            while (leaf is PsiWhiteSpace || leaf is PsiComment) leaf = PsiTreeUtil.nextLeaf(leaf)
            return leaf
        }

        fun findClassFile(project: Project, className: String): PsiFile? {
            val scope = GlobalSearchScope.projectScope(project)
            val manager = PsiManager.getInstance(project)
            return listOf("cls", "trigger").asSequence()
                .flatMap { FilenameIndex.getVirtualFilesByName("$className.$it", false, scope).asSequence() }
                .mapNotNull { manager.findFile(it) }
                .firstOrNull()
        }

        fun findMethodElement(file: PsiFile, methodName: String): PsiElement? {
            var leaf: PsiElement? = PsiTreeUtil.getDeepestFirst(file)
            while (leaf != null) {
                if (leaf.elementType == ApexTokens.IDENTIFIER && StringUtil.equalsIgnoreCase(leaf.text, methodName) &&
                    nextCode(leaf)?.elementType == ApexTokens.LPAREN && isTestMethodDeclaration(leaf)
                ) {
                    return leaf
                }
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
            return null
        }
    }
}

object ApexTests {
    fun find(project: Project): Map<String, List<String>> {
        val scope = GlobalSearchScope.projectScope(project)
        val manager = PsiManager.getInstance(project)
        return FilenameIndex.getAllFilesByExt(project, "cls", scope)
            .asSequence()
            .mapNotNull { manager.findFile(it) }
            .filter { ApexTestTarget.isTestFile(it) }
            .associate { file ->
                val methods = dev.sfcloud.lang.ApexStructure.of(file).asSequence()
                    .flatMap { it.flatten() }
                    .filter { it.kind == dev.sfcloud.lang.ApexMemberKind.METHOD && it.isTest }
                    .map { it.name }
                    .toList()
                file.virtualFile.nameWithoutExtension to methods
            }
    }

    fun changed(project: Project): List<String> {
        val changed = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project).affectedFiles
            .filter { it.extension.equals("cls", true) || it.extension.equals("trigger", true) }
        if (changed.isEmpty()) return emptyList()
        val tests = find(project).keys
        val changedNames = changed.map { it.nameWithoutExtension }
        val direct = changedNames.filter { it in tests }
        val production = changedNames.filterNot { it in tests }
        if (production.isEmpty()) return direct
        val pattern = Regex("\\b(" + production.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)
        val dependent = tests.filter { name ->
            val file = findClassFile(project, name) ?: return@filter false
            pattern.containsMatchIn(file.viewProvider.contents)
        }
        return (direct + dependent).distinct().sorted()
    }

    private fun findClassFile(project: Project, name: String): PsiFile? = ApexTestTarget.findClassFile(project, name)
}
