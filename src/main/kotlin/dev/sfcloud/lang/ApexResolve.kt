package dev.sfcloud.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import dev.sfcloud.ost.OfflineSymbolTable
import dev.sfcloud.tests.ApexTestTarget

data class ApexType(val file: PsiFile, val member: ApexMember)

object ApexResolver {
    private const val MAX_DEPTH = 8
    private const val SCHEMA = "Schema"
    private val IMPLICIT_NAMESPACES = setOf("system", "schema")

    fun targets(element: ApexReferenceElement): List<ApexNamedElement> =
        CachedValuesManager.getCachedValue(element) {
            CachedValueProvider.Result.create(resolve(element, 0), PsiModificationTracker.MODIFICATION_COUNT)
        }

    fun localDeclarationType(file: PsiFile, offset: Int, name: String): String? {
        var leaf: PsiElement? = file.findElementAt(scopeStart(file, offset))
        var found: String? = null
        while (leaf != null && leaf.textRange.startOffset < offset) {
            if (isWord(leaf) && leaf.text.equals(name, true) && declaresVariable(leaf)) {
                typeBefore(leaf)?.let { found = it }
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return found
    }

    fun visibleTypes(file: PsiFile, offset: Int): List<ApexType> = inherited(enclosingTypes(file, offset))

    fun typesNamed(context: PsiFile, name: String): List<ApexType> = resolveType(context, name, 0)

    fun membersOf(type: ApexType): List<ApexMember> =
        (listOf(type) + supertypes(type, 0)).distinct().flatMap { it.member.children }

    fun enclosingTypes(file: PsiFile, offset: Int): List<ApexType> {
        val path = memberPath(ApexStructure.of(file), offset).filter { it.kind.isType }
        if (path.isNotEmpty()) return path.reversed().map { ApexType(file, it) }
        return ApexStructure.of(file).filter { it.kind.isType }.map { ApexType(file, it) }
    }

    fun memberPath(members: List<ApexMember>, offset: Int): List<ApexMember> {
        val path = mutableListOf<ApexMember>()
        var level = members
        while (true) {
            val next = level.firstOrNull { it.contains(offset) } ?: return path
            path += next
            level = next.children
        }
    }

    fun declarationElement(file: PsiFile, member: ApexMember): ApexNamedElement? =
        file.findElementAt(member.nameOffset)?.parent as? ApexNamedElement

    fun baseTypeName(type: String): String = type.substringBefore('<').replace("[]", "").trim()

    private fun resolve(element: ApexReferenceElement, depth: Int): List<ApexNamedElement> {
        if (depth > MAX_DEPTH) return emptyList()
        val file = element.containingFile as? ApexFile ?: return emptyList()
        val name = element.referenceName.takeIf { it.isNotEmpty() } ?: return emptyList()
        return when (val qualifier = qualifierOf(element)) {
            Qualifier.None -> unqualified(file, element, name)
            Qualifier.This -> inherited(enclosingTypes(file, element.textRange.startOffset)).flatMap { declarations(it, name, isCall(element)) }
            Qualifier.Super -> supertypesOf(enclosingTypes(file, element.textRange.startOffset)).flatMap { declarations(it, name, isCall(element)) }
            Qualifier.Unsupported -> emptyList()
            is Qualifier.Ref -> inherited(qualifierTypes(file, qualifier.element, depth))
                .flatMap { declarations(it, name, isCall(element)) }
                .distinct()
        }
    }

    private fun unqualified(file: ApexFile, element: ApexReferenceElement, name: String): List<ApexNamedElement> {
        val offset = element.textRange.startOffset
        if (isInstantiation(element)) return typeDeclarations(file, name)
        if (localDeclarationType(file, offset, name) != null) return emptyList()
        val call = isCall(element)
        val members = inherited(enclosingTypes(file, offset)).flatMap { declarations(it, name, call) }.distinct()
        if (members.isNotEmpty()) return members
        return typeDeclarations(file, name)
    }

    private fun qualifierTypes(file: ApexFile, qualifier: ApexReferenceElement, depth: Int): List<ApexType> {
        val resolved = resolve(qualifier, depth + 1)
        if (resolved.isNotEmpty()) {
            return resolved.flatMap { target ->
                val member = target.member
                when {
                    member == null -> emptyList()
                    member.kind.isType -> listOf(ApexType(target.containingFile, member))
                    else -> resolveType(target.containingFile, baseTypeName(member.type), depth + 1)
                }
            }
        }
        val local = localDeclarationType(file, qualifier.textRange.startOffset, qualifier.referenceName)
        if (local != null) return resolveType(file, baseTypeName(local), depth + 1)
        return resolveType(file, qualifier.referenceName, depth + 1)
    }

    private fun typeDeclarations(context: PsiFile, name: String): List<ApexNamedElement> =
        resolveType(context, name, 0).mapNotNull { declarationElement(it.file, it.member) }

    private fun resolveType(context: PsiFile, name: String, depth: Int): List<ApexType> {
        if (depth > MAX_DEPTH || name.isEmpty()) return emptyList()
        val segments = name.split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()
        val direct = resolveSegments(context, segments)
        return when {
            direct.isNotEmpty() -> direct
            segments.size > 1 && segments.first().lowercase() in IMPLICIT_NAMESPACES -> resolveSegments(context, segments.drop(1))
            segments.size == 1 -> resolveSegments(context, listOf(SCHEMA, segments.first()))
            else -> direct
        }
    }

    private fun resolveSegments(context: PsiFile, segments: List<String>): List<ApexType> {
        var current = fileTypes(context, segments.first()).ifEmpty { projectTypes(context.project, segments.first()) }
        for (segment in segments.drop(1)) {
            current = current.flatMap { type ->
                type.member.children
                    .filter { it.kind.isType && it.name.equals(segment, true) }
                    .map { ApexType(type.file, it) }
            }
        }
        return current
    }

    private fun fileTypes(context: PsiFile, name: String): List<ApexType> =
        ApexStructure.of(context).asSequence()
            .flatMap { it.flatten() }
            .filter { it.kind.isType && it.name.equals(name, true) }
            .map { ApexType(context, it) }
            .toList()

    private fun projectTypes(project: Project, name: String): List<ApexType> {
        val file = ApexTestTarget.findClassFile(project, name)
            ?: OfflineSymbolTable.getInstance(project).file(name)
            ?: return emptyList()
        return ApexStructure.of(file).filter { it.kind.isType && it.name.equals(name, true) }.map { ApexType(file, it) }
    }

    private fun inherited(types: List<ApexType>): List<ApexType> =
        types.flatMap { listOf(it) + supertypes(it, 0) }.distinct()

    private fun supertypesOf(types: List<ApexType>): List<ApexType> = types.flatMap { supertypes(it, 0) }.distinct()

    private fun supertypes(type: ApexType, depth: Int): List<ApexType> {
        if (depth > MAX_DEPTH) return emptyList()
        return supertypeNames(type)
            .flatMap { resolveType(type.file, it, depth + 1) }
            .flatMap { listOf(it) + supertypes(it, depth + 1) }
    }

    private fun supertypeNames(type: ApexType): List<String> {
        val text = type.file.viewProvider.contents
        val start = type.member.nameOffset + type.member.name.length
        if (start >= text.length) return emptyList()
        val end = text.indexOf('{', start).takeIf { it > start } ?: return emptyList()
        val names = mutableListOf<String>()
        var collecting = false
        WORD.findAll(text.subSequence(start, end)).forEach { match ->
            val word = match.value
            when {
                word.equals("extends", true) || word.equals("implements", true) -> collecting = true
                collecting -> names += word
            }
        }
        return names
    }

    private fun declarations(type: ApexType, name: String, call: Boolean): List<ApexNamedElement> {
        val matches = type.member.children.filter { it.name.equals(name, true) && it.kind != ApexMemberKind.CONSTRUCTOR }
        if (matches.isEmpty()) return emptyList()
        val preferred = if (call) matches.filter { it.kind.isCallable } else matches.filter { !it.kind.isCallable }
        return preferred.ifEmpty { matches }.mapNotNull { declarationElement(type.file, it) }
    }

    private fun qualifierOf(element: ApexReferenceElement): Qualifier {
        if (element.firstChild.elementType == ApexTokens.BIND_VARIABLE) return Qualifier.None
        val dot = previousCode(element) ?: return Qualifier.None
        if (dot.elementType != ApexTokens.DOT) return Qualifier.None
        val before = previousCode(dot) ?: return Qualifier.Unsupported
        val parent = before.parent
        return when {
            before.elementType == ApexTokens.KEYWORD && before.text.equals("this", true) -> Qualifier.This
            before.elementType == ApexTokens.KEYWORD && before.text.equals("super", true) -> Qualifier.Super
            parent is ApexReferenceElement -> Qualifier.Ref(parent)
            else -> Qualifier.Unsupported
        }
    }

    private fun scopeStart(file: PsiFile, offset: Int): Int =
        memberPath(ApexStructure.of(file), offset)
            .lastOrNull { it.kind.isCallable || it.kind == ApexMemberKind.TRIGGER }
            ?.startOffset
            ?: 0

    private fun declaresVariable(name: PsiElement): Boolean {
        val next = nextCode(name) ?: return false
        return when (next.elementType) {
            ApexTokens.SEMICOLON, ApexTokens.COMMA, ApexTokens.RPAREN -> true
            ApexTokens.OPERATOR -> next.text == "=" || next.text == ":"
            else -> false
        }
    }

    private fun typeBefore(name: PsiElement): String? {
        var leaf = previousCode(name) ?: return null
        if (leaf.elementType == ApexTokens.OPERATOR && (leaf.text == ">" || leaf.text == ">>" || leaf.text == ">>>")) {
            leaf = beforeGenerics(leaf) ?: return null
        } else if (leaf.elementType == ApexTokens.RBRACKET) {
            leaf = previousCode(leaf)?.takeIf { it.elementType == ApexTokens.LBRACKET }?.let { previousCode(it) } ?: return null
        }
        if (!isTypeWord(leaf)) return null
        val segments = mutableListOf(leaf.text)
        var dot = previousCode(leaf)
        while (dot?.elementType == ApexTokens.DOT) {
            val segment = previousCode(dot)?.takeIf { isWord(it) } ?: break
            segments += segment.text
            dot = previousCode(segment)
        }
        return segments.reversed().joinToString(".")
    }

    private fun beforeGenerics(closing: PsiElement): PsiElement? {
        var depth = closing.text.length
        var leaf = previousCode(closing)
        while (leaf != null && depth > 0) {
            if (leaf.elementType == ApexTokens.OPERATOR) {
                depth += leaf.text.count { it == '>' }
                depth -= leaf.text.count { it == '<' }
            }
            if (depth <= 0) break
            leaf = previousCode(leaf)
        }
        return leaf?.let { previousCode(it) }
    }

    private fun isWord(element: PsiElement): Boolean = element.elementType == ApexTokens.IDENTIFIER

    private fun isTypeWord(element: PsiElement): Boolean =
        isWord(element) || (element.elementType == ApexTokens.KEYWORD && element.text.lowercase() in TYPE_KEYWORDS)

    private fun isCall(element: PsiElement): Boolean = nextCode(element)?.elementType == ApexTokens.LPAREN

    private fun isInstantiation(element: PsiElement): Boolean {
        val previous = previousCode(element) ?: return false
        return previous.elementType == ApexTokens.KEYWORD && previous.text.equals("new", true)
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

    private val WORD = Regex("""[\w.]+""")

    private val TYPE_KEYWORDS = setOf(
        "boolean", "integer", "long", "double", "decimal", "string", "id", "blob",
        "date", "datetime", "time", "object", "sobject", "list", "map", "set",
    )

    private sealed interface Qualifier {
        object None : Qualifier
        object This : Qualifier
        object Super : Qualifier
        object Unsupported : Qualifier
        class Ref(val element: ApexReferenceElement) : Qualifier
    }
}
