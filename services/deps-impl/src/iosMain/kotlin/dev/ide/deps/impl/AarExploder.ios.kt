package dev.ide.deps.impl

/**
 * No AAR reader here, and that is a statement about the artifact rather than about the platform.
 *
 * An `.aar` is an Android library: Android resources, an Android manifest, `jni/` for Android ABIs. Nothing
 * on iOS consumes any of it, and a zip reader alone would not change that — the exploded directory exists so
 * that aapt2 and the Android build can find a library's `res/`, and neither runs here.
 *
 * So an AAR coordinate resolves, is downloaded and cached like any other, and then contributes no class
 * root: the resolver reports that one coordinate unresolved and carries on with the rest of the graph.
 * Every plain `.jar` in the same resolve is unaffected, which is what a Kotlin/JVM project's classpath is
 * made of.
 */
internal actual fun explodeAar(aar: String, dir: String): String? = null
