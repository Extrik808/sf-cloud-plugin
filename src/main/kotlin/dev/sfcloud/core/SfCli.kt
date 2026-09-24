package dev.sfcloud.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import dev.sfcloud.settings.SfCloudSettings
import java.io.File
import java.nio.charset.StandardCharsets

class SfCliException(message: String) : Exception(message)

data class SfResult(
    val exitCode: Int,
    val json: JsonObject?,
    val stdout: String,
    val stderr: String,
) {
    val success: Boolean get() = exitCode == 0 && (json?.int("status") ?: 0) == 0

    val payload: JsonElement?
        get() = json?.get("result")?.takeUnless { it.isJsonNull } ?: json?.get("data")?.takeUnless { it.isJsonNull }

    val message: String
        get() = json?.str("message")
            ?: stderr.lineSequence().firstOrNull { it.isNotBlank() }
            ?: stdout.lineSequence().firstOrNull { it.isNotBlank() }
            ?: "sf exited with code $exitCode"
}

object SfCli {
    private val fallbackLocations = listOf(
        "/usr/local/bin/sf",
        "/opt/homebrew/bin/sf",
        "${System.getProperty("user.home")}/.local/bin/sf",
        "${System.getProperty("user.home")}/.npm-global/bin/sf",
    )

    fun executable(): String {
        val configured = SfCloudSettings.getInstance().state.sfPath.orEmpty().trim()
        if (configured.isNotEmpty()) return configured
        PathEnvironmentVariableUtil.findInPath("sf")?.let { return it.absolutePath }
        fallbackLocations.firstOrNull { File(it).canExecute() }?.let { return it }
        return "sf"
    }

    fun commandLine(project: Project?, args: List<String>, environment: Map<String, String> = emptyMap()): GeneralCommandLine =
        GeneralCommandLine(listOf(executable()) + args)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(
                mapOf(
                    "SF_DISABLE_COLORS" to "true",
                    "FORCE_COLOR" to "0",
                    "SF_AUTOUPDATE_DISABLE" to "true",
                    "SF_SKIP_NEW_VERSION_CHECK" to "true",
                    "SF_DISABLE_TELEMETRY" to "true",
                ) + environment,
            )
            .apply { project?.guessProjectDir()?.path?.takeIf { File(it).isDirectory }?.let { withWorkDirectory(it) } }

    fun run(
        project: Project?,
        args: List<String>,
        timeoutMs: Int = 10 * 60 * 1000,
        indicator: ProgressIndicator? = null,
        environment: Map<String, String> = emptyMap(),
    ): SfResult {
        val fullArgs = if ("--json" in args) args else args + "--json"
        val handler = try {
            CapturingProcessHandler(commandLine(project, fullArgs, environment))
        } catch (e: Exception) {
            throw SfCliException("Cannot start Salesforce CLI (${executable()}): ${e.message}. Set the path in Settings | Tools | SF Cloud.")
        }
        val output: ProcessOutput = if (indicator != null) {
            handler.runProcessWithProgressIndicator(indicator, timeoutMs)
        } else {
            handler.runProcess(timeoutMs)
        }
        if (output.isCancelled) throw SfCliException("Cancelled")
        if (output.isTimeout) throw SfCliException("sf ${args.firstOrNull().orEmpty()} timed out")
        return SfResult(output.exitCode, parseJson(output.stdout) ?: parseJson(output.stderr), output.stdout, output.stderr)
    }

    private fun parseJson(text: String): JsonObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        return try {
            JsonParser.parseString(text.substring(start)).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }
    }
}
