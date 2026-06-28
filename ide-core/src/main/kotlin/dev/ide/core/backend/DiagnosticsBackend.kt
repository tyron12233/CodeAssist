package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogRecord
import dev.ide.ui.backend.DiagnosticsService
import dev.ide.ui.backend.UiError
import dev.ide.ui.backend.UiLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * [DiagnosticsService]: the in-app logs viewer plus the cross-cutting error/analytics surface. The error
 * dialog state and analytics consent live on the aggregator (they are bound to its lifecycle — the LogSink,
 * crash reporter, and consent prefs), so those members delegate to [BackendContext]; only the logs are owned
 * here.
 */
internal class DiagnosticsBackend(private val ctx: BackendContext) : DiagnosticsService {

    override val errorEvents: StateFlow<UiError?> get() = ctx.errorEvents
    override fun dismissError(id: Int) = ctx.dismissError(id)

    override fun analyticsAvailable(): Boolean = ctx.analyticsAvailable()
    override fun analyticsConsent(): Boolean? = ctx.analyticsConsent()
    override fun setAnalyticsConsent(granted: Boolean) = ctx.setAnalyticsConsent(granted)
    override fun track(event: String, props: Map<String, String>) = ctx.track(event, props)

    override fun recentLogs(): List<UiLogEntry> = Log.recent().map { r ->
        UiLogEntry(
            level = r.level.name,
            tag = r.tag,
            message = r.message,
            timestampMs = r.timestampMs,
            timeLabel = logTimeLabel(r.timestampMs),
            thread = r.threadName,
            stackTrace = r.throwable?.let { stackTraceString(it) },
        )
    }

    override suspend fun exportLogs(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = ctx.manager?.storageRoot ?: ctx.servicesOrNull?.workspaceRoot ?: return@runCatching null
            Files.createDirectories(dir)
            val file = dir.resolve("codeassist-log-${System.currentTimeMillis()}.txt")
            file.toFile().writeText(renderLogs(Log.recent()))
            file.toString()
        }.getOrNull()
    }

    /** Local time-of-day label (HH:mm:ss.SSS) for a log record's epoch-millis timestamp. */
    private fun logTimeLabel(ms: Long): String = runCatching {
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
    }.getOrDefault(ms.toString())

    /** Render log records as a plain-text dump (one record per block, stack traces inline) for export/copy. */
    private fun renderLogs(records: List<LogRecord>): String = buildString {
        append("CodeAssist log — ${records.size} records\n")
        append("exported ${runCatching { java.time.LocalDateTime.now().withNano(0) }.getOrDefault("")}\n\n")
        for (r in records) {
            val time = runCatching { java.time.Instant.ofEpochMilli(r.timestampMs).toString() }.getOrDefault(r.timestampMs.toString())
            append("$time [${r.level}] ${r.tag} (${r.threadName}): ${r.message}\n")
            r.throwable?.let { append(stackTraceString(it)).append('\n') }
        }
    }
}
