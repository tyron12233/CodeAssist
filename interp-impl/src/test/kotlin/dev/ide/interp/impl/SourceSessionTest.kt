package dev.ide.interp.impl

import dev.ide.interp.api.HookDecision
import dev.ide.interp.api.InterpretConfig
import dev.ide.interp.api.InterpretException
import dev.ide.interp.api.InterpretHooks
import dev.ide.interp.api.InterpretSession
import dev.ide.interp.api.SandboxCategories
import dev.ide.platform.ServiceLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A source session driven the way a framework plugin drives one: no compile step, the user's class
 * instantiated from source, its lifecycle methods called in order, and the object handed to real code as the
 * interface it implements.
 *
 * That last one is the shape a framework needs (LibGDX's `ApplicationListener`, a `Runnable`, a listener),
 * and it is what makes a plugin's own preview possible at all.
 */
class SourceSessionTest {

    private fun interpreter(sandbox: Set<String> = emptySet()) = CodeInterpreterImpl(
        lowering = { error("this test lowers through the harness") },
        projectSandbox = { sandbox },
        appServices = ServiceLookup.Empty,
    )

    private fun open(
        code: String,
        entry: String,
        config: InterpretConfig = InterpretConfig(),
        sandbox: Set<String> = emptySet(),
    ) = interpreter(sandbox).openSource(loweredProgram(code, entry), config)

    @Test
    fun `calls a top-level function of the program`() {
        val s = open(
            """
            fun greet(name: String): String = "hello, " + name
            """.trimIndent(),
            "greet",
        )
        s.use { assertEquals("hello, world", it.call("greet", listOf("world"))) }
    }

    @Test
    fun `instantiates one of the project's classes and drives it`() {
        val s = open(
            """
            class Scene(val name: String) {
                var frames = 0
                fun render(): String { frames++; return name + ":" + frames }
                fun reset() { frames = 0 }
            }
            """.trimIndent(),
            "Scene",
        )
        s.use { session ->
            val scene = session.instantiate("Scene", listOf("main"))
            assertEquals("Scene", scene.typeFqn)
            assertEquals("main:1", scene.call("render"))
            assertEquals("main:2", scene.call("render"))
            assertEquals(2, scene.get("frames"))
            scene.call("reset")
            assertEquals(0, scene.get("frames"))
            scene.set("frames", 41)
            assertEquals("main:42", scene.call("render"))
        }
    }

    @Test
    fun `an interpreted object crosses out as a real interface a framework can hold`() {
        val s = open(
            """
            class Ticker : Runnable {
                var ticks = 0
                override fun run() { ticks++ }
            }
            """.trimIndent(),
            "Ticker",
        )
        s.use { session ->
            val ticker = session.instantiate("Ticker")
            val runnable: Runnable = ticker.proxy(Runnable::class.java)
            // Real code, with no idea an interpreter is involved.
            java.lang.Thread(runnable).apply { start(); join() }
            runnable.run()
            assertEquals(2, ticker.get("ticks"))
        }
    }

    @Test
    fun `proxying a class rather than an interface is refused with the reason`() {
        val s = open("class Empty", "Empty")
        s.use { session ->
            val e = assertFailsWith<InterpretException> {
                session.instantiate("Empty").proxy(java.lang.Thread::class.java)
            }
            assertTrue("not an interface" in e.message!!, e.message!!)
        }
    }

    @Test
    fun `state accumulates across calls within one session and resets in a new one`() {
        val code = """
            class Counter {
                var n = 0
                fun bump(): Int { n++; return n }
            }
            """.trimIndent()
        open(code, "Counter").use { session ->
            val c = session.instantiate("Counter")
            c.call("bump")
            assertEquals(2, c.call("bump"))
        }
        open(code, "Counter").use { session ->
            assertEquals(1, session.instantiate("Counter").call("bump"))
        }
    }

    @Test
    fun `a missing entry names what the program has`() {
        val s = open("fun a() = 1", "a")
        s.use { session ->
            val e = assertFailsWith<InterpretException> { session.call("b") }
            assertTrue("a/0" in e.message!!, e.message!!)
        }
    }

    @Test
    fun `the plugin's own hooks can stand in for a call into real code`() {
        val hooks = object : InterpretHooks {
            override fun beforeCall(
                ownerFqn: String?,
                member: String,
                receiver: Any?,
                args: List<Any?>,
            ): HookDecision =
                if (member == "nextInt") HookDecision.Replace(7) else HookDecision.Proceed
        }
        val s = open(RANDOM_PROGRAM, "roll", InterpretConfig(hooks = hooks))
        // What a preview does with this: a fixed source of randomness, so the rendered frame is the same
        // every time it is drawn.
        s.use { assertEquals(7, it.call("roll")) }
    }

    @Test
    fun `a hook sees the owner and member of a call into real code`() {
        val seen = mutableListOf<String>()
        val hooks = object : InterpretHooks {
            override fun beforeCall(
                ownerFqn: String?,
                member: String,
                receiver: Any?,
                args: List<Any?>,
            ): HookDecision {
                seen += "$ownerFqn.$member"
                return HookDecision.Proceed
            }
        }
        open(RANDOM_PROGRAM, "roll", InterpretConfig(hooks = hooks)).use { it.call("roll") }
        assertTrue("kotlin.random.Random.nextInt" in seen, seen.toString())
    }

    @Test
    fun `a hook denial fails the call with its reason`() {
        val hooks = object : InterpretHooks {
            override fun beforeCall(
                ownerFqn: String?,
                member: String,
                receiver: Any?,
                args: List<Any?>,
            ): HookDecision =
                if (member == "nextInt") HookDecision.Deny("no randomness in a preview")
                else HookDecision.Proceed
        }
        val s = open(RANDOM_PROGRAM, "roll", InterpretConfig(hooks = hooks))
        s.use { session ->
            val e = assertFailsWith<InterpretException> { session.call("roll") }
            assertTrue("no randomness in a preview" in e.message!!, e.message!!)
        }
    }

    @Test
    fun `a config naming no categories overrides the project's, and reports nothing`() {
        val s = open(
            RANDOM_PROGRAM,
            "roll",
            InterpretConfig(sandbox = emptySet()),
            sandbox = SandboxCategories.ALL,
        )
        s.use { session ->
            assertTrue(session.call("roll") is Int)
            assertTrue(session.problems.isEmpty())
        }
    }

    @Test
    fun `a closed session refuses further calls`() {
        val s = open("fun a() = 1", "a")
        s.dispose()
        assertFailsWith<InterpretException> { s.call("a") }
    }

    @Test
    fun `an exception from the user's own code is reported as one failure type`() {
        val s = open("fun boom(): Int = throw IllegalStateException(\"bang\")", "boom")
        s.use { session ->
            val e = assertFailsWith<InterpretException> { session.call("boom") }
            assertTrue("bang" in e.message!!, e.message!!)
        }
    }
}

/**
 * A program whose call really does cross into real code, which is what a hook test needs: many stdlib calls
 * are intrinsics the interpreter answers itself, and those never reach the boundary.
 */
private val RANDOM_PROGRAM = """
    import kotlin.random.Random
    fun roll(): Int = Random(1).nextInt(10)
""".trimIndent()

/** [dev.ide.platform.Disposable] is not [AutoCloseable], so a test that wants `use` brings its own. */
private inline fun <T : InterpretSession, R> T.use(body: (T) -> R): R =
    try { body(this) } finally { dispose() }
