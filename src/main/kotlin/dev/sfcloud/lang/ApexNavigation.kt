package dev.sfcloud.lang

import com.intellij.codeInsight.navigation.MethodNavigationOffsetProvider
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiFile
import com.intellij.ui.IconManager
import com.intellij.ui.LayeredIcon
import javax.swing.Icon

class ApexStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder = object : TreeBasedStructureViewBuilder() {
        override fun createStructureViewModel(editor: Editor?): StructureViewModel = ApexStructureViewModel(psiFile, editor)

        override fun isRootNodeShown(): Boolean = false
    }
}

class ApexStructureViewModel(private val file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, ApexFileTreeElement(file)),
    StructureViewModel.ElementInfoProvider {

    init {
        withSorters(Sorter.ALPHA_SORTER)
    }

    override fun getCurrentEditorElement(): Any? {
        val offset = editor?.caretModel?.offset ?: return null
        return ApexStructure.memberAt(ApexStructure.of(file), offset)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        (element.value as? ApexMember)?.kind?.isType == false
}

class ApexFileTreeElement(private val file: PsiFile) : StructureViewTreeElement {
    override fun getValue(): Any = file

    override fun getPresentation(): ItemPresentation = file.presentation ?: ApexMemberPresentation(file.name, null)

    override fun getChildren(): Array<StructureViewTreeElement> =
        ApexStructure.of(file).map { ApexMemberTreeElement(file, it) }.toTypedArray()

    override fun navigate(requestFocus: Boolean) = file.navigate(requestFocus)

    override fun canNavigate(): Boolean = file.canNavigate()

    override fun canNavigateToSource(): Boolean = file.canNavigateToSource()
}

class ApexMemberTreeElement(private val file: PsiFile, private val member: ApexMember) :
    StructureViewTreeElement,
    SortableTreeElement {
    override fun getValue(): Any = member

    override fun getAlphaSortKey(): String = member.name.lowercase()

    override fun getPresentation(): ItemPresentation =
        ApexMemberPresentation(member.presentableText, ApexMemberIcons.of(member))

    override fun getChildren(): Array<StructureViewTreeElement> =
        member.children.map { ApexMemberTreeElement(file, it) }.toTypedArray()

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, member.nameOffset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()
}

class ApexMemberPresentation(private val text: String, private val icon: Icon?) : ItemPresentation {
    override fun getPresentableText(): String = text

    override fun getIcon(unused: Boolean): Icon? = icon
}

object ApexMemberIcons {
    fun of(member: ApexMember): Icon {
        val base = when (member.kind) {
            ApexMemberKind.CLASS -> if ("abstract" in member.modifiers) AllIcons.Nodes.AbstractClass else if (member.isTest) AllIcons.Nodes.Test else AllIcons.Nodes.Class
            ApexMemberKind.INTERFACE -> AllIcons.Nodes.Interface
            ApexMemberKind.ENUM -> AllIcons.Nodes.Enum
            ApexMemberKind.TRIGGER -> SfCloudIcons.Trigger
            ApexMemberKind.CONSTRUCTOR, ApexMemberKind.METHOD ->
                if (member.isTest) AllIcons.Nodes.Test else if ("abstract" in member.modifiers) AllIcons.Nodes.AbstractMethod else AllIcons.Nodes.Method
            ApexMemberKind.PROPERTY -> AllIcons.Nodes.Property
            ApexMemberKind.FIELD -> AllIcons.Nodes.Field
            ApexMemberKind.ENUM_CONSTANT -> AllIcons.Nodes.Constant
        }
        val marked = if (member.isStatic && !member.kind.isType) LayeredIcon.layeredIcon(arrayOf(base, AllIcons.Nodes.StaticMark)) else base
        val visibility = when (member.visibility) {
            "global", "public" -> AllIcons.Nodes.C_public
            "protected" -> AllIcons.Nodes.C_protected
            "private" -> AllIcons.Nodes.C_private
            else -> if (member.kind == ApexMemberKind.ENUM_CONSTANT || member.kind == ApexMemberKind.TRIGGER) null else AllIcons.Nodes.C_plocal
        } ?: return marked
        return IconManager.getInstance().createRowIcon(marked, visibility)
    }
}

class ApexMethodNavigationOffsetProvider : MethodNavigationOffsetProvider {
    override fun getMethodNavigationOffsets(file: PsiFile, caretOffset: Int): IntArray? {
        if (file !is ApexFile) return null
        return ApexStructure.of(file).asSequence()
            .flatMap { it.flatten() }
            .filter { it.kind != ApexMemberKind.ENUM_CONSTANT }
            .map { it.startOffset }
            .distinct()
            .sorted()
            .toList()
            .toIntArray()
    }
}
