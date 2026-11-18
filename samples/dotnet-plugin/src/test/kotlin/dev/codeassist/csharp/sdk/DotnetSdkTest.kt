package dev.codeassist.csharp.sdk

import dev.codeassist.csharp.FakeHost
import dev.codeassist.csharp.newTempDir
import dev.ide.platform.notify.USER_MESSAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DotnetSdkTest {

    private val temp: Path = newTempDir()
    private val dataDir: Path = temp.resolve("data")
    private val privateDir: Path = temp.resolve("private")
    private val launcherFile: Path = temp.resolve("libdotnetlaunch.so")

    private fun host(
        privateDataDir: Path? = privateDir,
        withLauncher: Boolean = true,
    ): FakeHost {
        if (withLauncher) Files.write(launcherFile, ByteArray(0))
        val libraries = if (withLauncher) mapOf("dotnetlaunch" to launcherFile) else emptyMap()
        return FakeHost(dataDir, privateDataDir, libraries)
    }

    private fun sdk(host: FakeHost = host()) = DotnetSdk(host.registration, host.registration.appServices.getServiceOrNull(USER_MESSAGES))

    // ---- where things live ----

    @Test
    fun `home is the private data directory`() {
        assertEquals(privateDir, sdk().home)
    }
    /* need to check laster 
    @Test
    fun `a host older than SPI 3_4 falls back to the data directory`() {
        // Off Android, java.io.tmpdir is not under /data/, so there is no legacy internal path in between.
        assertEquals(dataDir, sdk(host(privateDataDir = null)).home)
    }
    */
    @Test
    fun `the tree and the compiler sit under home`() {
        val sdk = sdk()
        assertEquals(privateDir.resolve("dotnet"), sdk.root)
        assertEquals(privateDir.resolve("dotnet/sdk/Roslyn/csc.dll"), sdk.csc)
    }

    // ---- the launcher ----

    @Test
    fun `the launcher is the packaged library`() {
        assertEquals(launcherFile, sdk().launcher)
    }

    @Test
    fun `no packaged library means no launcher and a clear status`() {
        val sdk = sdk(host(withLauncher = false))
        assertNull(sdk.launcher)
        assertEquals(DotnetSdk.Status.Unavailable(DotnetSdk.NO_DOTNET), sdk.prepare())
    }

    @Test
    fun `a library that is not a file on disk is no launcher`() {
        val missing = FakeHost(dataDir, privateDir, mapOf("dotnetlaunch" to temp.resolve("absent.so")))
        assertNull(DotnetSdk(missing.registration, missing.registration.appServices.getServiceOrNull(USER_MESSAGES)).launcher)
    }

    // ---- the environment ----

    @Test
    fun `the environment points at the tree`() {
        val sdk = sdk()
        val environment = sdk.environment()
        assertEquals(sdk.root.toString(), environment["DOTNET_ROOT"])
        assertEquals("1", environment["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"])
        assertEquals(sdk.home.resolve("home").toString(), environment["HOME"])
        assertEquals(sdk.home.resolve("tmp").toString(), environment["TMPDIR"])
        assertEquals("dumb", environment["TERM"])
    }

    @Test
    fun `the library path starts with the tree's lib folder`() {
        val sdk = sdk()
        val path = sdk.environment().getValue("LD_LIBRARY_PATH")
        assertTrue(path.startsWith(sdk.root.resolve("lib").toString()))
    }

    // ---- the reference assemblies ----

    @Test
    fun `there are no references before the tree is unpacked`() {
        assertEquals(emptyList<Path>(), sdk().references())
    }

    @Test
    fun `references are the dlls of ref, sorted`() {
        val sdk = sdk()
        val ref = sdk.root.resolve("ref")
        Files.createDirectories(ref)
        for (name in listOf("System.dll", "Alpha.dll", "notes.txt", "Mid.dll")) {
            Files.write(ref.resolve(name), ByteArray(0))
        }
        assertEquals(listOf("Alpha.dll", "Mid.dll", "System.dll"), sdk.references().map { it.fileName.toString() })
    }

    // ---- preparing ----

    @Test
    fun `a tree already stamped with this version is not unpacked again`() {
        val sdk = sdk()
        Files.createDirectories(sdk.root)
        Files.write(sdk.root.resolve(".unpacked"), (DotnetSdk.VERSION + "\n").toByteArray())
        val marker = Files.write(sdk.root.resolve("marker.txt"), ByteArray(0))

        assertEquals(DotnetSdk.Status.Ready(DotnetSdk.VERSION), sdk.prepare())
        assertTrue(Files.exists(marker))
    }

    @Test
    fun `prepare makes the home and temp folders`() {
        val sdk = sdk()
        Files.createDirectories(sdk.root)
        Files.write(sdk.root.resolve(".unpacked"), DotnetSdk.VERSION.toByteArray())
        sdk.prepare()
        assertTrue(Files.isDirectory(sdk.home.resolve("home")))
        assertTrue(Files.isDirectory(sdk.home.resolve("tmp")))
    }

    @Test
    fun `prepare answers from memory the second time`() {
        val sdk = sdk()
        Files.createDirectories(sdk.root)
        Files.write(sdk.root.resolve(".unpacked"), DotnetSdk.VERSION.toByteArray())
        assertSame(sdk.prepare(), sdk.prepare())
    }

    @Test
    fun `dotnet archive is available`() {
        assertNotNull(
            DotnetSdk::class.java.getResourceAsStream("/assets/dotnet-sdk.zip")
        )
    }

    @Test
    fun `can read a file from test resources`() {
        val url = javaClass.getResource("/assets/test.txt")

        println("Resource URL: $url")
        println("Classpath:")
        javaClass.classLoader.getResource("assets/test.txt")?.let {
            println("Found: $it")
        } ?: println("NOT FOUND")

        val stream = javaClass.getResourceAsStream("/assets/test.txt")
        println("Stream: $stream")

        stream?.bufferedReader()?.use {
            println("Content: ${it.readText()}")
        }
    }

    @Test
    fun `a stamp from another version unpacks afresh and drops what was there`() {
        val sdk = sdk()
        Files.createDirectories(sdk.root)
        Files.write(sdk.root.resolve(".unpacked"), "0.0.0-old".toByteArray())
        val leftover = Files.write(sdk.root.resolve("leftover.txt"), ByteArray(0))

        // Needs assets/dotnet-sdk.zip on the test classpath: the one in test/resources, or the real one.
        assertEquals(DotnetSdk.Status.Ready(DotnetSdk.VERSION), sdk.prepare())
        assertFalse(Files.exists(leftover))
        assertEquals(DotnetSdk.VERSION, String(Files.readAllBytes(sdk.root.resolve(".unpacked"))).trim())
        assertTrue(Files.isRegularFile(sdk.csc))
    }

    @Test
    fun `a tree with no stamp is unpacked`() {
        val sdk = sdk()
        assertEquals(DotnetSdk.Status.Ready(DotnetSdk.VERSION), sdk.prepare())
        assertTrue(Files.isRegularFile(sdk.csc))
    }
}
