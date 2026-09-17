package dev.ide.lang.kotlin.index

import dev.ide.index.IndexInput
import dev.ide.lang.kotlin.symbols.KotlinMetadata
import org.objectweb.asm.ClassReader

/**
 * The raw ASM [ClassReader] of a binary [input], constructed ONCE per class and SHARED across every binary
 * index that parses it (`kotlin.typeShape`/`kotlin.callables`/`kotlin.pkgDecls`, `subtype.binary`,
 * `annotation.binary`, and the `java.*` family — all keyed on [IndexInput.CLASS_READER]) via
 * [IndexInput.shared]. A library `.class` was previously fed to a fresh `ClassReader` per index — ≈6
 * constant-pool parses of every `android.jar` class (the bulk of a cold build); now each consumer runs its own
 * visitor over the ONE shared reader (which ASM lets you `accept` any number of times). A `null` (unreadable
 * bytecode) is cached too.
 */
internal fun sharedClassReader(input: IndexInput): ClassReader? =
    input.shared(IndexInput.CLASS_READER) {
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return@shared null
        runCatching { ClassReader(bytes) }.getOrNull()
    }

/**
 * The decoded `@kotlin.Metadata` of a binary [input], decoded ONCE per class and SHARED across the `kotlin.*`
 * binary indexes that need it — [KotlinTypeShapeIndex] (`kotlin.typeShape`), [KotlinCallableIndex]
 * (`kotlin.callables`), [KotlinPackageDeclIndex] (`kotlin.pkgDecls`) — via [IndexInput.shared]. A library
 * `.class` was previously `KotlinMetadata.decode`d once PER index (three ASM passes over `android.jar`'s ~40k
 * classes, each yielding null for a plain Java type). The decode reuses the [sharedClassReader], and the result
 * — including a `null` for a non-Kotlin class or a failed decode — is cached on the input, so the extensions in
 * one pass reuse a single decode.
 */
internal fun sharedMetadata(input: IndexInput): KotlinMetadata.Decoded? =
    input.shared("kotlin.metadata") {
        val reader = sharedClassReader(input) ?: return@shared null
        runCatching { KotlinMetadata.decode(reader, null) }.getOrNull()
    }
