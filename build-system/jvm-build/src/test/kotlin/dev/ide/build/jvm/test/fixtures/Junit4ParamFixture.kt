package dev.ide.build.jvm.test.fixtures

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.junit.runners.Parameterized

/** JUnit 4's Parameterized runner, by constructor (see TestRunnerTest). The set [3, 3] fails on purpose. */
@RunWith(Parameterized::class)
class Junit4ParamFixture(private val input: Int, private val expected: Int) {
    @Test fun doubles() = assertEquals(expected, input * 2)

    companion object {
        @JvmStatic @Parameterized.Parameters
        fun data(): Collection<Array<Any>> = listOf(arrayOf(1, 2), arrayOf(2, 4), arrayOf(3, 3))
    }
}

/** JUnit 4's Parameterized runner, by `@Parameter` fields. */
@RunWith(Parameterized::class)
class Junit4FieldParamFixture {
    @JvmField @Parameterized.Parameter(0) var word: String = ""
    @JvmField @Parameterized.Parameter(1) var length: Int = 0

    @Test fun measures() = assertEquals(length, word.length)

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data(): Array<Array<Any>> = arrayOf(arrayOf("ab", 2), arrayOf("abc", 3))
    }
}

/** A class naming JUnit 4's default runner explicitly runs like any other. */
@RunWith(JUnit4::class)
class Junit4RunWithFixture {
    @Test fun runs() = assertEquals(1, 1)
}
