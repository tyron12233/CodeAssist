package dev.ide.core.backend

import dev.ide.store.LearnRemoteService
import dev.ide.store.StoreResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Which lesson catalog Learn serves: the one compiled into the app, or a newer one published on the server
 * and kept on disk. The higher [LearnCatalogDef.version] wins, so a release that bundles newer lessons than
 * the copy it downloaded earlier serves its own, and vice versa.
 *
 * The cached copy is read once, lazily. [refresh] asks the server for anything newer than what is being
 * served, verifies it (hash, then a full decode), writes it to disk atomically, and swaps it in. Every
 * failure keeps the current catalog: Learn must never get worse because the network did.
 *
 * Progress keys are lesson ids, which stay stable across catalog versions, so a swap never loses progress.
 */
internal class LearnCatalogStore(
    private val bundled: () -> LearnCatalogDef,
    private val remote: LearnRemoteService,
    /** Where the downloaded catalog lives, or null when there is nowhere to keep one (tests, no home dir). */
    private val cacheFile: () -> Path?,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val current = AtomicReference<LearnCatalogDef?>(null)

    @Volatile
    private var refreshedThisSession = false

    /** The catalog to serve now. */
    fun catalog(): LearnCatalogDef {
        current.get()?.let { return it }
        val base = bundled()
        val cached = readCache()
        val pick = if (cached != null && cached.version > base.version) cached else base
        current.compareAndSet(null, pick)
        return current.get() ?: pick
    }

    /**
     * Fetch a newer catalog if the server has one. Runs at most once per session unless [force]; returns
     * true when the served catalog changed. Blocking (network + disk): call off the main thread.
     */
    fun refresh(force: Boolean = false): Boolean {
        if (!remote.learnRemoteAvailable()) return false
        if (refreshedThisSession && !force) return false
        val held = catalog()
        val result = runCatching { remote.latestCatalog(held.version, LearnCatalogCodec.ENVELOPE_FORMAT) }
            .getOrElse { log("learn catalog fetch threw", it); return false }
        val payload = when (result) {
            is StoreResult.Ok -> result.value
            is StoreResult.Unavailable, is StoreResult.Failed -> {
                // Offline or a server error: try again next session rather than hammering it now.
                log("learn catalog fetch: $result", null)
                return false
            }
        }
        refreshedThisSession = true
        if (payload == null || payload.version <= held.version) return false

        if (payload.sha256.isNotBlank() && sha256(payload.body) != payload.sha256.lowercase()) {
            log("learn catalog ${payload.version}: hash mismatch, ignored", null)
            return false
        }
        val decoded = runCatching {
            LearnCatalogCodec.decode(payload.body) { id, why -> log("learn catalog: dropped $id ($why)", null) }
        }.getOrElse { log("learn catalog ${payload.version}: undecodable", it); return false }
        if (decoded.tracks.isEmpty() || decoded.version <= held.version) return false

        writeCache(payload.body)
        current.set(decoded)
        return true
    }

    private fun readCache(): LearnCatalogDef? {
        val file = cacheFile() ?: return null
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            LearnCatalogCodec.decode(String(Files.readAllBytes(file), Charsets.UTF_8))
        }.getOrElse {
            // A cache we cannot read (truncated, or from a future envelope) is worth nothing; drop it so the
            // next refresh starts clean.
            log("learn catalog cache unreadable, deleting", it)
            runCatching { Files.deleteIfExists(file) }
            null
        }
    }

    private fun writeCache(body: String) {
        val file = cacheFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(tmp, body.toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { log("learn catalog cache write failed", it) }
    }

    internal companion object {
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
