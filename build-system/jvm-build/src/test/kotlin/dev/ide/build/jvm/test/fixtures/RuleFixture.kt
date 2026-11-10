package dev.ide.build.jvm.test.fixtures

import org.hamcrest.CoreMatchers.containsString
import org.junit.After
import org.junit.Assert.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.MethodRule
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import java.io.File

/** JUnit 4 rules for the IDE's test runner (see TestRunnerTest): `@get:Rule`, `@JvmField @Rule` (a rule of the
 *  project's own) and a companion `@ClassRule`. One test fails on purpose, inside the rules. */
class RuleFixture {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val name = TestName()

    /** A rule the project writes itself, as a field. */
    @Suppress("DEPRECATION")
    @Rule @JvmField val thrown: ExpectedException = ExpectedException.none()

    /** A legacy MethodRule: it sees the test's method (and its annotations) and the test instance. */
    @Rule @JvmField val methodRule: MethodRule = MethodRule { base, method, target ->
        object : Statement() {
            override fun evaluate() {
                println("method-rule ${method.name} on ${target.javaClass.simpleName} test=${method.getAnnotation(Test::class.java) != null}")
                base.evaluate()
            }
        }
    }

    @Rule @JvmField val watcher: TestRule = object : TestWatcher() {
        override fun succeeded(description: Description) = println("watched ${description.methodName} ok")
        override fun failed(e: Throwable, description: Description) = println("watched ${description.methodName} failed")
    }

    private var fromBefore: File? = null

    /** The rules wrap the hooks: the folder exists in @Before and still in @After. */
    @Before fun makeFile() { fromBefore = tmp.newFile("from-before.txt") }
    @After fun checkFile() { check(fromBefore!!.exists()) { "the folder was deleted before @After" } }

    @Test fun createsFiles() {
        val f = tmp.newFile("a.txt")
        f.writeText("hi")
        assertEquals("hi", f.readText())
        assertEquals(tmp.root, f.parentFile)
        println("rule-tmp-root=" + tmp.root)
    }

    @Test fun knowsItsName() = assertEquals("knowsItsName", name.methodName)

    /** ExpectedException's matchers are hamcrest TypeSafeMatchers, which find their item type by reflecting over
     *  their own (interpreted) class. */
    @Test fun expectedByRule() {
        thrown.expect(IllegalStateException::class.java)
        thrown.expectMessage("boom")
        throw IllegalStateException("boom")
    }

    @Test fun hamcrestMatchers() = assertThat("temporary", containsString("pora"))

    @Test fun failsInsideRules() {
        tmp.newFile("x")
        fail("on purpose")
    }

    @Test fun classRuleApplies() = assertTrue(shared.root.isDirectory)

    companion object {
        @ClassRule @JvmField val shared = TemporaryFolder()
    }
}

/** `@Rule val` in Kotlin annotates the private backing field, which JUnit rejects: every test fails saying so. */
class RulePrivateFixture {
    @Rule val tmp = TemporaryFolder()

    @Test fun needsTheRule() { tmp.newFile("x") }
}
