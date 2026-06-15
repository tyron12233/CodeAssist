package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor

/**
 * One ART-compatibility rewrite of a Kotlin-compiler class.
 *
 * The Kotlin compiler (`kotlin-compiler-embeddable`, K2/2.4.0 here) is built for a desktop JVM and uses a
 * handful of APIs that are absent or behave differently on Android's ART runtime — `sun.misc.Unsafe`, the
 * NIO `jar:`/zip `FileSystemProvider`, JDK-internal reflection, class-file versions newer than the device's
 * ART verifier accepts, `MethodHandle`/`invokedynamic` shapes, etc. CodeAssist's original `build-tools/kotlinc`
 * solved this by hand-maintaining patched *source* copies of the broken classes and shadowing them on the
 * classpath. We do it as **surgical ASM rewrites** applied during the Android build instead, so there is no
 * forked source to keep in sync with the compiler version.
 *
 * Each pass declares the classes it touches ([handles]) and returns a [ClassVisitor] that performs the
 * rewrite ([visitor]). Passes are applied by [KotlincArtPatchFactory] via AGP's bytecode-instrumentation
 * API (scope = ALL), so they reach the compiler classes wherever they end up dexed — which means the SAME
 * rewrite also fixes the editor's standalone PSI parse-host (`:lang-kotlin`), since both run the same dexed
 * classes on device.
 *
 * ## Authoring loop (see docs/kotlin-compiler-on-art.md)
 * The set of breakages is discovered empirically, not guessed: run [KotlinCompilerArtSpikeTest] on a real
 * device, read the failing class + cause from logcat (a `LinkageError`/`VerifyError`/`NoClassDefFoundError`/
 * `ExceptionInInitializerError`), then add one pass here targeting exactly that class. Re-run until the
 * sample compiles. Until the first failure is found, [ALL] is intentionally empty and the instrumentation
 * is a no-op (every class reports `isInstrumentable == false`, so AGP routes nothing through us).
 */
interface ArtPatchPass {
    /** A short identifier for logging, e.g. `"unsafe-field-offset"`. */
    val name: String

    /**
     * Whether this pass rewrites the class named [classFqn] (a dotted fully-qualified name, e.g.
     * `org.jetbrains.kotlin.com.intellij.util.io.PersistentHashMap`). Must be cheap and side-effect-free —
     * AGP calls it for every class on the build's runtime classpath to decide what to route through us.
     */
    fun handles(classFqn: String): Boolean

    /**
     * The rewriting visitor, chained ahead of [next] (which writes the final bytes). Called only when
     * [handles] returned true for [classFqn].
     */
    fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor
}

/**
 * The ordered registry of [ArtPatchPass]es applied to the bundled Kotlin compiler.
 *
 * **Empty by design until the device spike reports its first failure** — see [ArtPatchPass]. New passes are
 * appended to [ALL]; when several passes [handle][ArtPatchPass.handles] the same class they are chained in
 * declaration order.
 */
object ArtPatchPasses {

    val ALL: List<ArtPatchPass> = listOf(
        // One pass per confirmed ART breakage, discovered via KotlinCompilerArtSpikeTest.
        ManagementStubPass(),    // java.lang.management.* absent on ART (PerformanceManager + 5 more)
        PathUtilSelfLocatePass(), // PathUtil.getResourcePathForClass → the runtime-provisioned resource dir
    )

    /** True if any registered pass rewrites [classFqn]. */
    fun handles(classFqn: String): Boolean = ALL.any { it.handles(classFqn) }

    /**
     * Chain every pass that handles [classFqn] ahead of [next], or return `null` if none do (so the caller
     * can pass [next] through untouched). Passes compose in registration order.
     */
    fun visitorFor(classFqn: String, next: ClassVisitor): ClassVisitor? {
        var chained = next
        var any = false
        for (pass in ALL) {
            if (pass.handles(classFqn)) {
                chained = pass.visitor(classFqn, chained)
                any = true
            }
        }
        return if (any) chained else null
    }
}
