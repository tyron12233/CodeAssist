package dev.codeassist.csharp.project.nuget

/**
 * Represents a NuGet version with up to four numeric components and an optional prerelease label.
 *
 * Comparison follows NuGet ordering: numeric components first, then release versions above their
 * prereleases, followed by prerelease identifiers compared part by part.
 */
internal class NuGetVersion private constructor(
    private val numbers: List<Int>,
    private val prerelease: String,
) : Comparable<NuGetVersion> {

    /** Flat-container URL form: at least three components, with a trailing zero fourth component omitted. */
    val normalized: String
        get() {
            val parts = numbers.toMutableList()
            while (parts.size < 3) parts.add(0)
            if (parts.size == 4 && parts[3] == 0) parts.removeAt(3)

            return parts.joinToString(".") +
                if (prerelease.isEmpty()) "" else "-${prerelease.lowercase()}"
        }

    val isPrerelease: Boolean
        get() = prerelease.isNotEmpty()

    override fun compareTo(other: NuGetVersion): Int {
        // Compare up to four numeric components, treating missing components as zero.
        for (i in 0 until 4) {
            val c = numbers.getOrElse(i) { 0 }
                .compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }

        // A release sorts above any prerelease of the same numeric version.
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }

        // Compare prerelease identifiers individually.
        val mine = prerelease.split('.')
        val theirs = other.prerelease.split('.')

        for (i in 0 until minOf(mine.size, theirs.size)) {
            val a = mine[i]
            val b = theirs[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()

            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b, ignoreCase = true)
            }
            if (c != 0) return c
        }

        return mine.size.compareTo(theirs.size)
    }

    override fun equals(other: Any?): Boolean =
        other is NuGetVersion && compareTo(other) == 0

    override fun hashCode(): Int = normalized.hashCode()

    override fun toString(): String = normalized

    companion object {

        /** Returns null if [text] is not a valid version. Build metadata (`+...`) is ignored. */
        fun parse(text: String): NuGetVersion? {
            val withoutMeta = text.trim().substringBefore('+')
            val core = withoutMeta.substringBefore('-')
            val pre = if ('-' in withoutMeta) withoutMeta.substringAfter('-') else ""

            val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
            if (numbers.isEmpty() || numbers.size > 4) return null

            return NuGetVersion(numbers, pre)
        }

        /** Returns true for floating versions (`*`, `1.*`), which select the newest matching version. */
        fun isFloating(range: String): Boolean = '*' in range

        /** Returns the prefix before `*`: `1.` for `1.*`, or an empty string for `*`. */
        fun floatingPrefix(range: String): String =
            range.trim().substringBefore('*').trimStart('[', '(')

        /**
         * Returns the lowest version allowed by [range], which is the version NuGet picks.
         *
         * For example, `1.2`, `[1.2,)`, and `[1.2, 2.0)` all have a lower bound of `1.2`.
         * Returns null if there is no lower bound or the range is floating.
         */
        fun lowerBound(range: String): NuGetVersion? {
            val text = range.trim()
            if (text.isEmpty() || isFloating(text)) return null

            val bare = if (text.startsWith("[") || text.startsWith("(")) {
                text.trim('[', ']', '(', ')')
            } else {
                text
            }

            val first = bare.substringBefore(',').trim()
            return if (first.isEmpty()) null else parse(first)
        }
    }
}

/**
 * Selects a package target framework supported by this compiler.
 *
 * The project targets `net10.0` (see `Csproj`), so the newest compatible `net` framework
 * is preferred, followed by .NET Standard frameworks.
 *
 * `netstandard.dll` is included in the reference pack and runtime, which is what lets a
 * .NET Standard library load. Platform-specific frameworks (such as `net8.0-android`) and .NET Framework
 * targets are not supported.
 */
internal object NuGetFrameworks {

    /**
     * Normalizes a framework moniker as used in `lib/` directories and `.nuspec` files.
     *
     * For example, `.NETStandard2.0` becomes `netstandard2.0`.
     */
    fun normalize(moniker: String): String {
        val lower = moniker.trim().lowercase()

        return when {
            lower.startsWith(".netstandard") ->
                "netstandard" + lower.removePrefix(".netstandard")

            lower.startsWith(".netcoreapp") ->
                "netcoreapp" + lower.removePrefix(".netcoreapp")

            lower.startsWith(".netframework") ->
                "net" + lower.removePrefix(".netframework").replace(".", "")

            else -> lower
        }
    }

    /** Returns the best usable entry from [available], preserving its original spelling. */
    fun best(available: Collection<String>): String? {
        val byShortName = available.associateBy { normalize(it) }
        return ORDER.firstNotNullOfOrNull { byShortName[it] }
    }

    /** Framework preference order for the project's `net10.0` target. */
    private val ORDER = listOf(
        "net10.0",
        "net9.0",
        "net8.0",
        "net7.0",
        "net6.0",
        "net5.0",
        "netcoreapp3.1",
        "netcoreapp3.0",
        "netcoreapp2.2",
        "netcoreapp2.1",
        "netcoreapp2.0",
        "netstandard2.1",
        "netstandard2.0",
        // Every .NET Standard 1.x library runs on .NET too, and older packages ship nothing newer.
        "netstandard1.6",
        "netstandard1.5",
        "netstandard1.4",
        "netstandard1.3",
        "netstandard1.2",
        "netstandard1.1",
        "netstandard1.0",
    )
}