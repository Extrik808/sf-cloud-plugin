package dev.sfcloud.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.xml.XmlElementDescriptorProvider
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.XmlAttributeDescriptor
import com.intellij.xml.XmlAttributeDescriptorsProvider
import com.intellij.xml.XmlElementDescriptor
import com.intellij.xml.XmlElementsGroup
import com.intellij.xml.XmlNSDescriptor
import com.intellij.xml.impl.schema.AnyXmlAttributeDescriptor
import dev.sfcloud.settings.SfCloudSettings

class LwcTagDescriptorProvider : XmlElementDescriptorProvider {
    override fun getDescriptor(tag: XmlTag): XmlElementDescriptor? {
        if (!SfCloudSettings.getInstance().state.lwcCompletion) return null
        if (!inTemplate(tag) || !LwcTemplateModel.isComponentTag(tag.name)) return null
        return LwcTagDescriptor(tag)
    }

    companion object {
        fun inTemplate(tag: XmlTag): Boolean {
            val file = tag.containingFile?.virtualFile ?: return false
            return LwcComponents.isTemplate(file)
        }
    }
}

class LwcTagDescriptor(private val tag: XmlTag) : XmlElementDescriptor {
    override fun getQualifiedName(): String = tag.name

    override fun getDefaultName(): String = tag.name

    override fun getName(context: PsiElement?): String = tag.name

    override fun getName(): String = tag.name

    override fun init(element: PsiElement?) = Unit

    override fun getDeclaration(): PsiElement? = null

    override fun getElementsDescriptors(context: XmlTag?): Array<XmlElementDescriptor> = XmlElementDescriptor.EMPTY_ARRAY

    override fun getElementDescriptor(childTag: XmlTag, contextTag: XmlTag?): XmlElementDescriptor? =
        if (LwcTemplateModel.isComponentTag(childTag.name)) LwcTagDescriptor(childTag) else null

    override fun getAttributesDescriptors(context: XmlTag?): Array<XmlAttributeDescriptor> =
        LwcAttributes.of(tag).map { AnyXmlAttributeDescriptor(it) as XmlAttributeDescriptor }.toTypedArray()

    override fun getAttributeDescriptor(attributeName: String?, context: XmlTag?): XmlAttributeDescriptor? =
        attributeName?.let { AnyXmlAttributeDescriptor(it) }

    override fun getAttributeDescriptor(attribute: XmlAttribute?): XmlAttributeDescriptor? =
        attribute?.name?.let { AnyXmlAttributeDescriptor(it) }

    override fun getNSDescriptor(): XmlNSDescriptor? = null

    override fun getTopGroup(): XmlElementsGroup? = null

    override fun getContentType(): Int = XmlElementDescriptor.CONTENT_TYPE_ANY

    override fun getDefaultValue(): String? = null
}

class LwcAttributeDescriptorsProvider : XmlAttributeDescriptorsProvider {
    override fun getAttributeDescriptors(tag: XmlTag?): Array<XmlAttributeDescriptor> {
        if (tag == null || !SfCloudSettings.getInstance().state.lwcCompletion) return XmlAttributeDescriptor.EMPTY
        if (!LwcTagDescriptorProvider.inTemplate(tag)) return XmlAttributeDescriptor.EMPTY
        return LwcAttributes.of(tag).map { AnyXmlAttributeDescriptor(it) as XmlAttributeDescriptor }.toTypedArray()
    }

    override fun getAttributeDescriptor(attributeName: String?, tag: XmlTag?): XmlAttributeDescriptor? {
        if (attributeName == null || tag == null || !SfCloudSettings.getInstance().state.lwcCompletion) return null
        if (!LwcTagDescriptorProvider.inTemplate(tag)) return null
        if (!LwcTemplateModel.isDirective(attributeName) && !LwcTemplateModel.isComponentTag(tag.name)) return null
        return AnyXmlAttributeDescriptor(attributeName)
    }
}

object LwcAttributes {
    fun of(tag: XmlTag): List<String> {
        val component = LwcComponents.find(tag.project, tag.name)
        val own = component?.apiProperties().orEmpty()
        return own + LwcTemplateModel.DIRECTIVES + LwcTemplateModel.EVENT_HANDLERS
    }
}

