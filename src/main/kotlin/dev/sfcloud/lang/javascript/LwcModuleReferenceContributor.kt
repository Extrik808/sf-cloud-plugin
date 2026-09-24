package dev.sfcloud.lang.javascript

import com.intellij.javascript.JSModuleBaseReference
import com.intellij.lang.javascript.frameworks.modules.JSResolvableModuleReferenceContributor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveResult
import dev.sfcloud.lang.LwcImport
import dev.sfcloud.lang.LwcModuleTargets

class LwcModuleReferenceContributor : JSResolvableModuleReferenceContributor() {
    override fun isAcceptableText(unquotedEscapedText: String): Boolean = LwcImport.componentName(unquotedEscapedText) != null

    override fun isApplicable(host: PsiElement): Boolean {
        val file = host.containingFile?.originalFile?.virtualFile ?: return false
        return generateSequence(file.parent) { it.parent }.any { it.name == "lwc" }
    }

    override fun resolveElement(context: PsiElement, text: String): Array<ResolveResult> {
        val name = LwcImport.componentName(text) ?: return ResolveResult.EMPTY_ARRAY
        val project = context.project
        val script = LwcModuleTargets.componentScript(project, name) ?: return ResolveResult.EMPTY_ARRAY
        val psi = PsiManager.getInstance(project).findFile(script) ?: return ResolveResult.EMPTY_ARRAY
        return arrayOf(PsiElementResolveResult(psi))
    }

    override fun getDefaultWeight(): Int = JSModuleBaseReference.ModuleTypes.PATH_MAPPING.weight()
}
