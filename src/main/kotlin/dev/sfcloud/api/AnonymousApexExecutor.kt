package dev.sfcloud.api

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.sfcloud.log.LogCategoryKey
import dev.sfcloud.log.LogLevels
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class AnonymousApexResult(
    val compiled: Boolean,
    val success: Boolean,
    val line: Int?,
    val column: Int?,
    val compileProblem: String?,
    val exceptionMessage: String?,
    val exceptionStackTrace: String?,
    val log: String?,
)

class AnonymousApexExecutor(private val project: Project) {
    fun execute(org: String?, body: String, levels: LogLevels, indicator: ProgressIndicator? = null): AnonymousApexResult {
        if (body.length > MAX_LENGTH) {
            throw SfApiException("Exceeds the maximum allowed anonymous Apex script length: ${body.length} > $MAX_LENGTH.")
        }
        indicator?.text2 = "Initializing the Apex API"
        val api = SfApi.getInstance(project)
        api.session(org, indicator)
        indicator?.text2 = "Executing anonymous Apex"
        val response = api.soap(org, "/services/Soap/s/{version}", { session -> envelope(session.accessToken, body, levels) }, indicator)
        indicator?.text2 = "Retrieving log"
        return parse(response)
    }

    companion object {
        const val MAX_LENGTH = 32000

        fun envelope(sessionId: String, body: String, levels: LogLevels): String {
            val categories = LogCategoryKey.entries.filter { it.soapCategory != null }.joinToString("") { key ->
                "<apex:categories><apex:category>${key.soapCategory}</apex:category>" +
                    "<apex:level>${levels[key].soapName}</apex:level></apex:categories>"
            }
            return """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:apex="http://soap.sforce.com/2006/08/apex">""" +
                "<soapenv:Header>" +
                "<apex:DebuggingHeader>$categories<apex:debugLevel>NONE</apex:debugLevel></apex:DebuggingHeader>" +
                "<apex:SessionHeader><apex:sessionId>${StringUtil.escapeXmlEntities(sessionId)}</apex:sessionId></apex:SessionHeader>" +
                "</soapenv:Header>" +
                "<soapenv:Body><apex:executeAnonymous><apex:String>${StringUtil.escapeXmlEntities(body)}</apex:String></apex:executeAnonymous></soapenv:Body>" +
                "</soapenv:Envelope>"
        }

        fun parse(xml: String): AnonymousApexResult {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val result = document.getElementsByTagNameNS("*", "result").item(0) as? Element
                ?: throw SfApiException("Unexpected executeAnonymous response")
            fun text(parent: Element?, name: String): String? {
                val node = parent?.getElementsByTagNameNS("*", name)?.item(0) as? Element ?: return null
                if (node.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "nil") == "true") return null
                return node.textContent
            }
            val info = document.getElementsByTagNameNS("*", "DebuggingInfo").item(0) as? Element
            return AnonymousApexResult(
                compiled = text(result, "compiled").toBoolean(),
                success = text(result, "success").toBoolean(),
                line = text(result, "line")?.toIntOrNull()?.takeIf { it >= 0 },
                column = text(result, "column")?.toIntOrNull()?.takeIf { it >= 0 },
                compileProblem = text(result, "compileProblem"),
                exceptionMessage = text(result, "exceptionMessage"),
                exceptionStackTrace = text(result, "exceptionStackTrace"),
                log = text(info, "debugLog"),
            )
        }
    }
}
