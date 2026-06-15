package dev.ide.lang.jdt.env

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared [JdtEnvironmentCache] is the optimization that took completion off the GC (jrt index +
 * package set built once per analyzer instead of per resolve). These tests pin its correctness: the
 * keystroke-invariant state is right and reusable, the per-instance overlay is *not* shared, and a
 * shared environment survives the `cleanup()` ecj calls mid-resolution (which must not tear down state
 * the next splice-variant resolve still needs).
 */
class JdtEnvironmentCacheTest {

    private val jdkHome = Path.of(System.getProperty("java.home"))
    private fun compound(vararg parts: String): Array<CharArray> = parts.map { it.toCharArray() }.toTypedArray()

    @Test
    fun buildsPlatformIndexAndPackagesOnce() {
        val cache = JdtEnvironmentCache(sourceRoots = emptyList(), classpathJars = emptyList(), jdkHome = jdkHome)
        try {
            assertTrue("java.lang.String" in cache.jrtIndex, "platform class index should contain java.lang.String")
            assertNotNull(cache.jrtBytes("java.util.List"), "should read platform bytecode from the jrt image")
            // every dotted prefix of a platform class is a package
            assertTrue(cache.isStaticPackage("java"))
            assertTrue(cache.isStaticPackage("java.util"))
            assertTrue(cache.isStaticPackage("java.util.concurrent"))
            assertFalse(cache.isStaticPackage("definitely.not.a.package"))
            // the lazies are idempotent — same map identity across reads (built once, then reused)
            assertTrue(cache.jrtIndex === cache.jrtIndex)
        } finally {
            cache.close()
        }
    }

    @Test
    fun sharedCacheIsolatesPerInstanceOverlayAndSurvivesCleanup() {
        val cache = JdtEnvironmentCache(sourceRoots = emptyList(), classpathJars = emptyList(), jdkHome = jdkHome)
        try {
            val envA = JdtNameEnvironment(
                cache, sourceRoots = emptyList(),
                overlay = mapOf("a.Foo" to "package a; public class Foo {}".toCharArray()),
                excludedTypes = emptySet(), ownsCache = false,
            )
            val envB = JdtNameEnvironment(
                cache, sourceRoots = emptyList(),
                overlay = mapOf("b.Bar" to "package b; public class Bar {}".toCharArray()),
                excludedTypes = emptySet(), ownsCache = false,
            )

            // each env sees ONLY its own overlay type — the overlay is per-instance, not cached/shared
            assertNotNull(envA.findType(compound("a", "Foo")))
            assertNull(envA.findType(compound("b", "Bar")), "envA must not see envB's overlay")
            assertNotNull(envB.findType(compound("b", "Bar")))
            assertNull(envB.findType(compound("a", "Foo")), "envB must not see envA's overlay")

            // overlay-derived packages are likewise per-instance
            assertTrue(envA.isPackage(emptyArray(), "a".toCharArray()))
            assertFalse(envA.isPackage(emptyArray(), "b".toCharArray()))

            // both share the platform from the cache
            assertNotNull(envA.findType(compound("java", "lang", "String")))
            assertNotNull(envB.findType(compound("java", "lang", "String")))

            // ecj calls cleanup() between phases; on a shared env it must NOT release the cache, so a later
            // resolve (here: envB, and another lookup on envA) still works.
            envA.cleanup()
            assertNotNull(envB.findType(compound("java", "util", "List")), "shared cache must survive a sibling's cleanup()")
            assertNotNull(envA.findType(compound("java", "lang", "Integer")), "env still usable after its own no-op cleanup()")
        } finally {
            cache.close()
        }
    }

    @Test
    fun ownedCacheConstructorStillResolves() {
        // the back-compat convenience constructor builds its own (unshared) cache and closes it on cleanup()
        val env = JdtNameEnvironment(
            sourceRoots = emptyList(),
            overlay = mapOf("c.Baz" to "package c; public class Baz {}".toCharArray()),
            classpathJars = emptyList(),
            jdkHome = jdkHome,
        )
        try {
            assertNotNull(env.findType(compound("c", "Baz")))
            assertNotNull(env.findType(compound("java", "lang", "String")))
            assertTrue(env.isPackage(compound("java"), "lang".toCharArray()))
        } finally {
            env.cleanup()
        }
    }

    @Test
    fun platformImageIsSharedAcrossCachesForTheSameHome() {
        // Two per-module caches on the same JDK must share ONE jrt image (not walk the jrt twice / hold two
        // 40k maps). They expose the same map instance because JrtImage is process-cached by home.
        val a = JdtEnvironmentCache(sourceRoots = emptyList(), classpathJars = emptyList(), jdkHome = jdkHome)
        val b = JdtEnvironmentCache(sourceRoots = emptyList(), classpathJars = emptyList(), jdkHome = jdkHome)
        try {
            assertTrue(a.jrtIndex === b.jrtIndex, "caches on the same JDK home must share the jrt index instance")
            // disposing one must not affect the other or the shared image (only its own jar handles)
            a.dispose()
            assertTrue("java.lang.String" in b.jrtIndex)
            assertTrue(b.isStaticPackage("java.util"))
        } finally {
            a.dispose(); b.dispose()
        }
    }

    @Test
    fun addPackagePrefixesCoversEveryDottedPrefix() {
        val out = HashSet<String>()
        JdtEnvironmentCache.addPackagePrefixes("a.b.c.Type", out)
        assertTrue(out.containsAll(listOf("a", "a.b", "a.b.c")))
        assertFalse("a.b.c.Type" in out, "the type's own FQCN is not a package")

        val none = HashSet<String>()
        JdtEnvironmentCache.addPackagePrefixes("TopLevel", none) // default package → no prefixes
        assertTrue(none.isEmpty())
    }
}
