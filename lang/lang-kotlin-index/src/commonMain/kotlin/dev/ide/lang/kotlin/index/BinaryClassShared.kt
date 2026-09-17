package dev.ide.lang.kotlin.index

import dev.ide.index.IndexInput
import dev.ide.kotlin.classfile.ClassFile
import dev.ide.lang.kotlin.symbols.KotlinMetadata

/**
 * The parsed [ClassFile] of a binary [input], read ONCE per class and SHARED across every binary index that
 * needs it (`kotlin.typeShape`/`kotlin.callables`/`kotlin.pkgDecls`, `subtype.binary`, `annotation.binary`,
 * and the `java.*` family — all keyed on [IndexInput.CLASS_FILE]) via [IndexInput.shared]. A library
 * `.class` was previously fed to a fresh reader per index — ≈6 constant-pool parses of every `android.jar`
 * class (the bulk of a cold build); now each consumer reads the ONE shared parse. A `null` (unreadable
 * bytecode) is cached too.
 *
 * It is a `:kotlin-classfile` parse rather than an ASM one, which is what lets the decoding above it live in
 * `commonMain`. The sharing is unchanged: same one-per-class contract, same cross-family key.
 */
internal fun sharedClassFile(input: IndexInput): ClassFile? =
    input.shared(IndexInput.CLASS_FILE) {
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return@shared null
        ClassFile.read(bytes)
    }

/**
 * The decoded `@kotlin.Metadata` of a binary [input], decoded ONCE per class and SHARED across the `kotlin.*`
 * binary indexes that need it — [KotlinTypeShapeIndex] (`kotlin.typeShape`), [KotlinCallableIndex]
 * (`kotlin.callables`), [KotlinPackageDeclIndex] (`kotlin.pkgDecls`) — via [IndexInput.shared]. A library
 * `.class` was previously `KotlinMetadata.decode`d once PER index (three passes over `android.jar`'s classes,
 * each yielding null for a plain Java type). The decode reuses the [sharedClassFile], and the result —
 * including a `null` for a non-Kotlin class or a failed decode — is cached on the input, so the extensions in
 * one pass reuse a single decode.
 */
internal fun sharedMetadata(input: IndexInput): KotlinMetadata.Decoded? =
    input.shared("kotlin.metadata") {
        val classFile = sharedClassFile(input) ?: return@shared null
        runCatching { KotlinMetadata.decode(classFile, null) }.getOrNull()
    }
