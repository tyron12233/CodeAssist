package dev.ide.lang.kotlin.interp

// Analysis on iOS runs on the one analysis thread (see IosKotlinAnalysis), so lowering's caches are never
// shared across threads there.
internal actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = HashMap()
