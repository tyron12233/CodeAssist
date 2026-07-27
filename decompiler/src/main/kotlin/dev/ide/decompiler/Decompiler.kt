package dev.ide.decompiler

import dev.ide.lang.kotlin.symbols.KotlinMetadata
import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.ClassFileSource
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Reads a compiled class off [classpath] and produces a decompiled view: full-body **Java** via CFR, or (for
 * a `@kotlin.Metadata` class) a top-level **Kotlin stub** (see [KotlinStub]). Locating a class handles jars,
 * class dirs, and exploded-AAR `classes.jar`, plus nested-type `$` names. CFR pulls classes on demand through
 * an injected [ClassFileSource] backed by [classpath] (no temp files), so inner classes resolve automatically;
 * a Kotlin multi-file facade (`CollectionsKt`) is expanded into its part classes. Any failure returns null so
 * the caller can fall back. Chosen over Fernflower/Vineflower because those crash on ART (their plugin loader
 * calls `Class.getProtectionDomain()`, which is null on Android); CFR is self-contained and runs on-device.
 */
class Decompiler(private val classpath: List<Path>) {

    /** True when [fqn] is a Kotlin class (carries `@kotlin.Metadata`) — decides the natural decompile language. */
    fun isKotlin(fqn: String): Boolean = locate(fqn)?.let { KotlinMetadata.isKotlin(it.bytes) } ?: false

    /** A declaration-only Kotlin stub for [fqn], or null (not on the classpath / not Kotlin / decode failed).
     *  A multi-file facade (`CollectionsKt`) has no members of its own — merge the top-level declarations of
     *  its part classes so `listOf`/`map`/… actually show. */
    fun kotlinStub(fqn: String): String? {
        val loc = locate(fqn) ?: return null
        KotlinMetadata.multifileFacadeParts(loc.bytes)?.let { parts ->
            val members = parts.flatMap { part ->
                readClass(loc.container, "$part.class")
                    ?.let { runCatching { KotlinMetadata.decode(it, null) }.getOrNull() }
                    ?.let { it.topLevel + it.extensions } ?: emptyList()
            }
            if (members.isNotEmpty()) return runCatching { KotlinStub.renderFacade(fqn, members) }.getOrNull()
        }
        val decoded = runCatching { KotlinMetadata.decode(loc.bytes, null) }.getOrNull() ?: return null
        return runCatching { KotlinStub.render(fqn, decoded) }.getOrNull()
    }

    /** Full-body decompiled Java for [fqn] (its top-level class + inners), or null on failure. A multi-file
     *  facade decompiles every PART class (the facade itself is an empty forwarder), joined. */
    fun javaSource(fqn: String): String? {
        val loc = locate(fqn) ?: return null
        val topLevel = loc.internalName.substringBefore('$')
        val parts = KotlinMetadata.multifileFacadeParts(loc.bytes)?.takeIf { it.isNotEmpty() }
        val entries = (parts ?: listOf(topLevel)).map { "$it.class" }
        return runCatching { decompile(entries) }.getOrNull()?.ifBlank { null }
    }

    // --- class location -----------------------------------------------------

    private class Located(val container: Path, val internalName: String, val bytes: ByteArray)

    private fun locate(fqn: String): Located? {
        for (candidate in internalCandidates(fqn)) {
            for (container in classpath) {
                readClass(container, "$candidate.class")?.let { return Located(container, candidate, it) }
            }
        }
        return null
    }

    /** `com.foo.Bar.Inner` → `com/foo/Bar/Inner`, then `com/foo/Bar$Inner`, … (nested types); first match wins. */
    private fun internalCandidates(fqn: String): List<String> {
        val base = fqn.replace('.', '/')
        val out = arrayListOf(base)
        var s = base
        while ('/' in s) {
            val i = s.lastIndexOf('/')
            s = s.substring(0, i) + "$" + s.substring(i + 1)
            out += s
        }
        return out
    }

    private fun readClass(container: Path, entry: String): ByteArray? = runCatching {
        when {
            Files.isDirectory(container) -> container.resolve(entry).takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
            Files.isRegularFile(container) -> ZipFile(container.toFile()).use { zf -> zf.getEntry(entry)?.let { e -> zf.getInputStream(e).use { it.readBytes() } } }
            else -> null
        }
    }.getOrNull()

    // --- CFR ----------------------------------------------------------------

    /** Decompile the given class [entries] (`foo/Bar.class`) with CFR, serving bytecode + on-demand inner/part
     *  classes from [classpath], and return the concatenated Java. */
    private fun decompile(entries: List<String>): String {
        val out = StringBuilder()
        val source = object : ClassFileSource {
            override fun informAnalysisRelativePathDetail(usePath: String?, classFilePath: String?) {}
            override fun getPossiblyRenamedPath(path: String): String = path
            override fun addJar(jarPath: String?): Collection<String> = emptyList()
            override fun getClassFileContent(path: String): Pair<ByteArray, String> {
                val bytes = classpath.firstNotNullOfOrNull { readClass(it, path) }
                    ?: throw java.io.IOException("class not on classpath: $path")
                return Pair.make(bytes, path)
            }
        }
        val sink = object : OutputSinkFactory {
            override fun getSupportedSinks(
                type: OutputSinkFactory.SinkType, available: Collection<OutputSinkFactory.SinkClass>
            ): List<OutputSinkFactory.SinkClass> =
                if (type == OutputSinkFactory.SinkType.JAVA && OutputSinkFactory.SinkClass.STRING in available)
                    listOf(OutputSinkFactory.SinkClass.STRING) else emptyList()

            override fun <T> getSink(
                type: OutputSinkFactory.SinkType, sinkClass: OutputSinkFactory.SinkClass
            ): OutputSinkFactory.Sink<T> = object : OutputSinkFactory.Sink<T> {
                override fun write(value: T) {
                    if (type == OutputSinkFactory.SinkType.JAVA) out.append(value.toString())
                }
            }
        }
        CfrDriver.Builder()
            .withClassFileSource(source)
            .withOutputSink(sink)
            .withOptions(mapOf("comments" to "false"))
            .build()
            .analyse(entries)
        val text = out.toString().trim()
        return if (text.isEmpty()) text else "// Decompiled with CFR — read-only.\n$text"
    }
}
