package dev.ide.android.support

/**
 * The Android API levels the IDE offers, and which Android release each one is.
 *
 * One table, because the same levels are needed in three places that had drifted apart: the new-project
 * pickers, a new module's starter facet, and the level an import falls back to when the Gradle scripts
 * declare none. All three sat at API 34 (Android 14) long after Android 16 shipped, which is not just a stale
 * label: a modern AndroidX release declares `minCompileSdk` in its AAR metadata, and the build refuses a
 * dependency that needs a higher compileSdk than the module has ([tools.AarMetadata]).
 */
object AndroidApiLevels {

    /** An API level and the Android release it shipped as. */
    data class Level(val api: Int, val release: String) {
        /** "API 36 · Android 16", the form the pickers show. */
        val label: String get() = "API $api · Android $release"
    }

    /**
     * The levels a new project can pick, ascending. Not every level ever published: a spread of the ones
     * still worth targeting, which is what the minSdk picker is for.
     */
    val LEVELS: List<Level> = listOf(
        Level(21, "5.0"),
        Level(23, "6.0"),
        Level(24, "7.0"),
        Level(26, "8.0"),
        Level(28, "9.0"),
        Level(30, "11"),
        Level(33, "13"),
        Level(34, "14"),
        Level(35, "15"),
        Level(36, "16"),
    )

    /**
     * The level a new module compiles and targets by default: the newest in [LEVELS].
     *
     * It tracks the `android.jar` that `:ide-android` bundles, which is the compile ceiling on device (a
     * device build has no other platform to compile against). Desktop resolves whatever is installed
     * (`AndroidSdk.detect` takes the highest platform present), so a module that sets a higher level by hand
     * still builds there. Android 17 (API 37) is therefore NOT offered yet: raising this means bundling the
     * API 37 jar first, which costs about 15 MB of APK.
     */
    const val LATEST: Int = 36

    /** Levels offered as a `minSdk`: the app's floor, so the whole spread. */
    val MIN_SDK_LEVELS: List<Level> = LEVELS

    /** Levels offered as a `targetSdk`: recent ones only, since Play requires a current target and an old
     *  one silently opts the app into compatibility behaviour. */
    val TARGET_SDK_LEVELS: List<Level> = LEVELS.filter { it.api >= 30 }

    /**
     * The default `minSdk` for a new module: 26, not the lowest level offered.
     *
     * Below API 26 D8 must desugar (lambdas, default interface methods, core library) every library on
     * device, and the whole-set desugaring cache key means adding one dependency re-dexes the entire
     * classpath. At 26+ desugaring is off and each library dexes once into a cross-project bucket, so a new
     * Compose project's first build is far cheaper and later builds are cache hits.
     */
    const val DEFAULT_MIN_SDK: Int = 26

    /** "API 36 · Android 16" for a known level, else just the number. */
    fun label(api: Int): String = LEVELS.firstOrNull { it.api == api }?.label ?: "API $api"
}
