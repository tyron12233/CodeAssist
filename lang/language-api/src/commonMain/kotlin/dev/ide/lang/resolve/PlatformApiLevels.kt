package dev.ide.lang.resolve

/**
 * When each Android platform API appeared, was deprecated, and was removed: the facts in a platform's
 * `data/api-versions.xml`, which `android.jar` itself does not carry. The editor reads them for the
 * "Added in API N" line of a doc popup and for the minSdk check (a call to an API newer than the module's
 * `minSdk`, Android lint's `NewApi`).
 *
 * Class names are accepted in any of the spellings a backend has at hand: internal (`android/os/Build$VERSION`),
 * binary (`android.os.Build$VERSION`) or dotted (`android.os.Build.VERSION`). Every answer is null when the
 * table has no entry, which callers must treat as "unknown, say nothing", never as "always available".
 *
 * The host injects an implementation (the table ships with the Android support); [NONE] is the default.
 */
interface PlatformApiLevels {
    /** The API level of the platform the table was generated from (its highest `since`). */
    val platformLevel: Int

    /** The class's own levels, or null when it is not a public platform class. */
    fun classLevel(className: String): ApiLevel?

    /**
     * The method's levels on [ownerClass], where [descriptor] is the JVM method descriptor (`(I)V`, and `<init>`
     * for a constructor). When [descriptor] is null the overloads named [name] are merged conservatively: the
     * EARLIEST `since` among them (so an unknown overload is never reported as too new) and a deprecation only
     * when every one of them is deprecated. [arity], when non-negative, narrows that merge to the overloads
     * taking that many parameters.
     *
     * A method the table does not list on [ownerClass] is looked up through its supertypes, and a listed
     * method without a level of its own takes its class's.
     */
    fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int = -1): ApiLevel?

    /** The field's levels on [ownerClass], resolved like [methodLevel]. */
    fun fieldLevel(ownerClass: String, name: String): ApiLevel?

    companion object {
        /** A table that knows nothing: every lookup is null, so no API-level fact is ever claimed. */
        val NONE: PlatformApiLevels = object : PlatformApiLevels {
            override val platformLevel: Int = 0
            override fun classLevel(className: String): ApiLevel? = null
            override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int): ApiLevel? = null
            override fun fieldLevel(ownerClass: String, name: String): ApiLevel? = null
        }
    }
}

/**
 * One API's levels. [since] is the first API level that has it (1 for the original platform). [deprecatedIn] and
 * [removedIn] are 0 when it never was. [desugared] marks a `java.*` method D8 backports into the app itself, so
 * it is usable below [since] and the minSdk check must not report it.
 */
data class ApiLevel(
    val since: Int,
    val deprecatedIn: Int = 0,
    val removedIn: Int = 0,
    val desugared: Boolean = false,
) {
    /**
     * A one-line, human-readable summary: `Added in API 31 · Deprecated in API 33`, or for the JDK
     * `Since Java 9 · Deprecated in Java 17`. The JDK table starts at Java 8, so an API from 8 or earlier gets
     * no "Since" part, and the line is empty when there is nothing else to say.
     */
    fun describe(platform: ApiPlatform = ApiPlatform.ANDROID): String {
        val unit = platform.unit
        val parts = ArrayList<String>(3)
        when {
            platform == ApiPlatform.ANDROID -> parts += "Added in $unit ${maxOf(since, 1)}"
            since > JDK_BASELINE -> parts += "Since $unit $since"
        }
        if (deprecatedIn > 0) parts += "Deprecated in $unit $deprecatedIn"
        if (removedIn > 0) parts += "Removed in $unit $removedIn"
        return parts.joinToString(" · ")
    }

    /**
     * [doc] with [describe] as its first line. A raw doc comment keeps its opening and closing markers, so a
     * renderer that strips them still finds the tags after the description; a null or blank [doc] becomes the
     * line alone.
     */
    fun prependTo(doc: String?, platform: ApiPlatform = ApiPlatform.ANDROID): String {
        val line = describe(platform)
        if (line.isEmpty()) return doc.orEmpty()
        if (doc.isNullOrBlank()) return line
        val trimmed = doc.trimStart()
        if (trimmed.startsWith("/**")) return "/**\n * $line\n *\n * " + trimmed.removePrefix("/**").trimStart(' ', '\n')
        return "$line\n\n$doc"
    }
}

/** The oldest release the JDK table distinguishes: its `since` of 8 means "Java 8 or earlier". */
const val JDK_BASELINE = 8

/** Whose levels a [PlatformApiLevels] table holds: Android API levels, or Java releases. */
enum class ApiPlatform(val unit: String) {
    ANDROID("API"),
    JDK("Java"),
}

/**
 * What the API-level check needs about the module being analyzed. For an Android module it is the minSdk check
 * (lint's `NewApi`): [minLevel] is the module's `minSdk` and [levels] the platform's API-level table. For a plain
 * JVM module it is the language-level check (`javac --release`): [minLevel] is the module's Java release and
 * [levels] the JDK's table. A module with neither has no context (null), and the check does not run.
 *
 * [enforced] says the build rejects such a call too (a desktop build compiled with `--release`), which makes
 * the editor report it as an error rather than a warning.
 */
data class ApiLevelContext(
    val platform: ApiPlatform,
    val minLevel: Int,
    val levels: PlatformApiLevels,
    val enforced: Boolean = false,
) {
    companion object {
        /** An Android module's minSdk check. */
        fun android(minSdk: Int, levels: PlatformApiLevels) = ApiLevelContext(ApiPlatform.ANDROID, minSdk, levels)

        /** A JVM module's check against its Java [release]. */
        fun jdk(release: Int, levels: PlatformApiLevels, enforced: Boolean) = ApiLevelContext(ApiPlatform.JDK, release, levels, enforced)
    }
}
