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
 *
 * [jdkReference], for an Android module, supplies the JDK's own table. Android's `java.*` is a subset of the
 * JDK's, so a call to real Java API the platform lacks (`Files.writeString`, Java 11, absent from Android before
 * API 37) fails to resolve; with the JDK table at hand the editor can say that, in place of a bare "unresolved
 * reference" that reads as the user's mistake. A supplier, because the table is only worth loading once such a
 * call turns up.
 */
data class ApiLevelContext(
    val platform: ApiPlatform,
    val minLevel: Int,
    val levels: PlatformApiLevels,
    val enforced: Boolean = false,
) {
    /**
     * See the class comment. Outside the constructor so the published constructor, `copy` and `componentN`
     * keep their shapes (plugins link against them); it is therefore not part of equality, and [copy] drops it.
     */
    var jdkReference: (() -> PlatformApiLevels?)? = null
        private set

    companion object {
        /** An Android module's minSdk check. */
        fun android(minSdk: Int, levels: PlatformApiLevels) = ApiLevelContext(ApiPlatform.ANDROID, minSdk, levels)

        /** An Android module's minSdk check, with the JDK's table to explain Java API the platform lacks. */
        fun android(minSdk: Int, levels: PlatformApiLevels, jdkReference: () -> PlatformApiLevels?) =
            ApiLevelContext(ApiPlatform.ANDROID, minSdk, levels).also { it.jdkReference = jdkReference }

        /** A JVM module's check against its Java [release]. */
        fun jdk(release: Int, levels: PlatformApiLevels, enforced: Boolean) = ApiLevelContext(ApiPlatform.JDK, release, levels, enforced)
    }
}

/**
 * Why a member of a `java.*` type does not resolve in an Android module, when the reason is that it is real Java
 * API the Android platform does not have. Android's `java.*` is a subset of the JDK's, so code written from Java
 * documentation (`Files.writeString`, Java 11) is correct Java that this platform rejects, and a bare
 * "unresolved" reads as the user's own mistake. Shared by the language backends so they say it the same way.
 */
object JavaApiMissingOnAndroid {

    /**
     * The sentence explaining [member] on [ownerFqn] (`'Files.writeString' is Java 11 API that Android API 36
     * does not have.`), or null when that is not the reason: not an Android module, not a `java.*`/`javax.*`
     * type, no JDK table at hand ([ApiLevelContext.jdkReference]), or the JDK does not have the member either.
     */
    fun explain(context: ApiLevelContext?, ownerFqn: String, member: String): String? {
        if (context == null || context.platform != ApiPlatform.ANDROID) return null
        if (!ownerFqn.startsWith("java.") && !ownerFqn.startsWith("javax.")) return null
        val jdk = context.jdkReference?.invoke() ?: return null
        val level = jdk.methodLevel(ownerFqn, member, null) ?: jdk.fieldLevel(ownerFqn, member) ?: return null
        val java = if (level.since > JDK_BASELINE) "Java ${level.since} API" else "Java API"
        val platform = context.levels.platformLevel.takeIf { it > 0 }?.let { "Android API $it" } ?: "this Android platform"
        return "'${ownerFqn.substringAfterLast('.')}.$member' is $java that $platform does not have."
    }

    /**
     * [explain] for a whole TYPE that does not resolve (`import java.net.http.HttpClient`), by its qualified
     * name: the JDK has the class and this platform does not. A package the JDK has and Android leaves out
     * entirely ([MISSING_PACKAGES]) is explained too, which covers a star import of it. Null otherwise.
     */
    fun explainType(context: ApiLevelContext?, typeFqn: String): String? {
        if (context == null || context.platform != ApiPlatform.ANDROID) return null
        if (!typeFqn.startsWith("java.") && !typeFqn.startsWith("javax.")) return null
        val jdk = context.jdkReference?.invoke() ?: return null
        val level = jdk.classLevel(typeFqn)
        if (level == null && MISSING_PACKAGES.none { typeFqn == it || typeFqn.startsWith("$it.") }) return null
        val java = if (level != null && level.since > JDK_BASELINE) "Java ${level.since} API" else "Java API"
        val platform = context.levels.platformLevel.takeIf { it > 0 }?.let { "Android API $it" } ?: "this Android platform"
        return "'$typeFqn' is $java that $platform does not have."
    }

    /**
     * What to use in place of [name], an `owner.member` or a type's qualified name, when there is something
     * that works the same on Android; the entry for the nearest enclosing package applies to everything in it.
     * Worded for either language. A backend with a better answer in its own syntax uses that first.
     */
    fun alternative(name: String): String? {
        ALTERNATIVES[name]?.let { return it }
        var prefix = name
        while (true) {
            prefix = prefix.substringBeforeLast('.', "")
            if (prefix.isEmpty()) return null
            PACKAGE_ALTERNATIVES[prefix]?.let { return it }
        }
    }

    /**
     * The members here were read off a comparison of JDK 21's `java.base` with the API 36 `android.jar`: these
     * are the ones in everyday classes that Android lacks AND that have a straightforward replacement. The rest
     * of what is missing (modules, class-file constants, foreign memory, hidden classes) gets the explanation
     * without a suggestion.
     */
    private val ALTERNATIVES: Map<String, String> = mapOf(
        "java.lang.Thread.ofVirtual" to "Android has no virtual threads. Use a thread pool such as 'Executors.newCachedThreadPool()', or Kotlin coroutines.",
        "java.lang.Thread.startVirtualThread" to "Android has no virtual threads. Use a thread pool such as 'Executors.newCachedThreadPool()', or Kotlin coroutines.",
        "java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor" to "Android has no virtual threads. Use 'Executors.newCachedThreadPool()'.",
        "java.util.concurrent.Executors.newThreadPerTaskExecutor" to "Use 'Executors.newCachedThreadPool(threadFactory)'.",
        "java.lang.Thread.ofPlatform" to "Create the thread with the 'Thread' constructor.",
        "java.lang.StringBuilder.repeat" to "Append 'text.repeat(count)'.",
        "java.lang.StringBuffer.repeat" to "Append 'text.repeat(count)'.",
        "java.lang.Runtime.version" to "For the platform's version use 'android.os.Build.VERSION.SDK_INT'.",
        "java.lang.System.getLogger" to "Use 'android.util.Log', or 'java.util.logging.Logger'.",
        "java.lang.Process.pid" to "Android does not expose a child process's id. 'android.os.Process.myPid()' is this app's own.",
        "java.lang.Process.toHandle" to "Android has no 'ProcessHandle'. 'android.os.Process' covers this app's own process.",
        "java.lang.ProcessBuilder.Redirect.DISCARD" to "Redirect to the file '/dev/null'.",
        "java.time.Duration.isPositive" to "Test that it is neither 'isNegative()' nor 'isZero()'.",
        "java.net.URL.of" to "Use 'uri.toURL()'.",
        "java.lang.reflect.AccessibleObject.canAccess" to "Use 'isAccessible()'.",
        "java.lang.reflect.AccessibleObject.trySetAccessible" to "Call 'setAccessible(true)' and catch the exception it throws when access is refused.",
        "java.text.NumberFormat.getCompactNumberInstance" to "Use 'android.icu.text.CompactDecimalFormat'.",
        "java.lang.ProcessHandle" to "'android.os.Process' covers this app's own process.",
    )

    /** Packages the JDK has and Android leaves out, each with what Android offers in its place. */
    private val PACKAGE_ALTERNATIVES: Map<String, String> = mapOf(
        "java.net.http" to "Use 'java.net.HttpURLConnection', or a library such as OkHttp.",
        "java.awt" to "Android has no AWT. Use 'android.graphics' for drawing and Android views or Compose for UI.",
        "javax.swing" to "Android has no Swing. Build the UI with Android views or Compose.",
        "javax.imageio" to "Use 'android.graphics.BitmapFactory' to read an image and 'Bitmap.compress' to write one.",
    )

    private val MISSING_PACKAGES: Set<String> = PACKAGE_ALTERNATIVES.keys
}
