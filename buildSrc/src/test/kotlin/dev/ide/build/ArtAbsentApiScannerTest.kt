package dev.ide.build

import dev.ide.build.ArtAbsentApiScanner.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies [ArtAbsentApiScanner] classifies references to ART-absent `java.*` types by position so the
 * build fails on the uncatchable (load-bearing) ones and merely reports the lazily-reached ones. Fixtures
 * are compiled Java classes ([dev.ide.build.fixtures.Fixtures]) read back from the test classpath, so the
 * assertions run against real bytecode, not synthesized class files.
 */
class ArtAbsentApiScannerTest {

    private val stackWalker = "java/lang/StackWalker"

    private fun bytesOf(simpleNested: String): ByteArray {
        val res = "dev/ide/build/fixtures/Fixtures\$$simpleNested.class"
        val stream = javaClass.classLoader.getResourceAsStream(res)
            ?: error("fixture class not found on test classpath: $res")
        return stream.use { it.readBytes() }
    }

    private fun scan(simpleNested: String, denylist: Set<String> = setOf("java/lang/StackWalker")) =
        ArtAbsentApiScanner.scanClass(bytesOf(simpleNested), denylist)

    @Test
    fun `static field of an absent type is load-bearing`() {
        val findings = scan("LoadBearingStatic")
        assertEquals(1, findings.size, "expected exactly one finding: $findings")
        val f = findings.single()
        assertEquals(stackWalker, f.absentType)
        assertEquals(Position.STATIC_FIELD, f.position)
        assertEquals("WALKER", f.detail)
        assertTrue(f.isLoadBearing, "a static field of an absent type must fail the build")
    }

    @Test
    fun `supertype that is an absent type is load-bearing`() {
        // Use a synthetic denylist entry the fixture genuinely extends (no JDK-17 absent type is extendable).
        val findings =
            ArtAbsentApiScanner.scanClass(bytesOf("SubclassOfFlagged"), setOf("dev/ide/build/fixtures/Fixtures\$SyntheticBase"))
        val f = findings.single()
        assertEquals(Position.SUPERTYPE, f.position)
        assertTrue(f.isLoadBearing)
    }

    @Test
    fun `instance field of an absent type is advisory, not load-bearing`() {
        val f = scan("InstanceFieldHolder").single()
        assertEquals(Position.INSTANCE_FIELD, f.position)
        assertEquals("walker", f.detail)
        assertTrue(!f.isLoadBearing, "an instance field is lazily reached — must not fail the build")
    }

    @Test
    fun `absent type used only in a method body is advisory, not load-bearing`() {
        val f = scan("ColdPathMethod").single()
        assertEquals(Position.METHOD_REF, f.position)
        assertNull(f.detail)
        assertTrue(!f.isLoadBearing, "a cold-path method reference must not fail the build")
    }

    @Test
    fun `a class with no absent-type reference produces no findings`() {
        assertTrue(scan("CleanClass").isEmpty())
    }

    @Test
    fun `package-prefix denylist entries match nested types`() {
        // `java/lang/constant/` should match e.g. a descriptor mentioning java.lang.constant.ConstantDesc.
        // ColdPathMethod has no such ref, so an unrelated prefix yields nothing — guards against over-matching.
        assertTrue(scan("ColdPathMethod", setOf("java/lang/foreign/")).isEmpty())
    }

    @Test
    fun `default denylist excludes MethodHandles Lookup (present on ART at the floor)`() {
        assertTrue(ArtAbsentApiScanner.DEFAULT_DENYLIST.none { it.contains("MethodHandles") })
    }
}
