package dev.ide.android.support.gms

import dev.ide.android.support.AndroidVariant
import java.nio.file.Files
import java.nio.file.Path

/**
 * The on-device equivalent of the `com.google.gms.google-services` Gradle plugin's `GoogleServicesTask`:
 * read `google-services.json`, find the client matching the module's `applicationId`, and emit the string
 * resources Firebase/Play Services read at runtime (`google_app_id`, `gcm_defaultSenderId`, `project_id`,
 * the API key, etc.). Without these, FirebaseApp initialization throws at startup ("Missing google_app_id").
 *
 * Pure JSON→resource transformation (no Android types), so it is unit-testable without an SDK.
 */
object GoogleServices {

    /** A generated `<string name=.. translatable="false">value</string>`. */
    data class StringResource(val name: String, val value: String)

    sealed interface Outcome {
        /** Resources to write, plus any non-fatal notes. */
        data class Success(val resources: List<StringResource>, val matchedPackage: String, val messages: List<String>) : Outcome
        data class Failure(val message: String) : Outcome
    }

    /**
     * Locate `google-services.json` the way the plugin does: prefer the most variant-specific source dir,
     * falling back to the module root. [moduleDir] is the module directory; [variant] supplies the build
     * type + flavor names that name the candidate `src/<...>/` directories.
     */
    fun findJson(moduleDir: Path, variant: AndroidVariant): Path? {
        val names = buildList {
            // Most specific first: src/<flavorBuildType>, src/<flavor>, src/<buildType>, then the module root.
            add("src/${variant.name}")
            variant.flavorNames.forEach { add("src/$it") }
            add("src/${variant.buildTypeName}")
            add("")
        }
        return names.map { rel -> if (rel.isEmpty()) moduleDir.resolve("google-services.json") else moduleDir.resolve(rel).resolve("google-services.json") }
            .firstOrNull { Files.isRegularFile(it) }
    }

    /** Parse [jsonText] and produce the resources for [applicationId] (falling back to [fallbackPackage]). */
    fun process(jsonText: String, applicationId: String, fallbackPackage: String): Outcome {
        val root = runCatching { MiniJson.parse(jsonText) }.getOrElse {
            return Outcome.Failure("google-services.json is not valid JSON: ${it.message}")
        }
        val projectInfo = root.obj("project_info")
            ?: return Outcome.Failure("google-services.json: missing project_info")
        val clients = root.arr("client")
        if (clients.isEmpty()) return Outcome.Failure("google-services.json: no client entries")

        val messages = ArrayList<String>()
        val client = matchClient(clients, applicationId, fallbackPackage, messages)
            ?: return Outcome.Failure(
                "google-services.json: no client matches applicationId '$applicationId'. " +
                    "Available package names: ${availablePackages(clients).joinToString(", ")}"
            )
        val pkg = client.obj("client_info").obj("android_client_info").str("package_name") ?: applicationId

        val res = ArrayList<StringResource>()
        // Required.
        val appId = client.obj("client_info").str("mobilesdk_app_id")
            ?: return Outcome.Failure("google-services.json: client for '$pkg' has no mobilesdk_app_id")
        res += StringResource("google_app_id", appId)
        val projectId = projectInfo.str("project_id")
            ?: return Outcome.Failure("google-services.json: missing project_info.project_id")
        res += StringResource("project_id", projectId)

        // Optional, emitted only when present (matching the plugin).
        projectInfo.str("project_number")?.let { res += StringResource("gcm_defaultSenderId", it) }
        projectInfo.str("firebase_url")?.let { res += StringResource("firebase_database_url", it) }
        projectInfo.str("storage_bucket")?.let { res += StringResource("google_storage_bucket", it) }

        // api_key[0].current_key → both the api key and the (legacy) crash-reporting key.
        val apiKey = client.arr("api_key").firstNotNullOfOrNull { it.str("current_key") }
        apiKey?.let {
            res += StringResource("google_api_key", it)
            res += StringResource("google_crash_reporting_api_key", it)
        }

        // oauth_client with client_type == 3 (web) → default_web_client_id.
        client.arr("oauth_client").firstOrNull { (it as? Map<*, *>)?.get("client_type")?.asInt() == 3 }
            ?.str("client_id")?.let { res += StringResource("default_web_client_id", it) }

        // Analytics tracking id, if configured.
        client.obj("services")?.obj("analytics_service")?.obj("analytics_property")?.str("tracking_id")
            ?.let { res += StringResource("ga_trackingId", it) }

        return Outcome.Success(res.distinctBy { it.name }, pkg, messages)
    }

    /** Render the resources as a `res/values/values.xml` body. */
    fun valuesXml(resources: List<StringResource>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<resources>\n")
        for (r in resources) {
            append("    <string name=\"").append(r.name).append("\" translatable=\"false\">")
            append(escapeXml(r.value))
            append("</string>\n")
        }
        append("</resources>\n")
    }

    @Suppress("UNCHECKED_CAST")
    private fun matchClient(clients: List<Any?>, applicationId: String, fallback: String, messages: MutableList<String>): Map<String, Any?>? {
        fun pkgOf(c: Any?): String? = (c as? Map<String, Any?>)?.obj("client_info")?.obj("android_client_info").str("package_name")
        // 1) exact applicationId, then 2) the base namespace (handles applicationIdSuffix). No single-client
        // lenience: a non-matching package is a real misconfiguration the plugin also rejects.
        clients.firstOrNull { pkgOf(it) == applicationId }?.let { return it.asMap() }
        clients.firstOrNull { pkgOf(it) == fallback }?.let {
            if (applicationId != fallback) messages += "google-services.json: no client for '$applicationId'; using '$fallback'"
            return it.asMap()
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun availablePackages(clients: List<Any?>): List<String> =
        clients.mapNotNull { (it as? Map<String, Any?>)?.obj("client_info")?.obj("android_client_info").str("package_name") }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
    private fun Any?.asInt(): Int? = when (this) { is Number -> toInt(); is String -> toIntOrNull(); else -> null }
}
