package dev.ide.kotlin.syntax

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Do we still know every name the compiler does?
 *
 * The vocabularies are the shared language of the whole port: the parser produces these element types, the
 * facade dispatches on them, and both differential oracles compare by them. A name the compiler has added and
 * we have not is a piece of Kotlin this module cannot yet describe, and it will show up here — on a compiler
 * bump — rather than as an unexplained tree difference months later.
 *
 * The check is one-directional on purpose. Extra names on our side are fine (token SETS, which the compiler
 * declares as `TokenSet` rather than as element types, and a couple of the compiler's own aliases); missing
 * ones are not.
 */
class VocabularyParityTest {

    @Test
    fun weDeclareEveryTokenTheCompilerDoes() {
        val theirs = CompilerVocabulary.compilerTokenNames.values.flatten().toSortedSet()
        assertTrue(theirs.size > 100, "reflection over the compiler's KtTokens found only ${theirs.size} names")
        val missing = theirs - CompilerVocabulary.ourTokenFieldNames
        assertTrue(
            missing.isEmpty(),
            "the compiler declares ${missing.size} token(s) this module does not: ${missing.joinToString(", ")}",
        )
    }

    @Test
    fun weDeclareEveryElementTheCompilerDoes() {
        val theirs = CompilerVocabulary.compilerNodeNames.values.flatten().toSortedSet()
        assertTrue(theirs.size > 100, "reflection over the compiler's KtNodeTypes found only ${theirs.size} names")
        val missing = theirs - CompilerVocabulary.ourNodeFieldNames
        assertTrue(
            missing.isEmpty(),
            "the compiler declares ${missing.size} element(s) this module does not: ${missing.joinToString(", ")}",
        )
    }

    @Test
    fun ourNamesAreDistinctInstances() {
        // Identity is the contract for an element type, so two names must not accidentally share one object
        // unless they are a deliberate alias. This catches a copy-paste that would silently merge two kinds.
        val shared = CompilerVocabulary.ourNodeNames.filterValues { it.size > 1 }
        assertTrue(
            shared.all { (_, names) -> names.any { it in KNOWN_ALIASES } },
            "unexpected shared element instances: ${shared.values}",
        )
    }

    private companion object {
        /** Aliases the compiler itself keeps, reproduced so code written against either name resolves. */
        val KNOWN_ALIASES = setOf("FUNCTION", "FILE", "KDOC", "DEFAULT_VISIBILITY_KEYWORD", "INSTANCE")
    }
}
