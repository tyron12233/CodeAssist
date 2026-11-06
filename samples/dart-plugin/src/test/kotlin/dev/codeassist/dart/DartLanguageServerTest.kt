package dev.codeassist.dart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DartLanguageServerTest {

    @Test
    fun completingAFunctionInsertsItsCall() {
        // Off unless the client turns it on: completing `print` then inserted the bare name, no parentheses.
        assertEquals("""{"completeFunctionCalls":true}""", DartLanguageServer.settings("dart"))
        assertEquals("""{"dart":{"completeFunctionCalls":true}}""", DartLanguageServer.settings(null))
    }

    @Test
    fun otherSectionsHaveNoSettings() {
        assertNull(DartLanguageServer.settings("editor"))
    }
}
