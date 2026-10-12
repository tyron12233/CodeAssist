package dev.ide.core.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemberUsageIndexTest {

    @Test
    fun countsEveryNameAfterADot() {
        val counts = MemberUsageIndex.countMemberNames(
            """
            class A {
                void f(TextView t) {
                    t.setText("a");
                    t.setText("b");
                    t?.setVisibility(View.GONE);
                    t
                        .setOnClickListener(null);
                }
            }
            """.trimIndent(),
        )
        assertEquals(2, counts["setText"])
        assertEquals(1, counts["setVisibility"])
        assertEquals(1, counts["GONE"])
        assertEquals(1, counts["setOnClickListener"], "a chained call on the next line counts too")
    }

    @Test
    fun skipsCommentsLiteralsImportsAndNumbers() {
        val counts = MemberUsageIndex.countMemberNames(
            """
            package com.example.app
            import android.widget.TextView
            // t.commented()
            /* t.blocked() */
            val s = "t.inString()"
            val r = ""${'"'}t.inRaw()""${'"'}
            val c = '.'
            val d = 1.5
            fun f(t: TextView) = t.real()
            """.trimIndent(),
        )
        assertEquals(mapOf("real" to 1), counts)
        assertNull(counts["widget"], "import lines are skipped")
    }
}
