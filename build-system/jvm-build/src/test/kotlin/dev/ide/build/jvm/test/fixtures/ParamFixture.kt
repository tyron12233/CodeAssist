package dev.ide.build.jvm.test.fixtures

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.util.stream.Stream

enum class Color { RED, GREEN, BLUE }

/** JUnit 5 parameterized, repeated and nested shapes for the IDE's test runner (see TestRunnerTest). */
class ParamFixture {
    var log = ""

    @BeforeEach fun outerBefore() { log += "outer-before;" }
    @AfterEach fun outerAfter() = println("outer after")

    @ParameterizedTest @ValueSource(ints = [1, 2, 3])
    fun positive(n: Int) = assertTrue(n > 0)

    @ParameterizedTest @ValueSource(strings = ["a", "bb", "ccc"])
    fun lengths(s: String) = assertTrue(s.length < 3, "too long: $s")

    @ParameterizedTest @CsvSource("1, 2, 3", "2, 3, 5", "'x, y', 0, 0")
    fun sums(a: String, b: Long, sum: Double) {
        if (a.startsWith("x")) return
        assertEquals(sum, a.toInt() + b.toDouble())
    }

    @ParameterizedTest @CsvSource("1.50, GREEN")
    fun converted(amount: BigDecimal, color: Color) {
        assertEquals(BigDecimal("1.50"), amount)
        assertEquals(Color.GREEN, color)
    }

    @ParameterizedTest @EnumSource(Color::class, names = ["RED", "BLUE"])
    fun colors(c: Color) = assertTrue(c != Color.GREEN)

    @ParameterizedTest @NullAndEmptySource
    fun blank(s: String?) = assertTrue(s.isNullOrEmpty())

    @ParameterizedTest @MethodSource("pairs")
    fun fromMethod(a: Int, b: String) = assertEquals(b, "n$a")

    @ParameterizedTest @MethodSource("singles")
    fun fromStream(n: Int) = assertTrue(n % 2 == 0, "odd: $n")

    @RepeatedTest(3)
    fun repeated() { counter++ }

    @Nested
    inner class Inner {
        @BeforeEach fun innerBefore() { log += "inner-before;" }

        @Test fun seesOuterSetup() = assertEquals("outer-before;inner-before;", log)
    }

    companion object {
        var counter = 0

        @JvmStatic fun pairs(): Stream<Arguments> = Stream.of(Arguments.of(1, "n1"), Arguments.of(2, "n2"))
        @JvmStatic fun singles(): List<Int> = listOf(2, 3, 4)
    }
}
