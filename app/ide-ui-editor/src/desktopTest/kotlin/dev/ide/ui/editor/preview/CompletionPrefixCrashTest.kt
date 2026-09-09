package dev.ide.ui.editor.preview

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A regression guard for one of the top on-device crashes in the analytics (see docs/analytics.md). It was
 * one case in :ide-ui's CrashFixesTest until the UI split; `completionPrefix` is internal to this module, and
 * a test belongs with the internals it exercises.
 */
class CompletionPrefixCrashTest {

    /**
     * The layout-attribute completion popup filters on the text between the completion's replace start and the
     * caret. The completion pass is debounced and the caret is not, so a replace start computed for an earlier
     * position outlives the caret moving back before it -- which used to invert the range and throw
     * StringIndexOutOfBounds straight out of the composition.
     */
    @Test
    fun completionPrefixSurvivesACaretBehindAStaleReplaceStart() {
        assertEquals("wid", completionPrefix("android:wid", replaceStart = 8, caret = 11))
        // The reported crash: caret moved back past the stale replace start (was start=8 > end=3).
        assertEquals("", completionPrefix("android:wid", replaceStart = 8, caret = 3))
        // And the same inversion after the text shrank under both offsets.
        assertEquals("", completionPrefix("and", replaceStart = 8, caret = 1))
        // Out-of-range ends still clamp to the text.
        assertEquals("droid:wid", completionPrefix("android:wid", replaceStart = 2, caret = 999))
        assertEquals("android:wid", completionPrefix("android:wid", replaceStart = -4, caret = 11))
        assertEquals("", completionPrefix("", replaceStart = 3, caret = 7))
    }
}
