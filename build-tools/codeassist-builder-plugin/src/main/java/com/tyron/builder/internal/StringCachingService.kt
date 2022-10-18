package com.tyron.builder.internal

/**
 * A service that can cache strings so that there are no duplicate.
 *
 * Should be used in place of [String.intern] which has performance issues
 */
interface StringCachingService {

    /**
     * Returns a cached version of the string.
     */
    fun cacheString(string: String): String
}

fun StringCachingService?.cacheString(string: String): String = this?.cacheString(string) ?: string