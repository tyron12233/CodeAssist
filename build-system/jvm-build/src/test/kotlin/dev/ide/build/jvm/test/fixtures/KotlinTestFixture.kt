package dev.ide.build.jvm.test.fixtures

import kotlin.test.Test
import kotlin.test.assertEquals

/** kotlin.test: its assertions live in kotlin-test, which a test run must interpret (it is not in the IDE). */
class KotlinTestFixture {
    @Test fun kotlinTestPasses() = assertEquals("ab", "a" + "b")
    @Test fun kotlinTestFails() = assertEquals("expected", "actual")
}
