package dev.ide.build.jvm.test.fixtures

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/** JUnit 4 shapes for the IDE's test runner (see TestRunnerTest). Several fail on purpose. */
class Junit4Fixture {
    @Before fun before() = println("junit4 before")

    @Test fun passes() = assertTrue(true)
    @Test(expected = IllegalStateException::class) fun expectedThrows() { throw IllegalStateException("as expected") }
    @Test(expected = IllegalStateException::class) fun expectedMissing() {}
    @Ignore @Test fun ignored() { error("must not run") }
}
