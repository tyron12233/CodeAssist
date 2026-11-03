package dev.ide.store

/**
 * The online half of Learn. Today it delivers the lesson catalog; account progress and lesson discussions
 * join it in later phases.
 *
 * Reads work signed out. Nothing here is required for Learn to work: the app always has its bundled
 * catalog, so every failure degrades to "keep showing what we have".
 */
interface LearnRemoteService {
    /** Whether a backend is configured at all. */
    fun learnRemoteAvailable(): Boolean = false

    /**
     * The newest published catalog above [sinceVersion] that a client reading envelope [format] can parse.
     * `Ok(null)` means the client is already current.
     */
    fun latestCatalog(sinceVersion: Long, format: Int): StoreResult<LearnCatalogPayload?> =
        StoreResult.Unavailable("Learn is not connected in this build")

    companion object {
        val Unsupported: LearnRemoteService = object : LearnRemoteService {}
    }
}

/** A published catalog as the server returned it. [body] is the catalog JSON, untouched. */
data class LearnCatalogPayload(
    val version: Long,
    val format: Int,
    val sha256: String,
    val body: String,
)
