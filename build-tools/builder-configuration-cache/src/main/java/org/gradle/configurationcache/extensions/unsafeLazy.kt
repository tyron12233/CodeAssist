package org.gradle.configurationcache.extensions
/**
 * Thread unsafe version of [lazy].
 *
 * @see LazyThreadSafetyMode.NONE
 */
internal
fun <T> unsafeLazy(initializer: () -> T): Lazy<T> = lazy(LazyThreadSafetyMode.NONE, initializer)
