package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

@Incubating
interface PackagingOptions {
    /** The set of excluded paths.*/
    @Deprecated(
        "This property is deprecated. Use resources.excludes or jniLibs.excludes instead. Use "
                + "jniLibs.excludes for .so file patterns, and use resources.excludes for all "
                + "other file patterns."
    )
    val excludes: MutableSet<String>

    /**
     * The set of patterns where the first occurrence is packaged in the APK. First pick patterns
     * do get packaged in the APK, but only the first occurrence found gets packaged.
     */
    @Deprecated(
        "This property is deprecated. Use resources.pickFirsts or jniLibs.pickFirsts instead. "
                + "Use jniLibs.pickFirsts for .so file patterns, and use resources.pickFirsts for "
                + "all other file patterns."
    )
    val pickFirsts: MutableSet<String>

    /** The set of patterns where all occurrences are concatenated and packaged in the APK. */
    @Deprecated(
        "This property is deprecated. Use resources.merges instead.",
        replaceWith = ReplaceWith("resources.merges")
    )
    val merges: MutableSet<String>

    /**
     * The set of patterns for native library that should not be stripped of debug symbols.
     */
    @Deprecated(
        "This property is deprecated. Use jniLibs.keepDebugSymbols instead.",
        replaceWith = ReplaceWith("jniLibs.keepDebugSymbols")
    )
    val doNotStrip: MutableSet<String>

    /**
     * Adds an excluded pattern.
     *
     * @param pattern the pattern
     */
    @Deprecated(
        "This method is deprecated. Use resources.excludes.add() or "
                + "jniLibs.excludes.add() instead. Use jniLibs.excludes.add() for .so file "
                + "patterns, and use resources.excludes.add() for all other file patterns."
    )
    fun exclude(pattern: String)

    /**
     * Adds a first-pick pattern.
     *
     * @param pattern the path to add.
     */
    @Deprecated(
        "This method is deprecated. Use resources.pickFirsts.add() or "
                + "jniLibs.pickFirsts.add() instead. Use jniLibs.pickFirsts.add() for .so file "
                + "patterns, and use resources.pickFirsts.add() for all other file patterns."
    )
    fun pickFirst(pattern: String)

    /**
     * Adds a merge pattern.
     *
     * @param pattern the pattern, as packaged in the APK
     */
    @Deprecated(
        "This method is deprecated. Use resources.merges.add() instead.",
        replaceWith = ReplaceWith("resources.merges.add(pattern)")
    )
    fun merge(pattern: String)

    /**
     * Adds a doNotStrip pattern.
     *
     * @param pattern the pattern, as packaged in the APK
     */
    @Deprecated(
        "This method is deprecated. Use jniLibs.keepDebugSymbols.add() instead.",
        replaceWith = ReplaceWith("jniLibs.keepDebugSymbols.add(pattern)")
    )
    fun doNotStrip(pattern: String)

    /** PackagingOptions for dex */
    val dex: DexPackagingOptions

    /** PackagingOptions for dex */
    fun dex(action: DexPackagingOptions.() -> Unit)

    /** PackagingOptions for jniLibs */
    val jniLibs: JniLibsPackagingOptions

    /** PackagingOptions for jniLibs */
    fun jniLibs(action: JniLibsPackagingOptions.() -> Unit)

    /** PackagingOptions for java resources */
    val resources: ResourcesPackagingOptions

    /** PackagingOptions for java resources */
    fun resources(action: ResourcesPackagingOptions.() -> Unit)
}