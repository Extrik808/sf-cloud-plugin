package dev.sfcloud.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import dev.sfcloud.core.SfCli
import dev.sfcloud.core.SfCliException
import dev.sfcloud.core.objects
import dev.sfcloud.core.str
import dev.sfcloud.org.OrgService
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SfApiException(message: String, val status: Int = 0, val errorCode: String? = null) : Exception(message)

data class OrgSession(
    val key: String,
    val username: String,
    val alias: String?,
    val instanceUrl: String,
    val accessToken: String,
    val apiVersion: String,
) {
    val displayName: String get() = alias ?: username
}

data class QueryResult(val totalSize: Int, val records: List<JsonObject>, val done: Boolean)

@Service(Service.Level.PROJECT)
class SfApi(private val project: Project) {
    private val sessions = ConcurrentHashMap<String, OrgSession>()
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun session(org: String?, indicator: ProgressIndicator? = null): OrgSession {
        val key = OrgService.getInstance(project).orgKey(org).orEmpty()
        sessions[key]?.let { return it }
        indicator?.text2 = "Logging in to ${key.ifEmpty { "the default org" }}"
        val args = mutableListOf("org", "display")
        if (key.isNotEmpty()) args += listOf("--target-org", key)
        val result = try {
            SfCli.run(project, args, 120_000, indicator)
        } catch (e: SfCliException) {
            throw SfApiException(e.message.orEmpty())
        }
        val payload = result.payload?.takeIf { it.isJsonObject }?.asJsonObject
        val instance = payload?.str("instanceUrl")
        if (!result.success || payload == null || instance.isNullOrEmpty()) throw SfApiException(result.message)
        val token = usableToken(payload.str("accessToken"))
            ?: accessToken(payload.str("username")?.takeIf { it.isNotBlank() } ?: key, indicator)
        val session = OrgSession(
            key = key,
            username = payload.str("username").orEmpty(),
            alias = payload.str("alias"),
            instanceUrl = instance.trimEnd('/'),
            accessToken = token,
            apiVersion = payload.str("apiVersion") ?: DEFAULT_API_VERSION,
        )
        sessions[key] = session
        return session
    }

    private fun accessToken(target: String, indicator: ProgressIndicator?): String {
        val args = mutableListOf("org", "auth", "show-access-token", "--no-prompt")
        if (target.isNotEmpty()) args += listOf("--target-org", target)
        val result = try {
            SfCli.run(project, args, 120_000, indicator)
        } catch (e: SfCliException) {
            throw SfApiException(e.message.orEmpty())
        }
        val payload = result.payload?.takeIf { it.isJsonObject }?.asJsonObject
        return usableToken(payload?.str("accessToken")).takeIf { result.success }
            ?: throw SfApiException("Cannot obtain an access token for ${target.ifEmpty { "the default org" }}: ${result.message}")
    }

    fun invalidate(org: String? = null) {
        if (org == null) sessions.clear() else sessions.remove(OrgService.getInstance(project).orgKey(org).orEmpty())
    }

    fun dataPath(session: OrgSession, tooling: Boolean, suffix: String): String =
        "/services/data/v${session.apiVersion}" + (if (tooling) "/tooling" else "") + suffix

    fun query(org: String?, soql: String, tooling: Boolean = false, all: Boolean = false, maxRows: Int = Int.MAX_VALUE, indicator: ProgressIndicator? = null): QueryResult {
        val session = session(org, indicator)
        val endpoint = if (all && !tooling) "/queryAll/" else "/query/"
        var json = getJson(org, dataPath(session, tooling, endpoint + "?q=" + encode(soql)), indicator).asJsonObject
        val total = json.get("totalSize")?.asInt ?: 0
        val records = ArrayList<JsonObject>(json.get("records").objects())
        while (json.get("done")?.asBoolean == false && records.size < maxRows) {
            indicator?.checkCanceled()
            val next = json.str("nextRecordsUrl") ?: break
            json = getJson(org, next, indicator).asJsonObject
            records += json.get("records").objects()
        }
        return QueryResult(total, if (records.size > maxRows) records.subList(0, maxRows) else records, records.size >= total)
    }

    fun search(org: String?, sosl: String, indicator: ProgressIndicator? = null): List<JsonObject> {
        val session = session(org, indicator)
        val json = getJson(org, dataPath(session, false, "/search/?q=" + encode(sosl)), indicator)
        return when {
            json.isJsonObject -> json.asJsonObject.get("searchRecords").objects()
            else -> json.objects()
        }
    }

    fun explain(org: String?, soql: String, indicator: ProgressIndicator? = null): JsonObject {
        val session = session(org, indicator)
        return getJson(org, dataPath(session, false, "/query/?explain=" + encode(soql)), indicator).asJsonObject
    }

    fun describe(org: String?, sobject: String, tooling: Boolean = false, indicator: ProgressIndicator? = null): JsonObject {
        val session = session(org, indicator)
        return getJson(org, dataPath(session, tooling, "/sobjects/$sobject/describe"), indicator).asJsonObject
    }

    fun create(org: String?, sobject: String, fields: JsonObject, tooling: Boolean = false, indicator: ProgressIndicator? = null): String {
        val session = session(org, indicator)
        val response = send(org, "POST", dataPath(session, tooling, "/sobjects/$sobject"), fields.toString(), indicator)
        return parse(response)?.asJsonObject?.str("id") ?: throw SfApiException("No id returned when creating $sobject")
    }

    fun update(org: String?, sobject: String, id: String, fields: JsonObject, tooling: Boolean = false, indicator: ProgressIndicator? = null) {
        val session = session(org, indicator)
        send(org, "PATCH", dataPath(session, tooling, "/sobjects/$sobject/$id"), fields.toString(), indicator)
    }

    fun delete(org: String?, sobject: String, id: String, tooling: Boolean = false, indicator: ProgressIndicator? = null) {
        val session = session(org, indicator)
        send(org, "DELETE", dataPath(session, tooling, "/sobjects/$sobject/$id"), null, indicator)
    }

    fun deleteRecords(org: String?, ids: List<String>, indicator: ProgressIndicator? = null): List<String> {
        val session = session(org, indicator)
        val failures = mutableListOf<String>()
        ids.chunked(200).forEach { chunk ->
            indicator?.checkCanceled()
            val path = dataPath(session, false, "/composite/sobjects?allOrNone=false&ids=" + chunk.joinToString(","))
            val results = parse(send(org, "DELETE", path, null, indicator)).objects()
            results.filter { it.get("success")?.asBoolean == false }.forEach { result ->
                val message = result.get("errors").objects().joinToString("; ") { it.str("message").orEmpty() }
                failures += "${result.str("id").orEmpty()}: $message"
            }
        }
        return failures
    }

    fun logBody(org: String?, id: String, indicator: ProgressIndicator? = null): String {
        val session = session(org, indicator)
        return send(org, "GET", dataPath(session, true, "/sobjects/ApexLog/$id/Body"), null, indicator)
    }

    fun userId(org: String?, indicator: ProgressIndicator? = null): String {
        val session = session(org, indicator)
        val result = query(org, "SELECT Id FROM User WHERE Username = '${escapeSoql(session.username)}' LIMIT 1", indicator = indicator)
        return result.records.firstOrNull()?.str("Id") ?: throw SfApiException("User ${session.username} was not found")
    }

    fun getJson(org: String?, path: String, indicator: ProgressIndicator? = null): JsonElement =
        parse(send(org, "GET", path, null, indicator)) ?: JsonNull.INSTANCE

    fun soap(org: String?, path: String, envelope: (OrgSession) -> String, indicator: ProgressIndicator? = null): String =
        execute(org, indicator) { session ->
            HttpRequest.newBuilder(URI.create(session.instanceUrl + path.replace("{version}", session.apiVersion)))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "text/xml; charset=UTF-8")
                .header("SOAPAction", "\"\"")
                .POST(HttpRequest.BodyPublishers.ofString(envelope(session)))
                .build()
        }

    fun send(org: String?, method: String, path: String, body: String?, indicator: ProgressIndicator? = null): String =
        execute(org, indicator) { session ->
            val url = if (path.startsWith("http")) path else session.instanceUrl + path
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                .build()
        }

    private fun execute(org: String?, indicator: ProgressIndicator?, build: (OrgSession) -> HttpRequest): String {
        var retried = false
        while (true) {
            val session = session(org, indicator)
            val response = await(client.sendAsync(build(session), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), indicator)
            val status = response.statusCode()
            val isSoapSessionError = status == 500 && response.body().contains("INVALID_SESSION_ID")
            if ((status == 401 || isSoapSessionError) && !retried) {
                retried = true
                sessions.remove(session.key)
                continue
            }
            if (status in 200..299) return response.body()
            throw error(status, response.body())
        }
    }

    private fun await(future: java.util.concurrent.CompletableFuture<HttpResponse<String>>, indicator: ProgressIndicator?): HttpResponse<String> {
        while (true) {
            try {
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                val canceled = indicator?.isCanceled == true || ProgressManager.getInstance().progressIndicator?.isCanceled == true
                if (canceled) {
                    future.cancel(true)
                    throw SfApiException("Cancelled")
                }
            } catch (e: java.util.concurrent.ExecutionException) {
                throw SfApiException(e.cause?.message ?: e.message.orEmpty())
            }
        }
    }

    private fun error(status: Int, body: String): SfApiException {
        val json = runCatching { JsonParser.parseString(body) }.getOrNull()
        val first = when {
            json == null -> null
            json.isJsonArray -> json.asJsonArray.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            json.isJsonObject -> json.asJsonObject
            else -> null
        }
        val message = first?.str("message") ?: SOAP_FAULT.find(body)?.groupValues?.get(1) ?: body.take(500).ifBlank { "HTTP $status" }
        return SfApiException(message, status, first?.str("errorCode"))
    }

    private fun parse(body: String): JsonElement? = if (body.isBlank()) null else JsonParser.parseString(body)

    companion object {
        const val DEFAULT_API_VERSION = "64.0"
        private val SOAP_FAULT = Regex("<faultstring>(.*?)</faultstring>", RegexOption.DOT_MATCHES_ALL)

        fun getInstance(project: Project): SfApi = project.service()

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        fun usableToken(token: String?): String? =
            token?.trim()?.takeIf {
                it.isNotEmpty() && !it.startsWith("[") && !it.contains("REDACTED", ignoreCase = true) && it.none(Char::isWhitespace)
            }

        fun escapeSoql(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

        fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
            pairs.forEach { (key, value) ->
                when (value) {
                    null -> add(key, JsonNull.INSTANCE)
                    is Boolean -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is JsonElement -> add(key, value)
                    else -> addProperty(key, value.toString())
                }
            }
        }

        fun array(values: List<JsonElement>): JsonArray = JsonArray().apply { values.forEach { add(it) } }
    }
}
