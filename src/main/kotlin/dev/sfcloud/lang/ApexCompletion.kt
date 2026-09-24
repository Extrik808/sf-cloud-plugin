package dev.sfcloud.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.PlatformIcons
import dev.sfcloud.lsp.ApexLspServerSupportProvider
import dev.sfcloud.ost.OfflineSymbolTable
import dev.sfcloud.settings.SfCloudSettings

class ApexCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!SfCloudSettings.getInstance().state.apexCompletion) return
        val file = parameters.originalFile as? ApexFile ?: return
        val project = file.project
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset
        val inline = SoqlStatements.inlineAt(text, offset)
        if (inline != null) {
            val caret = SoqlContext.at(text.subSequence(inline.first, inline.last + 1), offset - inline.first)
            if (caret !is SoqlCaret.Bind) {
                val variants = SoqlCompletion.variants(project, caret)
                if (variants.isNotEmpty()) result.withPrefixMatcher(caret.prefix).addAllElements(variants)
                result.stopHere()
                return
            }
        }
        val context = ApexCaret.at(text, offset, soql = inline == null)
        val variants = when (context) {
            is ApexCaret.Soql -> SoqlCompletion.variants(project, SoqlCaret.Objects(context.prefix))
            is ApexCaret.Qualified -> qualified(file, offset, context.qualifier)
            is ApexCaret.Plain -> plain(file, offset)
        }
        val matcher = result.withPrefixMatcher(context.prefix)
        if (!ApexLspStatus.serves(file)) {
            matcher.addAllElements(variants)
            return
        }
        val offered = HashSet<String>()
        result.runRemainingContributors(parameters) { completion ->
            offered += key(completion.lookupElement.lookupString)
            result.passResult(completion)
        }
        matcher.addAllElements(variants.filter { key(it.lookupString) !in offered })
    }

    private fun key(lookup: String): String = lookup.trimStart('@').takeWhile { it.isLetterOrDigit() || it == '_' }.lowercase()

    private fun plain(file: ApexFile, offset: Int): List<LookupElement> {
        val project = file.project
        val locals = ApexLocals.inScope(file, offset).map {
            LookupElementBuilder.create(it.name).withIcon(PlatformIcons.VARIABLE_ICON).withTypeText(it.type)
        }
        val members = ApexResolver.visibleTypes(file, offset)
            .flatMap { type -> ApexResolver.membersOf(type).map { type to it } }
            .filter { it.second.kind != ApexMemberKind.CONSTRUCTOR }
            .map { (type, member) -> member(member, type.member.name) }
        val fileTypes = ApexStructure.of(file).asSequence().flatMap { it.flatten() }.filter { it.kind.isType }
            .map { LookupElementBuilder.create(it.name).withIcon(SfCloudIcons.Apex).withTypeText("this file") }
            .toList()
        val classes = ApexClasses.names(project).map {
            LookupElementBuilder.create(it).withIcon(SfCloudIcons.Apex).withTypeText("Apex class")
        }
        val objects = ApexSystem.sObjects(project).map {
            LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.DataTables).withTypeText("SObject")
        }
        val system = ApexSystem.types(project).map { LookupElementBuilder.create(it).withTypeText("System") }
        val keywords = ApexTokens.KEYWORDS.map { LookupElementBuilder.create(it).bold() }
        val annotations = ApexSystem.ANNOTATIONS.map { LookupElementBuilder.create(it).withTypeText("annotation") }
        return locals + members + fileTypes + classes + objects + system + keywords + annotations
    }

    private fun qualified(file: ApexFile, offset: Int, qualifier: String): List<LookupElement> {
        val types = ApexCompletionTypes.of(file, offset, qualifier)
        val schema = ApexCompletionTypes.sObject(file, offset, qualifier)
        if (types.isEmpty() && schema == null) {
            ApexSystem.MEMBERS[qualifier.lowercase()]?.let { members ->
                return members.map { LookupElementBuilder.create(it).withIcon(PlatformIcons.METHOD_ICON).withTypeText(qualifier) }
            }
        }
        val statics = types.isEmpty() ||
            (!ApexCompletionTypes.isVariable(file, offset, qualifier) && ApexResolver.typesNamed(file, qualifier).isNotEmpty())
        val members = types.flatMap { type -> ApexResolver.membersOf(type).map { type to it } }
            .filter { (_, member) ->
                member.kind != ApexMemberKind.CONSTRUCTOR &&
                    (!statics || member.isStatic || member.kind.isType || member.kind == ApexMemberKind.ENUM_CONSTANT)
            }
            .map { (type, member) -> member(member, type.member.name) }
        if (schema == null) return members
        val known = members.map { it.lookupString.lowercase() }.toHashSet()
        val fields = schema.fields.filter { it.name.lowercase() !in known }.map {
            LookupElementBuilder.create(it.name).withIcon(PlatformIcons.FIELD_ICON).withPresentableText("${it.name}: ${it.type}").withTypeText(schema.name)
        }
        val parents = schema.parents.filter { it.name.lowercase() !in known }.map {
            LookupElementBuilder.create(it.name).withIcon(PlatformIcons.FIELD_ICON).withPresentableText("${it.name}: ${it.target}").withTypeText(schema.name)
        }
        val children = schema.children.filter { it.name.lowercase() !in known }.map {
            LookupElementBuilder.create(it.name).withIcon(PlatformIcons.FIELD_ICON).withPresentableText("${it.name}: List<${it.target}>").withTypeText(schema.name)
        }
        return members + fields + parents + children
    }

    private fun member(member: ApexMember, owner: String): LookupElement =
        LookupElementBuilder.create(member.name)
            .withIcon(if (member.kind.isCallable) PlatformIcons.METHOD_ICON else PlatformIcons.FIELD_ICON)
            .withPresentableText(member.presentableText)
            .withTypeText(owner)
}

object ApexCompletionTypes {
    fun of(file: PsiFile, offset: Int, qualifier: String): List<ApexType> {
        val segments = qualifier.split('.').filter { it.isNotEmpty() }.let { if (it.firstOrNull().equals("this", true)) it.drop(1) else it }
        if (segments.isEmpty()) return emptyList()
        if (segments.size > 1) ApexResolver.typesNamed(file, segments.joinToString(".")).takeIf { it.isNotEmpty() }?.let { return it }
        return segments.drop(1).fold(single(file, offset, segments.first())) { types, segment -> memberTypes(types, segment) }
    }

    fun sObject(file: PsiFile, offset: Int, qualifier: String): SObjectSchema? {
        val segments = qualifier.split('.').filter { it.isNotEmpty() }.let { if (it.firstOrNull().equals("this", true)) it.drop(1) else it }
        val first = segments.firstOrNull() ?: return null
        val declared = ApexResolver.localDeclarationType(file, offset, first)
            ?: ApexResolver.visibleTypes(file, offset).flatMap { ApexResolver.membersOf(it) }
                .firstOrNull { it.name.equals(first, true) && !it.kind.isCallable && !it.kind.isType }?.type
            ?: return null
        val root = ApexResolver.baseTypeName(declared).removePrefix("Schema.")
        if (SalesforceSchema.isScalar(root)) return null
        return SalesforceSchema.traverse(file.project, root, segments.drop(1))
    }

    fun isVariable(file: PsiFile, offset: Int, qualifier: String): Boolean {
        if (qualifier.contains('.')) return false
        if (ApexResolver.localDeclarationType(file, offset, qualifier) != null) return true
        return ApexResolver.visibleTypes(file, offset).flatMap { ApexResolver.membersOf(it) }
            .any { it.name.equals(qualifier, true) && !it.kind.isCallable && !it.kind.isType }
    }

    private fun memberTypes(types: List<ApexType>, name: String): List<ApexType> =
        types.flatMap { type ->
            val nested = type.member.children.filter { it.kind.isType && it.name.equals(name, true) }.map { ApexType(type.file, it) }
            nested.ifEmpty {
                ApexResolver.membersOf(type)
                    .filter { it.name.equals(name, true) && !it.kind.isCallable && !it.kind.isType && it.type.isNotEmpty() }
                    .flatMap { ApexResolver.typesNamed(type.file, ApexResolver.baseTypeName(it.type)) }
            }
        }.distinct()

    private fun single(file: PsiFile, offset: Int, qualifier: String): List<ApexType> {
        val local = ApexResolver.localDeclarationType(file, offset, qualifier)
        if (local != null) return ApexResolver.typesNamed(file, ApexResolver.baseTypeName(local))
        val member = ApexResolver.visibleTypes(file, offset)
            .flatMap { ApexResolver.membersOf(it) }
            .firstOrNull { it.name.equals(qualifier, true) && !it.kind.isCallable }
        if (member != null && member.type.isNotEmpty()) {
            return ApexResolver.typesNamed(file, ApexResolver.baseTypeName(member.type))
        }
        return ApexResolver.typesNamed(file, qualifier)
    }
}

object ApexLspStatus {
    fun isRunning(project: Project): Boolean = try {
        LspServerManager.getInstance(project)
            .getServersForProvider(ApexLspServerSupportProvider::class.java)
            .any { it.state == LspServerState.Running }
    } catch (_: Throwable) {
        false
    }

    fun serves(file: PsiFile): Boolean {
        val virtualFile = file.originalFile.virtualFile ?: return false
        if (virtualFile is LightVirtualFile || !ApexLspServerSupportProvider.isApex(virtualFile)) return false
        if (!ProjectFileIndex.getInstance(file.project).isInContent(virtualFile)) return false
        return isRunning(file.project)
    }
}

class ApexLocal(val name: String, val type: String)

object ApexLocals {
    fun inScope(file: PsiFile, offset: Int): List<ApexLocal> {
        val text = file.viewProvider.contents
        val scope = ApexResolver.memberPath(ApexStructure.of(file), offset)
            .lastOrNull { it.kind.isCallable || it.kind == ApexMemberKind.TRIGGER }
            ?: return emptyList()
        val end = minOf(offset, text.length)
        if (scope.startOffset >= end) return emptyList()
        val locals = LinkedHashMap<String, ApexLocal>()
        val tokens = tokenize(text.subSequence(scope.startOffset, end))
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.type != ApexTokens.IDENTIFIER) continue
            val next = tokens.getOrNull(i + 1) ?: continue
            val declares = next.type == ApexTokens.SEMICOLON ||
                (next.type == ApexTokens.OPERATOR && (next.text == "=" || next.text == ":")) ||
                next.type == ApexTokens.RPAREN || next.type == ApexTokens.COMMA
            if (!declares) continue
            val type = typeBefore(tokens, i) ?: continue
            locals.putIfAbsent(token.text, ApexLocal(token.text, type))
        }
        return locals.values.toList()
    }

    private fun typeBefore(tokens: List<ApexToken>, index: Int): String? {
        var i = index - 1
        if (i < 0) return null
        if (tokens[i].type == ApexTokens.OPERATOR && tokens[i].text.all { it == '>' }) {
            var depth = tokens[i].text.length
            i--
            while (i >= 0 && depth > 0) {
                if (tokens[i].type == ApexTokens.OPERATOR) {
                    depth += tokens[i].text.count { it == '>' }
                    depth -= tokens[i].text.count { it == '<' }
                }
                i--
            }
        }
        if (i < 0) return null
        val token = tokens[i]
        val isType = token.type == ApexTokens.IDENTIFIER ||
            (token.type == ApexTokens.KEYWORD && token.text.lowercase() in TYPE_KEYWORDS)
        if (!isType) return null
        val before = tokens.getOrNull(i - 1)
        if (before?.type == ApexTokens.DOT) return null
        if (before?.type == ApexTokens.KEYWORD && before.text.lowercase() in NOT_TYPES) return null
        return token.text
    }

    private fun tokenize(text: CharSequence): List<ApexToken> {
        val lexer = ApexLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<ApexToken>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in ApexTokens.COMMENTS) {
                tokens += ApexToken(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString())
            }
            lexer.advance()
        }
        return tokens
    }

    private val TYPE_KEYWORDS = setOf(
        "boolean", "integer", "long", "double", "decimal", "string", "id", "blob",
        "date", "datetime", "time", "object", "sobject", "list", "map", "set",
    )
    private val NOT_TYPES = setOf("return", "new", "throw", "case", "instanceof")
}

class ApexToken(val type: com.intellij.psi.tree.IElementType, val text: String)

sealed interface ApexCaret {
    val prefix: String

    class Plain(override val prefix: String) : ApexCaret

    class Qualified(override val prefix: String, val qualifier: String) : ApexCaret

    class Soql(override val prefix: String) : ApexCaret

    companion object {
        fun at(text: CharSequence, offset: Int, soql: Boolean = true): ApexCaret {
            val safe = offset.coerceIn(0, text.length)
            var start = safe
            while (start > 0 && isIdentifierPart(text[start - 1])) start--
            val prefix = text.subSequence(start, safe).toString()
            if (soql && inSoql(text, start)) return Soql(prefix)
            var before = start - 1
            while (before >= 0 && text[before].isWhitespace()) before--
            if (before < 0 || text[before] != '.') return Plain(prefix)
            var qualifierEnd = before
            while (qualifierEnd > 0 && text[qualifierEnd - 1].isWhitespace()) qualifierEnd--
            var qualifierStart = qualifierEnd
            while (qualifierStart > 0 && isIdentifierPart(text[qualifierStart - 1])) qualifierStart--
            while (qualifierStart > 1 && text[qualifierStart - 1] == '.' && isIdentifierPart(text[qualifierStart - 2])) {
                qualifierStart--
                while (qualifierStart > 0 && isIdentifierPart(text[qualifierStart - 1])) qualifierStart--
            }
            val qualifier = text.subSequence(qualifierStart, qualifierEnd).toString()
            return if (qualifier.isEmpty()) Plain(prefix) else Qualified(prefix, qualifier)
        }

        private fun inSoql(text: CharSequence, offset: Int): Boolean {
            var depth = 0
            var i = offset - 1
            while (i >= 0) {
                when (text[i]) {
                    ']' -> depth++
                    '[' -> if (depth == 0) return true else depth--
                    ';', '{', '}' -> return false
                }
                i--
            }
            return false
        }

        private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
    }
}

object ApexSystem {
    val TYPES: List<String> = listOf(
        "System", "Database", "Schema", "Test", "Math", "JSON", "Limits", "UserInfo", "Messaging", "ApexPages",
        "EncodingUtil", "Crypto", "Http", "HttpRequest", "HttpResponse", "Url", "Blob", "Id", "String", "Integer",
        "Long", "Double", "Decimal", "Boolean", "Date", "Datetime", "Time", "List", "Map", "Set", "SObject",
        "Exception", "DmlException", "QueryException", "AuraHandledException", "Savepoint", "PageReference",
        "StaticResourceCalloutMock", "HttpCalloutMock", "Queueable", "Batchable", "Schedulable", "Comparable",
    )

    val ANNOTATIONS: List<String> = listOf(
        "@AuraEnabled", "@Deprecated", "@Future", "@InvocableMethod", "@InvocableVariable", "@IsTest",
        "@JsonAccess", "@NamespaceAccessible", "@ReadOnly", "@RemoteAction", "@SuppressWarnings",
        "@TestSetup", "@TestVisible", "@RestResource", "@HttpGet", "@HttpPost", "@HttpPut", "@HttpPatch",
        "@HttpDelete", "@SuppressWarnings",
    )

    val MEMBERS: Map<String, List<String>> = mapOf(
        "system" to listOf(
            "debug", "assert", "assertEquals", "assertNotEquals", "now", "today", "currentTimeMillis",
            "runAs", "enqueueJob", "schedule", "abortJob", "isBatch", "isFuture", "isQueueable", "isScheduled",
            "resetPassword", "setPassword", "requestVersion", "currentPageReference",
        ),
        "database" to listOf(
            "insert", "update", "upsert", "delete", "undelete", "query", "queryWithBinds", "countQuery",
            "getQueryLocator", "executeBatch", "rollback", "setSavepoint", "convertLead", "merge", "emptyRecycleBin",
        ),
        "limits" to listOf(
            "getQueries", "getLimitQueries", "getDmlStatements", "getLimitDmlStatements", "getDmlRows",
            "getLimitDmlRows", "getCpuTime", "getLimitCpuTime", "getHeapSize", "getLimitHeapSize",
            "getCallouts", "getLimitCallouts", "getQueryRows", "getLimitQueryRows", "getFutureCalls",
        ),
        "test" to listOf(
            "startTest", "stopTest", "setMock", "loadData", "isRunningTest", "setCreatedDate",
            "setReadOnlyApplicationMode", "getStandardPricebookId", "setCurrentPage", "setFixedSearchResults",
        ),
        "json" to listOf("serialize", "serializePretty", "deserialize", "deserializeUntyped", "deserializeStrict", "createGenerator", "createParser"),
        "userinfo" to listOf(
            "getUserId", "getUserName", "getName", "getFirstName", "getLastName", "getProfileId", "getUserEmail",
            "getOrganizationId", "getOrganizationName", "getLocale", "getLanguage", "getTimeZone", "getSessionId",
            "isMultiCurrencyOrganization", "getDefaultCurrency", "getUserType", "getUiThemeDisplayed",
        ),
        "schema" to listOf("getGlobalDescribe", "describeSObjects", "describeDataCategoryGroups", "SObjectType"),
        "math" to listOf("abs", "ceil", "floor", "round", "roundToLong", "max", "min", "mod", "pow", "random", "sqrt"),
        "encodingutil" to listOf("base64Encode", "base64Decode", "urlEncode", "urlDecode", "convertToHex", "convertFromHex"),
        "trigger" to listOf(
            "new", "old", "newMap", "oldMap", "isInsert", "isUpdate", "isDelete", "isUndelete",
            "isBefore", "isAfter", "isExecuting", "size", "operationType",
        ),
    )

    fun types(project: Project): List<String> =
        OfflineSymbolTable.getInstance(project).typeNames().ifEmpty { TYPES }

    fun sObjects(project: Project): List<String> = SalesforceSchema.objectNames(project)
}
