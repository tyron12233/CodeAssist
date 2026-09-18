// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.model

/**
 * What a dependency IS, separated from what a project does with one.
 *
 * A coordinate is four strings and an exclusion is two; neither has a filesystem, a build or a workspace
 * behind it. They live here rather than with the project model because the dependency RESOLVER is typed by
 * these and by nothing else in that model, and a resolver that only ever turns coordinates into URLs should
 * not be pinned to the JVM by the module that also describes a `Project`.
 */

/**
 * A Maven-style artifact identity. Fundamental enough to live in the model; deps-api reuses it.
 *
 * [classifier] names a SECONDARY artifact of the same module: the fourth segment of Gradle's
 * `group:name:version:classifier` notation. A module can publish files that are not its main artifact, keyed
 * by classifier: `com.badlogicgames.gdx:gdx-platform` publishes no main jar at all, only
 * `natives-arm64-v8a`, `natives-armeabi-v7a`, `natives-desktop`, … Without a classifier there is nothing to
 * declare for such a module, and the plain three-part coordinate resolves to nothing.
 *
 * The classifier is part of the identity (two classifiers of one `group:name:version` are different
 * artifacts, not two versions of one) but NOT of the conflict-resolution key: Maven picks one version per
 * `group:name`, and every classifier of that module resolves at that version.
 */
data class Coordinate(
    val group: String,
    val name: String,
    val version: String,
    val classifier: String? = null,
) {
    override fun toString() =
        if (classifier.isNullOrEmpty()) "$group:$name:$version" else "$group:$name:$version:$classifier"

    companion object {
        /**
         * Parse `group:name`, `group:name:version` or `group:name:version:classifier` (the Gradle spellings),
         * or null when [s] is not a coordinate. A two-part form leaves [version] blank: the versionless
         * declaration an imported BOM fills in. Blank segments are rejected, so `a::1.0` isn't a coordinate.
         */
        fun parseOrNull(s: String): Coordinate? {
            val p = sanitizeCoordinate(s).split(":")
            if (p.size !in 2..4 || p.any(String::isBlank)) return null
            return Coordinate(p[0], p[1], p.getOrElse(2) { "" }, p.getOrNull(3))
        }
    }
}

/**
 * Strip the characters a `group:name:version` can never legally contain: whitespace (ordinary or
 * non-breaking) and every zero-width, bidi-mark, soft-hyphen [CharCategory.FORMAT] or control character.
 *
 * These arrive by paste. Documentation sites put `U+200B ZERO WIDTH SPACE` into their code blocks as
 * line-break hints, so copying a coordinate off a web page can yield
 * `"\u200Bandroidx.lifecycle:lifecycle-process:2.11.0"`, indistinguishable on screen from the real thing.
 * `String.trim()` does not touch it, since U+200B is not whitespace. Left in, the invisible character
 * reaches the artifact URL, the repository answers 404, and the dependency is reported unresolved
 * against a coordinate that looks perfectly correct. The same corruption inside the VERSION can be worse
 * than a clean failure: on emulated external storage a path lookup ignores zero-width characters, so the
 * cache probe hits an already-downloaded clean artifact and the coordinate resolves to something nothing
 * declared.
 *
 * Normalizing both on the way in (a new declaration) and on the way out of `module.toml` (load) also
 * heals a project that already persisted one.
 */
fun sanitizeCoordinate(raw: String): String = raw.filterNot { it.isInvisibleInCoordinate() }

/** [sanitizeCoordinate] over an already-split coordinate. */
fun sanitizeCoordinate(raw: Coordinate): Coordinate = Coordinate(
    sanitizeCoordinate(raw.group),
    sanitizeCoordinate(raw.name),
    sanitizeCoordinate(raw.version),
    raw.classifier?.let { sanitizeCoordinate(it) },
)

private fun Char.isInvisibleInCoordinate(): Boolean =
    isWhitespace() || category == CharCategory.FORMAT || category == CharCategory.CONTROL

/**
 * A transitive dependency to exclude from a declared dependency's closure, matched by `group:name`. Either
 * field may be the wildcard `"*"` (e.g. `Exclusion("com.google.guava", "*")` drops every guava artifact;
 * `Exclusion("*", "*")` drops all transitives, leaving only the declared artifact).
 */
data class Exclusion(val group: String, val name: String) {
    override fun toString(): String = "$group:$name"

    companion object {
        /** Parse a `group:name` exclusion string (either side may be `*`). Null if it isn't two colon parts. */
        fun parse(s: String): Exclusion? =
            s.split(":").map { it.trim() }.takeIf { it.size == 2 && it.none(String::isEmpty) }
                ?.let { Exclusion(it[0], it[1]) }
    }
}
