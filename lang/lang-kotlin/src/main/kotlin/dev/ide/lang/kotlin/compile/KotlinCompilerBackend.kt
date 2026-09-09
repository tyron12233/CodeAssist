package dev.ide.lang.kotlin.compile

import java.nio.file.Path

/**
 * Everything one `compileKotlin` invocation needs, as a single value.
 *
 * The parameter list used to be spread across [KotlinJvmCompiler.compile]'s signature. It is a value class
 * here because a compile can now cross a process boundary: the on-device host runs kotlinc in a persistent
 * forked VM, and a request has to be written to disk, read back, and replayed there identically. Anything
 * added to a compile belongs in this type, or the forked path silently drops it.
 *
 * Every member is a path or a plain string, so the request carries no live objects and reconstructs exactly.
 */
data class KotlinCompileRequest(
    val kotlinSources: List<Path>,
    val javaSources: List<Path> = emptyList(),
    val classpath: List<Path> = emptyList(),
    val outputDir: Path,
    val jvmTarget: String = "17",
    /** The platform library: `android.jar` on ART (compiled with `-no-jdk`), empty on a desktop JDK. */
    val bootClasspath: List<Path> = emptyList(),
    /** Grants same-module `internal` visibility across the source/binary split of an incremental compile. */
    val friendPaths: List<Path> = emptyList(),
    /** kotlinc compiler-plugin jars for `-Xplugin`. */
    val compilerPlugins: List<Path> = emptyList(),
    /** `plugin:<id>:<key>=<value>` strings for `-P`. */
    val pluginOptions: List<String> = emptyList(),
    /** Classpaths of plugins loaded and registered programmatically, one list per plugin. */
    val runtimePluginClasspaths: List<List<Path>> = emptyList(),
)

/**
 * The outcome of one compile.
 *
 * [outputs] maps each compiled source to the `.class` files it produced (from `-Xreport-output-files`; empty
 * when the compile threw before reporting). [IncrementalKotlinCompiler] uses it to know which outputs a
 * changed source owns, so a class a source no longer produces can be detected and pruned, and per-class ABI
 * diffed.
 */
data class KotlinCompileResult(
    val success: Boolean,
    val messages: List<String>,
    val outputs: Map<Path, List<Path>> = emptyMap(),
)

/**
 * Where Kotlin-to-`.class` codegen actually runs.
 *
 * [KotlinJvmCompiler] is the in-process implementation and the only one on the desktop. On device the host
 * may substitute one that forwards to a persistent forked VM, whose heap is not bound by the app's
 * `dalvik.vm.heapsize` cap; it is registered as the `platform.kotlinCompilerBackend` port and resolved in
 * place of the in-process compiler. Both are driven by [IncrementalKotlinCompiler], which is unaware of the
 * difference.
 *
 * An implementation must be safe to call from several build threads at once: independent modules compile in
 * parallel, and one app-scoped backend serves all of them.
 */
interface KotlinCompilerBackend {

    fun compile(request: KotlinCompileRequest): KotlinCompileResult

    /**
     * Pay the compiler's one-time cold-start cost now, off the interaction path, so the user's first real
     * build compile is warm. Idempotent, and failure is not reported: this is an optimization, not a build
     * step. [bootClasspath] should be the platform library the real build uses, so the warm-up exercises the
     * same `-no-jdk`/boot path.
     */
    fun warmUp(bootClasspath: List<Path> = emptyList()) {}

    /** Release whatever the backend holds open (a forked VM and its heap). Compiling afterwards must still
     *  work, re-acquiring what it needs. */
    fun close() {}
}
