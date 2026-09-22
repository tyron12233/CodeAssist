package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An enum entry whose name is also a `java.lang` class: `System`, `Thread`, `Character`, `Process`.
 *
 * A bare branch label in a `when` over an enum subject is the ENTRY, always. `java.lang.*` is imported into
 * every Kotlin file by default, so a resolver that looks up the bare name as a type before trying the
 * subject's entries binds `System` to `java.lang.System` and the interpreter then fails on the receiver with
 * "`java.lang.System` has no object/companion instance". Reported from a real project (2026-09-22) whose
 * theme enum was `enum class DarkMode { System, Light, Dark }`; the preview rendered partially.
 */
class EnumEntryShadowingJdkClassTest {

    @Test fun whenBranchBindsTheEnumEntryNotTheJdkClassOfTheSameName() {
        val code = """
            enum class DarkMode {
                System, Light, Dark;

                fun resolve(systemIsDark: Boolean): Boolean = when (this) {
                    System -> systemIsDark
                    Light -> false
                    Dark -> true
                }
            }
            fun main(): Boolean = DarkMode.System.resolve(true)
        """.trimIndent()
        assertEquals(true, runProgram(code, "main/0", emptyList()))
    }

    @Test fun theOtherBranchesStillAnswerWhenTheSubjectIsNotTheShadowingEntry() {
        val code = """
            enum class DarkMode {
                System, Light, Dark;

                fun resolve(systemIsDark: Boolean): Boolean = when (this) {
                    System -> systemIsDark
                    Light -> false
                    Dark -> true
                }
            }
            fun main(): Boolean = DarkMode.Dark.resolve(false)
        """.trimIndent()
        assertEquals(true, runProgram(code, "main/0", emptyList()))
    }
}
