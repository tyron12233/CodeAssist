package dev.ide.android.support

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unpacking a `natives`-scoped dependency into the `<abi>/lib*.so` layout the packager reads.
 *
 * The shape under test is libGDX's: `gdx-platform-1.14.2-natives-arm64-v8a.jar` holds one `libgdx.so` at
 * the archive root, so the ABI exists only in the file name's classifier. A jar already laid out under
 * `lib/<abi>/` has to keep working too, since that is the form an ordinary classpath jar carries.
 */
class NativeLibrariesTest {

    @Test
    fun aRootLevelSoIsPlacedUnderTheAbiFromTheClassifier() {
        withDirs { dir, out ->
            val jar = jar(dir, "gdx-platform-1.14.2-natives-arm64-v8a.jar", "libgdx.so")
            val unpacked = NativeLibraries.unpack(listOf(jar), out)

            assertTrue(unpacked.warnings.isEmpty(), "unexpected warnings: ${unpacked.warnings}")
            val root = unpacked.dirs.single()
            assertEquals(listOf("arm64-v8a/libgdx.so"), relativeFiles(root))
        }
    }

    @Test
    fun eachAbiJarLandsInItsOwnAbiDirectory() {
        withDirs { dir, out ->
            val jars = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").map {
                jar(dir, "gdx-platform-1.14.2-natives-$it.jar", "libgdx.so")
            }
            val unpacked = NativeLibraries.unpack(jars, out)

            assertEquals(4, unpacked.dirs.size, "one output directory per jar")
            assertEquals(
                listOf("arm64-v8a/libgdx.so", "armeabi-v7a/libgdx.so", "x86/libgdx.so", "x86_64/libgdx.so"),
                unpacked.dirs.flatMap { relativeFiles(it) }.sorted(),
            )
        }
    }

    @Test
    fun anAlreadyPackagedLayoutIsKeptVerbatim() {
        withDirs { dir, out ->
            // `lib/<abi>/` is the packaged form, and it is authoritative over the file name: this jar's
            // classifier says arm64 while its contents say armeabi-v7a, and the contents win.
            val jar = jar(dir, "some-lib-1.0-natives-arm64-v8a.jar", "lib/armeabi-v7a/libfoo.so")
            val unpacked = NativeLibraries.unpack(listOf(jar), out)

            assertEquals(listOf("armeabi-v7a/libfoo.so"), relativeFiles(unpacked.dirs.single()))
        }
    }

    @Test
    fun anAbiPrefixedLayoutIsKeptVerbatim() {
        withDirs { dir, out ->
            val jar = jar(dir, "natives-bundle-1.0.jar", "arm64-v8a/libfoo.so", "x86_64/libfoo.so")
            val unpacked = NativeLibraries.unpack(listOf(jar), out)

            assertEquals(
                listOf("arm64-v8a/libfoo.so", "x86_64/libfoo.so"),
                relativeFiles(unpacked.dirs.single()),
            )
        }
    }

    @Test
    fun aDesktopClassifierNamesNoAndroidAbiSoItIsReportedNotGuessedAt() {
        withDirs { dir, out ->
            // The mistake this catches: declaring `natives-desktop` in an Android module. Its `.so` files are
            // for a host JVM, there is no ABI to file them under, and packaging them anyway would ship an
            // unloadable library. Silence here is what made the declaration look like it had worked.
            val jar = jar(dir, "gdx-platform-1.14.2-natives-desktop.jar", "libgdx64.so")
            val unpacked = NativeLibraries.unpack(listOf(jar), out)

            assertTrue(unpacked.dirs.isEmpty(), "nothing packageable: ${unpacked.dirs}")
            val warning = unpacked.warnings.single()
            assertTrue("gdx-platform-1.14.2-natives-desktop.jar" in warning, "must name the jar: $warning")
            assertTrue("natives-arm64-v8a" in warning, "must name what to declare instead: $warning")
        }
    }

    @Test
    fun aJarWithNoNativeLibraryAtAllIsReported() {
        withDirs { dir, out ->
            val jar = jar(dir, "plain-1.0.jar", "pkg/Foo.class")
            val unpacked = NativeLibraries.unpack(listOf(jar), out)

            assertTrue(unpacked.dirs.isEmpty())
            assertEquals(1, unpacked.warnings.size, "one warning per jar that gave nothing: ${unpacked.warnings}")
        }
    }

    @Test
    fun aSecondUnpackReusesTheDirectoryInsteadOfRewritingIt() {
        withDirs { dir, out ->
            val jar = jar(dir, "gdx-platform-1.14.2-natives-arm64-v8a.jar", "libgdx.so")
            val first = NativeLibraries.unpack(listOf(jar), out).dirs.single()
            val stamp = Files.getLastModifiedTime(first.resolve("arm64-v8a/libgdx.so"))

            val second = NativeLibraries.unpack(listOf(jar), out).dirs.single()
            assertEquals(first, second)
            assertEquals(stamp, Files.getLastModifiedTime(second.resolve("arm64-v8a/libgdx.so")),
                "the marker should short-circuit the re-extraction")
        }
    }

    @Test
    fun theAbiIsReadFromTheClassifierWithoutMistakingX86_64ForX86() {
        assertEquals("arm64-v8a", NativeLibraries.abiFromFileName("gdx-platform-1.14.2-natives-arm64-v8a.jar"))
        assertEquals("armeabi-v7a", NativeLibraries.abiFromFileName("gdx-platform-1.14.2-natives-armeabi-v7a.jar"))
        assertEquals("x86_64", NativeLibraries.abiFromFileName("gdx-platform-1.14.2-natives-x86_64.jar"))
        assertEquals("x86", NativeLibraries.abiFromFileName("gdx-platform-1.14.2-natives-x86.jar"))
        assertEquals(null, NativeLibraries.abiFromFileName("gdx-platform-1.14.2-natives-desktop.jar"))
        assertEquals(null, NativeLibraries.abiFromFileName("gdx-platform-1.14.2.jar"))
    }

    private fun withDirs(body: (jars: Path, out: Path) -> Unit) {
        val root = createTempDirectory("natives-test")
        body(Files.createDirectories(root.resolve("jars")), root.resolve("out"))
    }

    private fun jar(dir: Path, name: String, vararg entries: String): Path {
        val path = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); z.closeEntry()
            for (e in entries) { z.putNextEntry(ZipEntry(e)); z.write(ByteArray(64) { it.toByte() }); z.closeEntry() }
        }
        return path
    }

    private fun relativeFiles(root: Path): List<String> =
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .filter { it != ".unpacked" }
                .sorted()
                .collect(java.util.stream.Collectors.toList())
        }
}
