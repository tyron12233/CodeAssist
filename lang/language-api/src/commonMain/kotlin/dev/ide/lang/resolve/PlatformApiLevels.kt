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
    /** A one-line, human-readable summary: `Added in API 31 · Deprecated in API 33`. */
    fun describe(): String = buildString {
        append("Added in API ").append(maxOf(since, 1))
        if (deprecatedIn > 0) append(" · Deprecated in API ").append(deprecatedIn)
        if (removedIn > 0) append(" · Removed in API ").append(removedIn)
    }

    /**
     * [doc] with [describe] as its first line. A raw doc comment keeps its opening and closing markers, so a
     * renderer that strips them still finds the tags after the description; a null or blank [doc] becomes the
     * line alone.
     */
    fun prependTo(doc: String?): String {
        val line = describe()
        if (doc.isNullOrBlank()) return line
        val trimmed = doc.trimStart()
        if (trimmed.startsWith("/**")) return "/**\n * $line\n *\n * " + trimmed.removePrefix("/**").trimStart(' ', '\n')
        return "$line\n\n$doc"
    }
}

/**
 * What the minSdk check needs about the module being analyzed: its [minSdk] and the [levels] table. A module
 * that is not an Android module has no context (null), and the check does not run.
 */
data class AndroidApiContext(
    val minSdk: Int,
    val compileSdk: Int,
    val levels: PlatformApiLevels,
)
