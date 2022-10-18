package com.tyron.kotlin.completion.util

import java.time.Duration
import java.time.Instant

fun <T> logTime(name: String, block: () -> T): T {
    val start = Instant.now()
    val value = block()
    val duration = Duration.between(start, Instant.now())
    println("$name: took ${duration.toMillis()} ms")
    return value
}
