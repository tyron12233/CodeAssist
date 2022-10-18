package com.tyron.builder.ide.common.workers

import java.io.PrintWriter

/**
 * An exception that wraps multiple worker tasks exceptions.
 */
class WorkerExecutorException : RuntimeException {
    override val cause: Throwable? get() = causes.firstOrNull()

    val causes: List<Throwable>

    companion object {
        private fun getMessage(causes: List<Throwable>): String {
            var message =
                causes.size.toString() +
                        (if (causes.size == 1) " exception was" else " exceptions were") +
                        " raised by workers:\n"
            causes.forEach { message += it.message + "\n" }
            return message
        }
    }

    constructor(causes: Iterable<Throwable>) : this(getMessage(causes.toList()), causes)

    constructor(message: String, causes: Iterable<Throwable>) : super(message) {
        this.causes = causes.toList()
    }

    override fun printStackTrace(writer: PrintWriter) {
        for ((i, cause) in causes.withIndex()) {
            writer.format("Cause %d: ", i + 1)
            cause.printStackTrace(writer)
        }
    }
}
