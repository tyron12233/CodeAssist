package dev.ide.lang.jdt

import dev.ide.lang.signature.SignatureScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shared lexical call-site locator (used by both signature-help backends). `^` marks the caret. */
class SignatureScanTest {

    private fun at(textWithCaret: String): SignatureScan.CallSite? {
        val caret = textWithCaret.indexOf('^')
        val text = textWithCaret.replace("^", "")
        return SignatureScan.enclosingCall(text, caret)
    }

    @Test fun findsCalleeAndFirstArg() {
        val s = at("foo(^)")!!
        assertEquals("foo", "foo(".substring(s.calleeNameStart, s.calleeNameEnd))
        assertEquals(0, s.activeParameter)
    }

    @Test fun countsTopLevelCommas() {
        assertEquals(2, at("foo(a, b, ^)")!!.activeParameter)
    }

    @Test fun nestedCallArgDoesNotLeakIntoOuterIndex() {
        // After the whole bar(1, 2) argument + a comma, the caret is the outer call's 2nd argument.
        assertEquals(1, at("foo(bar(1, 2), ^)")!!.activeParameter)
    }

    @Test fun caretInsideArrayOrLambdaIsNotTheCallArgList() {
        // The caret sits inside a `{}` (array initializer / lambda), not directly in the call's parens.
        assertNull(at("foo(a, new int[]{1, ^})"))
    }

    @Test fun innermostCallWins() {
        val s = at("foo(a, bar(b, ^))")!!
        assertEquals("bar", "foo(a, bar(".substring(s.calleeNameStart, s.calleeNameEnd))
        assertEquals(1, s.activeParameter)
    }

    @Test fun ignoresCommasAndParensInStrings() {
        assertEquals(1, at("foo(\"a, (b\", ^)")!!.activeParameter)
    }

    @Test fun nullWhenNotInCall() {
        assertNull(at("int x = ^1;"))
        assertNull(at("foo(a)^"))
    }
}
