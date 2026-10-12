package dev.ide.ui

/** First Strong Isolate and Pop Directional Isolate: the part between them takes its own direction. */
private const val FSI = '⁨'
private const val PDI = '⁩'

/**
 * Joins the parts of a one-line meta label (`Android · 1 module · 3 minutes ago`) with [separator], each
 * part bidi-isolated.
 *
 * A plain join reads wrongly under an RTL locale, because the bidi algorithm sees one run of text rather
 * than separate parts. A part that starts with a number loses it to its neighbour (`1 module` shows as
 * `module ... 1`), and a number after a Latin word joins the Latin run (`Gradle · 3 وحدات` reads as
 * `3 · Gradle`). Isolating each part keeps every part in its own direction while the parts themselves
 * follow the reading direction, so the first part sits at the reading edge in either locale. The
 * isolates draw nothing.
 */
fun Iterable<String>.joinMeta(separator: String = " · "): String =
    joinToString(separator) { if (it.isEmpty()) it else "$FSI$it$PDI" }
