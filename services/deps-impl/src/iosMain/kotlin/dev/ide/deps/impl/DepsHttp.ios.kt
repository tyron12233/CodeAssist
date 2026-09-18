package dev.ide.deps.impl

import dev.ide.platform.fileInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.downloadTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy

/**
 * `NSURLSession`, waited on.
 *
 * The resolver's fetches are blocking by contract and it already runs them on an IO dispatcher behind a
 * bounded semaphore, so the honest translation of "do the request and give me the answer" is a semaphore
 * rather than a second, parallel async world halfway down the stack. Nothing here is ever called on the
 * main queue.
 *
 * Redirects are the session's own: unlike the JDK, URLSession follows a protocol-changing redirect without
 * being asked, so there is nothing to reimplement.
 */
@OptIn(ExperimentalForeignApi::class)
actual class DepsHttp actual constructor(
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    private val userAgent: String,
) {

    private val session: NSURLSession = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            setTimeoutIntervalForRequest(connectTimeoutMs / 1000.0)
            setTimeoutIntervalForResource(readTimeoutMs / 1000.0)
            // The resolver's cache is the Maven-layout directory on disk and IS the offline repository; a
            // second, opaque cache underneath it would only make a stale artifact harder to explain.
            setURLCache(null)
        },
    )

    actual fun get(url: String): HttpReply {
        val request = newRequest(url) ?: return HttpReply(status = 0, error = "Bad URL: $url")
        var status = 0
        var body: ByteArray? = null
        var failure: String? = null
        val done = dispatch_semaphore_create(0)
        val task: NSURLSessionDataTask = session.dataTaskWithRequest(request) { data, response, error ->
            when {
                error != null -> failure = error.localizedDescription
                else -> {
                    status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    body = data?.toByteArray()
                }
            }
            dispatch_semaphore_signal(done)
        }
        task.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        return if (failure != null) HttpReply(status = 0, error = failure) else HttpReply(status, body)
    }

    /**
     * A DOWNLOAD task, not a data task: it writes the body to a file of its own and hands back the path, so
     * a 30 MB jar never becomes a 30 MB `NSData`. That is the whole reason this method exists separately,
     * and it is why the delegate-free completion-handler form is the right one — a delegate's callbacks
     * would have to run on a queue this call is already blocking, which is how a download deadlocks itself.
     *
     * The cost is that progress cannot be reported as it flows: without a delegate there is nothing to
     * observe, so [onProgress] is called once, with the finished size. A caller driving a bar gets one step
     * rather than a sweep. Streaming progress needs the delegate form, and that needs this call to stop
     * being blocking first.
     */
    actual fun download(
        url: String,
        dest: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): HttpReply {
        val request = newRequest(url) ?: return HttpReply(status = 0, error = "Bad URL: $url")
        var status = 0
        var failure: String? = null
        var temporary: String? = null
        val done = dispatch_semaphore_create(0)
        val task: NSURLSessionDownloadTask = session.downloadTaskWithRequest(request) { location, response, error ->
            when {
                error != null -> failure = error.localizedDescription
                else -> {
                    status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    // URLSession deletes this the moment the handler returns, so it is MOVED here, inside
                    // the handler, rather than remembered and moved after the wait.
                    if (status in 200..299) temporary = location?.path?.let { moveInto(it, dest) }
                }
            }
            dispatch_semaphore_signal(done)
        }
        task.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)

        failure?.let { return HttpReply(status = 0, error = it) }
        if (status !in 200..299) return HttpReply(status)
        if (temporary == null) return HttpReply(status = 0, error = "could not write $dest")
        val size = fileInfo(dest)?.size ?: 0L
        onProgress(size, size)
        return HttpReply(status)
    }

    /** Move the session's temporary file over [dest], replacing anything there. Null when it could not. */
    private fun moveInto(from: String, dest: String): String? {
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath(dest, null)
        return if (fm.moveItemAtPath(from, dest, null)) dest else null
    }

    private fun newRequest(url: String): NSMutableURLRequest? {
        val target = NSURL.URLWithString(url) ?: return null
        return NSMutableURLRequest.requestWithURL(target).apply {
            setHTTPMethod("GET")
            setValue(userAgent, forHTTPHeaderField = "User-Agent")
            setValue("*/*", forHTTPHeaderField = "Accept")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
