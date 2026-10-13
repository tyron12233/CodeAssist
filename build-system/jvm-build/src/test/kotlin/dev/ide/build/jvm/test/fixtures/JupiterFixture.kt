package dev.ide.build.jvm.test.fixtures

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/** JUnit 5 shapes for the IDE's test runner (see TestRunnerTest). Several fail on purpose. */
class JupiterFixture {
    @BeforeEach fun before() = println("before")
    @AfterEach fun after() = println("after")

    @Test fun passes() = assertEquals(4, 2 + 2)
    @Test fun fails() = assertEquals(5, 2 + 2, "math")
    @Test fun `backtick name works`() {}
    @Test fun throws() { error("boom") }
    @Disabled("not today") @Test fun disabled() { error("must not run") }

    companion object {
        @JvmStatic @BeforeAll fun setUpClass() = println("beforeAll")
    }
}
