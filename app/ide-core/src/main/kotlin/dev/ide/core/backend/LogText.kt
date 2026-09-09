package dev.ide.core.backend

/** Render a throwable's full stack trace to a string (for the error dialog detail + the logs viewer/export). */
internal fun stackTraceString(t: Throwable): String {
    val sw = java.io.StringWriter()
    t.printStackTrace(java.io.PrintWriter(sw))
    return sw.toString()
}
