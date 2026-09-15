package dev.ide.store.impl.platform

/**
 * The one HTTP call every store transport makes, and the only thing in this module a platform has to
 * supply itself.
 *
 * The services above it (catalog, accounts, submissions, moderation, reviews) are entirely about Supabase:
 * which RPC, what JSON, which status means "offline" rather than "rejected". None of that differs between
 * a phone, a desktop and a simulator, so none of it is written twice. What does differ is the socket, and
 * that is this file: `HttpURLConnection` on the JVM and ART, `NSURLSession` on iOS.
 *
 * **Blocking on purpose.** Every port in store-api is a blocking call that its caller runs on an IO
 * dispatcher, so this mirrors them rather than introducing a second concurrency model halfway down the
 * stack. The iOS actual therefore waits on its own semaphore, which is safe precisely because it is never
 * called on the main queue.
 */
internal class HttpReply(
    /** The status the server answered with, or 0 when the request never reached one. */
    val status: Int,
    /** The response body, or the error body for a non-2xx. Empty for a request whose body went to a file. */
    val body: String,
    /**
     * Why the request never completed, or null when the server answered at all.
     *
     * Separate from a status because the callers treat them differently: no network is the ordinary case
     * on a phone and reads as [dev.ide.store.StoreResult.Unavailable], while a status the server chose is
     * something it decided and may be worth showing.
     */
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && status in 200..299
}

/** What a streamed download produced. [sha256] is hex, lower case, and only computed when asked for. */
internal class DownloadReply(
    val status: Int,
    val bytes: Long,
    val sha256: String?,
    val error: String? = null,
    /** True when the response was longer than the cap the caller set, and the partial file was removed. */
    val tooLarge: Boolean = false,
) {
    val ok: Boolean get() = error == null && status in 200..299 && !tooLarge
}

internal expect class StoreHttp(connectTimeoutMs: Int, readTimeoutMs: Int) {

    /**
     * Send [method] to [url] with [headers] and an optional UTF-8 [body], and read the whole response.
     *
     * Note that PATCH is deliberately never sent by anything above: the desktop JDK's
     * `HttpURLConnection` rejects it outright while Android's OkHttp-backed one accepts it, so a store
     * mutation that worked on a phone reported itself as offline on a desktop. Mutations go through RPCs
     * over POST instead.
     */
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): HttpReply

    /**
     * GET [url] straight to the file at [intoPath], hashing as it goes rather than in a second pass.
     *
     * [limitBytes] is a ceiling on what is written: a body over it leaves no file behind, because a
     * truncated download is worse than none (a half-written image decodes as garbage and a half-written
     * archive fails a checksum it should never have been given the chance to fail). [expectedBytes] is
     * only there to drive [onProgress] when the response carries no length of its own.
     *
     * The destination's parent directory is created. A failure, a cap breach and a caller-side checksum
     * mismatch all delete the file; the caller never has to clean up after this.
     */
    fun download(
        url: String,
        headers: Map<String, String>,
        intoPath: String,
        expectedBytes: Long = 0,
        limitBytes: Long = Long.MAX_VALUE,
        hash: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): DownloadReply

    /**
     * Send the file at [filePath] as the whole request body.
     *
     * Its own method rather than a `ByteArray` on [request] because a submission archive is megabytes and
     * a phone should not hold one in memory twice to upload it.
     */
    fun upload(
        method: String,
        url: String,
        headers: Map<String, String>,
        filePath: String,
        contentType: String,
    ): HttpReply
}
