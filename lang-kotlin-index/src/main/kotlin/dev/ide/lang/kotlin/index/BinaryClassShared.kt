package dev.ide.lang.kotlin.index

import dev.ide.index.IndexInput
import dev.ide.lang.kotlin.symbols.KotlinMetadata

/**
 * The decoded `@kotlin.Metadata` of a binary [input], decoded ONCE per class and SHARED across the `kotlin.*`
 * binary indexes that need it — [KotlinTypeShapeIndex] (`kotlin.typeShape`), [KotlinCallableIndex]
 * (`kotlin.callables`), [KotlinPackageDeclIndex] (`kotlin.pkgDecls`) — via [IndexInput.shared]. A library
 * `.class` was previously `KotlinMetadata.decode`d once PER index (three ASM passes over `android.jar`'s ~40k
 * classes, each yielding null for a plain Java type). The result — including a `null` for a non-Kotlin class or
 * a failed decode — is cached on the input, so the extensions in one pass reuse a single decode.
 */
internal fun sharedMetadata(input: IndexInput): KotlinMetadata.Decoded? =
    input.shared("kotlin.metadata") {
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return@shared null
        runCatching { KotlinMetadata.decode(bytes, null) }.getOrNull()
    }
