package dev.ide.analytics.impl

import dev.ide.analytics.AnalyticsSink
import dev.ide.analytics.EventBatch
import java.net.HttpURLConnection
import java.net.URL

/**
 * The default [AnalyticsSink]: insert a batch of events into a Supabase table via PostgREST.
 *
 * POSTs a JSON array (one object per event) to `{url}/rest/v1/{table}` with the **publishable** key in both
 * the `apikey` and `Authorization: Bearer` headers. The key is safe to ship in an open-source client because
 * Row-Level Security on the table allows INSERT only — see docs/analytics.md for the SQL. `Prefer:
 * return=minimal` skips the echo body. Uses `java.net.HttpURLConnection` (JVM + ART, no extra dependency),
 * mirroring the dependency resolver's fetcher.
 *
 * Retry policy via the boolean return: 2xx → accepted (drop); 429 / 5xx / I/O failure → not accepted (the
 * service keeps the batch and retries later); other 4xx (auth/shape/validation) → treated as accepted-drop,
 * because retrying a client error never succeeds and would pin the buffer forever.
 */
class SupabaseSink(
    url: String,
    private val apiKey: String,
    table: String = "events",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : AnalyticsSink {

    private val endpoint = "${url.trimEnd('/')}/rest/v1/$table"
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun send(batch: EventBatch): Boolean {
        if (!configured || batch.events.isEmpty()) return true // nothing to do / not wired → don't retain
        val body = batch.events.joinToString(",", "[", "]") { e ->
            Json.obj(
                listOf(
                    "install_id" to batch.installId,
                    "session_id" to batch.sessionId,
                    "event" to e.name,
                    "category" to e.category.name.lowercase(),
                    "app_version" to batch.device.appVersion,
                    "app_build" to batch.device.appBuild,
                    "os_api" to batch.device.osApi,
                    "device_model" to batch.device.deviceModel,
                    "device_manufacturer" to batch.device.deviceManufacturer,
                    "abi" to batch.device.abi,
                    "locale" to batch.device.locale,
                    "props" to e.props, // jsonb column
                )
            )
        }
        return try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Prefer", "return=minimal")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // Drain so the socket returns to the keep-alive pool (never disconnect()).
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            when {
                code in 200..299 -> true
                code == 429 || code >= 500 -> false // transient → retry later
                else -> true // 4xx client error → won't fix by retrying; drop
            }
        } catch (_: Exception) {
            false // network/I/O failure → keep the batch, retry on the next tick
        }
    }
}
