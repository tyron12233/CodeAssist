package dev.ide.store.impl.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSFileManager
import platform.Foundation.NSError
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.downloadTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.DISPATCH_TIME_FOREVER

/**
 * `NSURLSession`, waited on.
 *
 * The ports above are blocking by contract and their callers already run them off the main thread, so the
 * honest translation of "do the request and give me the answer" is a semaphore rather than a second,
 * parallel async world halfway down the stack. It is safe precisely because nothing calls a store port on
 * the main queue — the UI reaches all of it through `withContext(ioDispatcher)`.
 *
 * Timeouts are the session's own (`timeoutIntervalForRequest` for the response, and per-request for the
 * connection), which is as close as URLSession gets to the connect/read split `HttpURLConnection` has.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class StoreHttp actual constructor(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) {

    private val session: NSURLSession = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            setTimeoutIntervalForRequest(readTimeoutMs / 1000.0)
            setTimeoutIntervalForResource(readTimeoutMs / 1000.0)
            // The store's own cache is on disk and content-addressed; a second, opaque one underneath it
            // would only make a stale feed harder to explain.
            setURLCache(null)
        },
    )

    actual fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpReply {
        val request = newRequest(method, url, headers) ?: return HttpReply(0, "", "Bad URL: $url")
        if (body != null) request.setHTTPBody(body.encodeToByteArray().toNSData())

        var status = 0
        var text = ""
        var failure: String? = null
        val done = dispatch_semaphore_create(0)
        val task: NSURLSessionDataTask = session.dataTaskWithRequest(request) { data, response, error ->
            when {
                error != null -> failure = error.localizedDescription
                else -> {
                    status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    text = data?.toByteArray()?.decodeToString().orEmpty()
                }
            }
            dispatch_semaphore_signal(done)
        }
        task.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        return HttpReply(status, text, failure)
    }

    actual fun download(
        url: String,
        headers: Map<String, String>,
        intoPath: String,
        expectedBytes: Long,
        limitBytes: Long,
        hash: Boolean,
        onProgress: (Float) -> Unit,
    ): DownloadReply {
        val request = newRequest("GET", url, headers)
            ?: return DownloadReply(0, 0, null, "Bad URL: $url")

        var status = 0
        var failure: String? = null
        var bytes: ByteArray? = null
        val done = dispatch_semaphore_create(0)
        // The body is read into memory and then written, rather than streamed through a delegate. The two
        // things this downloads are a store payload and a screenshot, both capped in the low megabytes by
        // the backend's own bucket limits, and a delegate-based session would have to hand its callbacks
        // to a queue this call is already blocking — which is how a download deadlocks itself.
        val task: NSURLSessionDataTask = session.dataTaskWithRequest(request) { data, response, error ->
            when {
                error != null -> failure = error.localizedDescription
                else -> {
                    status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    bytes = data?.toByteArray()
                }
            }
            dispatch_semaphore_signal(done)
        }
        task.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)

        failure?.let {
            StoreFs.delete(intoPath)
            return DownloadReply(0, 0, null, it)
        }
        if (status !in 200..299) return DownloadReply(status, 0, null)

        val payload = bytes ?: ByteArray(0)
        if (payload.size.toLong() > limitBytes) {
            StoreFs.delete(intoPath)
            return DownloadReply(status, payload.size.toLong(), null, tooLarge = true)
        }
        StoreFs.mkdirsForFile(intoPath)
        if (!StoreFs.writeBytes(intoPath, payload)) {
            return DownloadReply(status, 0, null, "Could not write $intoPath")
        }
        onProgress(1f)
        return DownloadReply(status, payload.size.toLong(), if (hash) sha256Hex(payload) else null)
    }

    actual fun upload(
        method: String,
        url: String,
        headers: Map<String, String>,
        filePath: String,
        contentType: String,
    ): HttpReply {
        val bytes = StoreFs.readBytes(filePath) ?: return HttpReply(0, "", "Could not read $filePath")
        val request = newRequest(method, url, headers) ?: return HttpReply(0, "", "Bad URL: $url")
        request.setValue(contentType, forHTTPHeaderField = "Content-Type")
        request.setHTTPBody(bytes.toNSData())

        var status = 0
        var text = ""
        var failure: String? = null
        val done = dispatch_semaphore_create(0)
        val task = session.dataTaskWithRequest(request) { data, response, error ->
            when {
                error != null -> failure = error.localizedDescription
                else -> {
                    status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    text = data?.toByteArray()?.decodeToString().orEmpty()
                }
            }
            dispatch_semaphore_signal(done)
        }
        task.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        return HttpReply(status, text, failure)
    }

    private fun newRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): NSMutableURLRequest? {
        val nsUrl = NSURL.URLWithString(url) ?: return null
        val request = NSMutableURLRequest.requestWithURL(nsUrl) as NSMutableURLRequest
        request.setHTTPMethod(method)
        request.setTimeoutInterval(connectTimeoutMs / 1000.0)
        headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
        return request
    }
}
