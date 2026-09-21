package dev.ide.deps.impl

import dev.ide.platform.createDirectories
import dev.ide.platform.deleteFile
import dev.ide.platform.fileInfo
import dev.ide.platform.listDirectory
import dev.ide.platform.readFile
import dev.ide.platform.resolvePath
import dev.ide.platform.writeFileAtomically
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Getting the classes out of an `.aar`, on whatever platform this runs on.
 *
 * It matters because of where Android code LIVES: every `androidx.*` and Material artifact ships its classes
 * inside an `.aar`, so a platform that cannot open one resolves nothing from the Android ecosystem. That was
 * the iOS host until this: an Android project opened there had a classpath of whatever plain jars it
 * happened to use and unresolved references to everything else.
 *
 * The two actuals do different amounts of work on purpose, and only the part below is common to them. The
 * JVM one also unpacks `res/`, `assets/`, `jni/` and the manifest, because aapt2 and the Android build read
 * a library's resources out of that directory; nothing on iOS does, so nothing there writes them.
 */
class AarClassesTest {

    private val dir = scratchPath("aar-${nowSuffix()}")

    @AfterTest
    fun cleanUp() {
        deleteTree(dir)
    }

    /**
     * The library's `classes.jar`: a real (if tiny) archive, not a stand-in string.
     *
     * It has to open as a zip with something in it, because the JVM actual VALIDATES what it copied and
     * replaces an unusable one with a manifest-only jar. That check exists for the AARs that really do ship
     * a zero-entry `classes.jar`, and it is also the reason a fixture cannot get away with arbitrary bytes.
     */
    private val classes = TINY_JAR

    private fun writeAar(name: String, bytes: ByteArray): String {
        createDirectories(dir)
        val path = resolvePath(dir, name)
        assertTrue(writeFileAtomically(path, bytes), "could not stage $path")
        return path
    }

    @Test
    fun anAarsClassesJarIsExtractedByteForByte() {
        val aar = writeAar("widget-1.0.aar", aarBytes(classes))
        val exploded = resolvePath(dir, "exploded")

        val classRoot = assertNotNull(explodeAar(aar, exploded), "an .aar with code has a class root")

        assertTrue(classRoot.endsWith("classes.jar"), classRoot)
        assertContentEquals(classes, readFile(classRoot), "a copy the compiler could read, not a re-zip")
    }

    /**
     * The second call is the common one: a project resolves its dependencies on every open, and re-unpacking
     * every AAR each time is work nobody asked for.
     */
    @Test
    fun anAlreadyExplodedAarIsNotUnpackedAgain() {
        val aar = writeAar("widget-1.0.aar", aarBytes(classes))
        val exploded = resolvePath(dir, "exploded")
        val first = assertNotNull(explodeAar(aar, exploded))

        // Delete the archive: a second explode that reads it would fail, and one that reuses the directory
        // cannot tell the difference.
        assertTrue(deleteFile(aar))
        val second = assertNotNull(explodeAar(aar, exploded), "the exploded directory is the answer now")

        assertEquals(first, second)
        assertContentEquals(classes, readFile(second))
    }

    /** A file that is not an archive at all is answered, not thrown: one bad artifact is one coordinate. */
    @Test
    fun somethingThatIsNotAnArchiveDoesNotBringTheResolveDown() {
        val aar = writeAar("broken-1.0.aar", "this is not a zip".encodeToByteArray())

        // Null here, an exception there: the call site treats either as this one coordinate contributing
        // no classes, which is why the actuals were never made to agree on which.
        val classRoot = runCatching { explodeAar(aar, resolvePath(dir, "broken")) }.getOrNull()

        assertTrue(
            classRoot == null || fileInfo(classRoot) == null,
            "a corrupt archive yields no usable class root, however it reports that: $classRoot",
        )
    }
}

/** Remove [path] and everything under it. A copy of the one in `ResolveOffTheJvmTest`, which is file-private
 *  there for the same reason it is here: a test helper, not a fixture worth a module. */
private fun deleteTree(path: String) {
    val info = fileInfo(path) ?: return
    if (info.isDirectory) for (child in listDirectory(path)) deleteTree(child)
    deleteFile(path)
}
