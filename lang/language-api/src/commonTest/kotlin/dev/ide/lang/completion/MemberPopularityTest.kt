package dev.ide.lang.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemberPopularityTest {

    private val textView = listOf("android.widget.TextView", "android.view.View", "java.lang.Object")

    @Test
    fun theMostUsedMembersOfAWidgetOutscoreItsLongTail() {
        val setText = MemberPopularity.of(textView, "setText")
        val autoSize = MemberPopularity.of(textView, "setAutoSizeTextTypeWithDefaults")
        assertTrue(setText > 0, "setText is in the TextView list")
        assertEquals(0, autoSize, "a member no list mentions has no signal")
    }

    @Test
    fun aMemberOnlyTheSuperclassListsIsScoredFromIt() {
        // TextView's own list does not mention setPadding; View's does.
        assertTrue(MemberPopularity.of(textView, "setPadding") > 0)
        assertEquals(0, MemberPopularity.of(listOf("android.widget.TextView"), "setPadding"))
    }

    @Test
    fun theNearestListThatMentionsAMemberWins() {
        // getText is second on TextView but leads EditText's list.
        val editText = listOf("android.widget.EditText") + textView
        assertEquals(100, MemberPopularity.of(editText, "getText"))
        assertTrue(MemberPopularity.of(textView, "getText") < 100)
    }

    @Test
    fun aWidgetsListsInterleaveWithItsSuperclasses() {
        // Each class's first member ties: a TextView offers setText and setOnClickListener together at the top.
        assertEquals(100, MemberPopularity.of(textView, "setText"))
        assertEquals(100, MemberPopularity.of(textView, "setOnClickListener"))
        // A getter and its setter stay together, since only View lists them.
        assertEquals(1, MemberPopularity.of(textView, "setVisibility") - MemberPopularity.of(textView, "getVisibility"))
    }

    @Test
    fun aKotlinPropertyIsScoredAsItsAccessors() {
        assertEquals(MemberPopularity.of(textView, "setText"), MemberPopularity.of(textView, "text"))
        assertEquals(MemberPopularity.of(textView, "setTextColor"), MemberPopularity.of(textView, "textColor"))
        // `isEnabled` the Kotlin property stands for `isEnabled()` and `setEnabled(...)`.
        assertEquals(MemberPopularity.of(textView, "setEnabled"), MemberPopularity.of(textView, "isEnabled"))
    }

    @Test
    fun anUnknownTypeHasNoSignal() {
        assertEquals(0, MemberPopularity.of(listOf("com.example.Thing"), "setText"))
        assertEquals(0, MemberPopularity.of(emptyList(), "setText"))
    }
}
