package dev.ide.interp.impl

import dev.ide.interp.api.BytecodeConfig
import dev.ide.interp.api.InterpretException
import dev.ide.interp.impl.fixtures.Counter
import dev.ide.platform.ServiceLookup
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * A bytecode session driven the way a plugin drives one: construct one of the project's classes, call into
 * it, read its state back, and hand it to real code as the interface it implements.
 *
 * The fixtures are compiled by this module's own test source set, so the session runs REAL bytecode and the
 * same classes loaded normally are the oracle.
 */
class BytecodeSessionTest {

    private val interpreter = CodeInterpreterImpl(
        lowering = { error("this test never lowers source") },
        projectSandbox = { emptySet() },
        appServices = ServiceLookup.Empty,
    )

    /** The test classpath, so the session reads the fixtures' class files rather than the host's loaded copies. */
    private fun classpath(): List<Path> =
        System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Paths.get(it) }

    private fun session() = interpreter.openBytecode(
        BytecodeConfig(
            classpath = classpath(),
            interpretPrefixes = listOf("dev.ide.interp.impl.fixtures."),
        )
    )

    @Test
    fun `constructs a class, calls it, and reads its state`() {
        session().use { s ->
            val counter = s.construct(Counter::class.java.name, listOf(7))
            assertEquals("dev.ide.interp.impl.fixtures.Counter", counter.typeFqn)
            assertEquals(9, counter.call("add", listOf(2)))
            assertEquals(9, counter.call("value"))
            // The field lives on the interpreted object, not on the peer, so this is the accessor that reaches it.
            assertEquals(9, counter.get("value"))
            counter.set("value", 100)
            assertEquals(100, counter.call("value"))
        }
    }

    @Test
    fun `interpreted statics are the session's own, not the host's`() {
        val hostBefore = Counter.created
        session().use { s ->
            s.construct(Counter::class.java.name, listOf(1))
            s.construct(Counter::class.java.name, listOf(2))
            assertEquals(2, s.readStatic(Counter::class.java.name, "created"))
        }
        assertEquals(hostBefore, Counter.created, "the real class must be untouched by an interpreted run")
    }

    @Test
    fun `a static call picks the overload by argument count`() {
        session().use { s ->
            assertEquals("n=3", s.callStatic(Counter::class.java.name, "describe", listOf(3)))
        }
    }

    @Test
    fun `an interface-only class crosses out as the real interface`() {
        session().use { s ->
            val ticker = s.construct("dev.ide.interp.impl.fixtures.Ticker")
            val runnable = ticker.proxy(Runnable::class.java)
            // What a plugin actually does with this: hand it to code that knows nothing about interpretation.
            runnable.run()
            runnable.run()
            assertEquals(2, ticker.get("ticks"))
        }
    }

    @Test
    fun `a fresh session starts with fresh state`() {
        val first = session().use { s ->
            s.construct(Counter::class.java.name, listOf(1))
            s.readStatic(Counter::class.java.name, "created")
        }
        val second = session().use { s ->
            s.construct(Counter::class.java.name, listOf(1))
            s.readStatic(Counter::class.java.name, "created")
        }
        assertEquals(first, second, "each session loads the classes again, so statics reset")
    }

    @Test
    fun `a class the policy does not interpret is reported, not silently bridged`() {
        session().use { s ->
            val e = assertFailsWith<InterpretException> { s.construct("java.util.ArrayList") }
            assertTrue("not interpretable" in e.message!!, e.message!!)
        }
    }

    @Test
    fun `a missing member names what the class does have`() {
        session().use { s ->
            val counter = s.construct(Counter::class.java.name, listOf(0))
            val e = assertFailsWith<InterpretException> { counter.call("nope") }
            assertTrue("add" in e.message!!, e.message!!)
        }
    }

    @Test
    fun `a closed session refuses further calls`() {
        val s = session()
        s.dispose()
        assertFailsWith<InterpretException> { s.construct(Counter::class.java.name, listOf(0)) }
    }

    @Test
    fun `two sessions hold separate objects`() {
        session().use { a ->
            session().use { b ->
                val one = a.construct(Counter::class.java.name, listOf(1))
                val two = b.construct(Counter::class.java.name, listOf(1))
                assertNotSame(one.raw, two.raw)
            }
        }
    }
}

/** [dev.ide.platform.Disposable] is not [AutoCloseable], so a test that wants `use` brings its own. */
private inline fun <T : dev.ide.interp.api.InterpretSession, R> T.use(body: (T) -> R): R =
    try { body(this) } finally { dispose() }
