package dev.ide.build

import dev.ide.platform.ExtensionPoint

/**
 * Rewrites compiled classes on their way into an APK, after compilation and before dexing.
 *
 * This is the seam for the one kind of build logic the rest of the extension surface cannot express: a
 * change to bytecode the build did not produce. The motivating case is a library that cannot run as
 * published on Android: the Kotlin compiler reaching for `java.lang.ClassValue`, a cache spinning on
 * `VarHandle`, a Git client calling an API above the app's `minSdk`. Nothing can fix those in source,
 * because the source is a dependency; the only place to fix them is the bytecode, on the way in. AGP has
 * `AsmClassVisitorFactory` for exactly this, and a project built here needs the same reach.
 *
 * A transform sees one class at a time, as bytes, and returns the bytes to dex instead, or null to leave
 * the class alone, which is the cheap path and should be the answer for the overwhelming majority of
 * classes. Transforms run in registration order, each over the previous one's output.
 *
 * The build caches on content: a class whose bytes and transform set are unchanged is not visited again.
 * A transform must therefore be a **pure function** of the bytes it is given. One that consults the clock,
 * the file system or a counter produces a result the next build will not reproduce and will not recompute.
 */
interface ClassTransform {
    /** Stable id. Part of the instrumentation cache key, so changing it forces a re-transform. */
    val id: String

    /** Which classes this transform is offered. Narrowing it is what keeps a build from paying to walk
     *  every dependency's bytes. */
    val scope: ClassTransformScope get() = ClassTransformScope.PROJECT

    /** True when this transform applies to the module being packaged. */
    fun appliesTo(moduleName: String): Boolean = true

    /**
     * Rewrite [bytes], or return null to leave the class untouched.
     *
     * [className] is the binary name (`org.jetbrains.kotlin.Foo`), so a transform can decline in one string
     * comparison before parsing anything.
     */
    fun transform(className: String, bytes: ByteArray): ByteArray?

    /**
     * A version stamp for this transform's *behaviour*, folded into the cache key alongside [id]. Bump it
     * when the rewrite changes but the id does not, or a build will reuse classes rewritten by the old one.
     */
    val version: Int get() = 1
}

/** Which classes a [ClassTransform] is offered, mirroring the scopes the packaging pipeline already has. */
enum class ClassTransformScope {
    /** The application's own classes and those of the modules it depends on. */
    PROJECT,

    /** Only external library artifacts (the jars inside dependencies and AARs). */
    DEPENDENCIES,

    /** Everything that goes into the APK. */
    ALL,
    ;

    fun includesProject(): Boolean = this == PROJECT || this == ALL

    fun includesDependencies(): Boolean = this == DEPENDENCIES || this == ALL
}

/** Plugins contribute class transforms here; the packaging pipeline applies the applicable ones. */
val CLASS_TRANSFORM_EP = ExtensionPoint<ClassTransform>("platform.classTransform")
