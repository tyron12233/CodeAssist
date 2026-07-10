package dev.ide.build.engine

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-tests [ReflectiveMainLauncher]'s entry-point resolution and invocation against Java fixtures shaped
 * like every Kotlin/JVM `main` form (see [LauncherFixtures]). Resolution (`resolve`) is asserted directly;
 * the success paths are also driven end-to-end through `main` (which does NOT call `System.exit` on success),
 * proving construct-and-invoke. The failure paths are checked via `resolve` only — calling `main` there would
 * `System.exit` the test JVM.
 */
class ReflectiveMainLauncherTest {

    @BeforeTest
    @AfterTest
    fun clear() = LauncherFixtures.EVENTS.clear()

    private fun launch(cls: Class<*>, vararg args: String) =
        ReflectiveMainLauncher.main(arrayOf(cls.name, *args))

    @Test
    fun resolvesStaticMainWithArgs() {
        val e = ReflectiveMainLauncher.resolve(LauncherFixtures.StaticArgs::class.java)!!
        assertTrue(e.isStatic); assertTrue(e.takesArgs)
        launch(LauncherFixtures.StaticArgs::class.java, "hello")
        assertEquals(listOf("staticArgs:hello"), LauncherFixtures.EVENTS)
    }

    @Test
    fun resolvesStaticNoArgMain() {
        // The @JvmStatic fun main() (no-arg) shape: a static main() with no (String[]) bridge — the case the
        // native dalvikvm/java launcher can't start, which is precisely why we route through this launcher.
        val e = ReflectiveMainLauncher.resolve(LauncherFixtures.StaticNoArg::class.java)!!
        assertTrue(e.isStatic); assertTrue(!e.takesArgs)
        launch(LauncherFixtures.StaticNoArg::class.java, "ignored")
        assertEquals(listOf("staticNoArg"), LauncherFixtures.EVENTS)
    }

    @Test
    fun resolvesInstanceMainWithArgs() {
        val e = ReflectiveMainLauncher.resolve(LauncherFixtures.InstanceArgs::class.java)!!
        assertTrue(!e.isStatic); assertTrue(e.takesArgs)
        launch(LauncherFixtures.InstanceArgs::class.java, "a", "b")
        assertEquals(listOf("instanceArgs:a,b"), LauncherFixtures.EVENTS)
    }

    @Test
    fun resolvesInstanceNoArgMain() {
        val e = ReflectiveMainLauncher.resolve(LauncherFixtures.InstanceNoArg::class.java)!!
        assertTrue(!e.isStatic); assertTrue(!e.takesArgs)
        launch(LauncherFixtures.InstanceNoArg::class.java)
        assertEquals(listOf("instanceNoArg"), LauncherFixtures.EVENTS)
    }

    @Test
    fun noRunnableMainResolvesNull() {
        assertNull(ReflectiveMainLauncher.resolve(LauncherFixtures.NoMain::class.java))
    }

    @Test
    fun nonVoidMainResolvesNull() {
        assertNull(ReflectiveMainLauncher.resolve(LauncherFixtures.NonVoidMain::class.java))
    }

    @Test
    fun staticMainBeatsInstanceMain() {
        val e = ReflectiveMainLauncher.resolve(LauncherFixtures.StaticBeatsInstance::class.java)!!
        assertTrue(e.isStatic)
        launch(LauncherFixtures.StaticBeatsInstance::class.java, "x")
        assertEquals(listOf("staticNoArg-wins"), LauncherFixtures.EVENTS)
    }

    @Test
    fun findsInheritedStaticMain() {
        val e = ReflectiveMainLauncher.resolve(LauncherFixtures.Sub::class.java)!!
        assertTrue(e.isStatic); assertTrue(e.takesArgs)
        launch(LauncherFixtures.Sub::class.java, "z")
        assertEquals(listOf("base:z"), LauncherFixtures.EVENTS)
    }
}
