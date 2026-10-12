package dev.ide.core.sdk

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The toolchain the desktop app ships: found only where the launcher points, and its aapt2 always runnable, even
 * when the installer dropped the exec bit (jpackage does) and the bundle itself must not be changed.
 */
class BundledAndroidToolchainTest {
    private lateinit var dir: Path
    private lateinit var home: Path
    private var savedHome: String? = null

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("toolchain")
        home = Files.createTempDirectory("home")
        savedHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
    }

    @AfterTest fun tearDown() {
        System.clearProperty(BundledAndroidToolchain.DIR_PROPERTY)
        savedHome?.let { System.setProperty("user.home", it) }
        dir.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }

    private fun stage(executable: Boolean): Path {
        Files.write(dir.resolve("android.jar"), byteArrayOf(1))
        val aapt2 = Files.write(dir.resolve("aapt2"), byteArrayOf(1, 2, 3))
        aapt2.toFile().setExecutable(executable)
        System.setProperty(BundledAndroidToolchain.DIR_PROPERTY, dir.toString())
        return aapt2
    }

    @Test
    fun noneWhenTheLauncherStagedNone() {
        assertNull(BundledAndroidToolchain.bundled())
    }

    @Test
    fun noneWhenAToolIsMissing() {
        System.setProperty(BundledAndroidToolchain.DIR_PROPERTY, dir.toString())
        Files.write(dir.resolve("android.jar"), byteArrayOf(1))
        assertNull(BundledAndroidToolchain.bundled(), "no aapt2 staged")
    }

    @Test
    fun anExecutableAapt2RunsInPlace() {
        val aapt2 = stage(executable = true)
        val sdk = assertNotNull(BundledAndroidToolchain.bundled())
        assertEquals(aapt2, sdk.aapt2)
        assertEquals(dir.resolve("android.jar"), sdk.androidJar)
        assertEquals(dir.resolve("core-lambda-stubs.jar"), sdk.coreLambdaStubs)
    }

    @Test
    fun aNonExecutableAapt2RunsFromACopyOutsideTheBundle() {
        val aapt2 = stage(executable = false)
        val sdk = assertNotNull(BundledAndroidToolchain.bundled())
        assertNotEquals(aapt2, sdk.aapt2)
        assertTrue(sdk.aapt2.startsWith(home), "the copy lives under the user's home; got ${sdk.aapt2}")
        assertTrue(Files.isExecutable(sdk.aapt2))
        assertTrue(Files.readAllBytes(sdk.aapt2).contentEquals(Files.readAllBytes(aapt2)))
        assertTrue(!Files.isExecutable(aapt2), "the bundle is left as it was")
        // A second lookup reuses the copy.
        assertEquals(sdk.aapt2, BundledAndroidToolchain.bundled()?.aapt2)
    }
}
