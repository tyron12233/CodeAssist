package dev.ide.lang.kotlin.parse

/**
 * Where two versions of a text differ, as one region: both share their first [start] characters, the region
 * then runs to [oldEnd] in the old text and to [newEnd] in the new one, and both end the same after it.
 *
 * A keystroke is one such region, so a per-keystroke rebuild can keep everything that lies wholly before it
 * (same offsets) or wholly after it (offsets moved by [delta]) and redo only what overlaps it.
 */
internal class TextDiff(val start: Int, val oldEnd: Int, val newEnd: Int) {
    val delta: Int get() = newEnd - oldEnd

    /** Where a range of the NEW text that the edit left alone started in the old text, or null if the edit
     *  reaches into it. */
    fun oldStartOf(newStart: Int, newEnd: Int): Int? = when {
        newEnd <= start -> newStart
        newStart >= this.newEnd -> newStart - delta
        else -> null
    }

    companion object {
        fun between(old: CharSequence, new: CharSequence): TextDiff {
            val limit = minOf(old.length, new.length)
            var prefix = 0
            while (prefix < limit && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            return TextDiff(prefix, old.length - suffix, new.length - suffix)
        }
    }
}
