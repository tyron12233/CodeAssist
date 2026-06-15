package dev.ide.model.impl.jdk

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdkSdkProviderTest {

    @Test
    fun detectsTheCurrentModularJdkAsItsHome() {
        val home = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
        // The test JVM is a modular image (lib/modules), so the boot classpath is the JDK home itself.
        assertTrue(Files.exists(home.resolve("lib").resolve("modules")), "expected a modular test JVM")
        val sdk = JdkSdkProvider.detect()
        assertEquals(listOf(home.toString()), sdk.bootClasspath)
    }

    @Test
    fun detectsAClassicJreByRtJar() {
        val fakeHome = Files.createTempDirectory("fake-jre")
        try {
            val rt = fakeHome.resolve("lib").resolve("rt.jar")
            Files.createDirectories(rt.parent)
            Files.writeString(rt, "") // presence is enough for detection
            val sdk = JdkSdkProvider.detect(fakeHome)
            assertEquals(listOf(rt.toString()), sdk.bootClasspath)
        } finally {
            fakeHome.toFile().deleteRecursively()
        }
    }

    @Test
    fun fallsBackToSyntheticWhenNoJdkPresent() {
        val emptyHome = Files.createTempDirectory("no-jdk")
        try {
            val sdk = JdkSdkProvider.detect(emptyHome)
            assertEquals("synthetic", sdk.name)
            val stubRoot = Path.of(sdk.bootClasspath.single())
            assertTrue(Files.exists(stubRoot.resolve("java/lang/String.java")), "synthetic String stub should exist")
            assertTrue(Files.exists(stubRoot.resolve("java/lang/Object.java")))
            stubRoot.toFile().deleteRecursively()
        } finally {
            emptyHome.toFile().deleteRecursively()
        }
    }
}
