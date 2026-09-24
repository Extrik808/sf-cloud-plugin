package dev.sfcloud.lang

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiFile

class ApexTemplateContextType : TemplateContextType("Apex") {
    override fun isInContext(context: TemplateActionContext): Boolean =
        context.file.language.isKindOf(ApexLanguage)
}

class LwcJsTemplateContextType : TemplateContextType("LWC JavaScript") {
    override fun isInContext(context: TemplateActionContext): Boolean =
        SfTemplates.isBundleFile(context.file, setOf("js", "ts"))
}

class LwcHtmlTemplateContextType : TemplateContextType("LWC Template") {
    override fun isInContext(context: TemplateActionContext): Boolean =
        SfTemplates.isBundleFile(context.file, setOf("html"))
}

object SfTemplates {
    fun isBundleFile(file: PsiFile, extensions: Set<String>): Boolean {
        val virtual = file.originalFile.virtualFile ?: file.viewProvider.virtualFile
        val name = virtual?.name ?: file.name
        if (name.substringAfterLast('.', "").lowercase() !in extensions) return false
        return virtual?.parent?.parent?.name?.lowercase() == "lwc"
    }
}
