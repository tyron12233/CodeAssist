package dev.ide.model.impl.jdk

import dev.ide.model.PlatformKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorePlatformProviderTest {

    /** A stand-in android.jar with a mix of Android-framework and standard-Java entries. */
    private fun fakeAndroidJar(dir: Path): Path {
        val jar = dir.resolve("android.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            for (name in listOf(
                "android/app/Activity.class",
                "android/util/Log.class",
                "androidx/core/Foo.class",
                "com/android/internal/Bar.class",
                "dalvik/system/DexFile.class",
                "com/google/android/Baz.class",
                "java/lang/Object.class",
                "java/util/List.class",
                "javax/net/SocketFactory.class",
                "org/json/JSONObject.class",
                "META-INF/MANIFEST.MF",
            )) {
                zos.putNextEntry(ZipEntry(name)); zos.write(name.toByteArray()); zos.closeEntry()
            }
        }
        return jar
    }

    private fun entryNames(jar: Path): Set<String> =
        ZipFile(jar.toFile()).use { z -> z.entries().asSequence().map { it.name }.toSet() }

    @Test
    fun stripsAndroidNamespacesKeepsStandardJava() {
        val dir = Files.createTempDirectory("core-platform")
        try {
            val androidJar = fakeAndroidJar(dir)
            val cache = dir.resolve("cache")
            val sdk = CorePlatformProvider.coreJavaSdk(androidJar, extraStubs = emptyList(), cacheDir = cache)
            assertNotNull(sdk)
            assertEquals(CorePlatformProvider.SDK_NAME, sdk.name)
            assertEquals(PlatformKind.JVM, sdk.kind)

            val filtered = Path.of(sdk.bootClasspath.single())
            val names = entryNames(filtered)

            // Android framework namespaces are gone.
            assertFalse(names.any { it.startsWith("android/") }, "android/* leaked: $names")
            assertFalse(names.any { it.startsWith("androidx/") })
            assertFalse(names.any { it.startsWith("com/android/") })
            assertFalse(names.any { it.startsWith("com/google/android/") })
            assertFalse(names.any { it.startsWith("dalvik/") })
            assertFalse(names.any { it.startsWith("META-INF/") })

            // Standard-Java surface survives.
            assertTrue("java/lang/Object.class" in names)
            assertTrue("java/util/List.class" in names)
            assertTrue("javax/net/SocketFactory.class" in names)
            assertTrue("org/json/JSONObject.class" in names)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachesByContentSoSecondCallReusesTheJar() {
        val dir = Files.createTempDirectory("core-platform-cache")
        try {
            val androidJar = fakeAndroidJar(dir)
            val cache = dir.resolve("cache")
            val first = CorePlatformProvider.ensureFiltered(androidJar, cache)
            val second = CorePlatformProvider.ensureFiltered(androidJar, cache)
            assertNotNull(first)
            assertEquals(first, second, "second call should reuse the cached jar path")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun extraStubsJoinBootClasspathAfterFilteredJar() {
        val dir = Files.createTempDirectory("core-platform-stubs")
        try {
            val androidJar = fakeAndroidJar(dir)
            val stub = Files.createFile(dir.resolve("core-lambda-stubs.jar"))
            val sdk = CorePlatformProvider.coreJavaSdk(androidJar, extraStubs = listOf(stub), cacheDir = dir.resolve("cache"))
            assertNotNull(sdk)
            assertEquals(2, sdk.bootClasspath.size)
            assertTrue(sdk.bootClasspath[0].endsWith(".jar"))
            assertEquals(stub.toAbsolutePath().normalize().toString(), sdk.bootClasspath[1])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
