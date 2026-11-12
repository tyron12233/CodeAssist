package dev.ide.model.impl.jdk

import dev.ide.testkit.withTempDir
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

    /** What the host adds reaches the jar: an entry it rewrites, a class it appends, and none it already has.
     *  The additions' version is part of the file name, so the jar without them is not the one served. */
    @Test
    fun apiAdditionsRewriteAndAppendEntries() {
        withTempDir("core-platform") { dir ->
            val androidJar = fakeAndroidJar(dir)
            val additions = object : CorePlatformProvider.ApiAdditions {
                override val version = "7"
                override fun rewrite(entryName: String, bytes: ByteArray) =
                    if (entryName == "java/util/List.class") "patched".toByteArray() else bytes
                override fun additions(has: (String) -> Boolean) = buildMap {
                    put("java/lang/ScopedValue.class", "new".toByteArray())
                    if (!has("java/util/List.class")) put("java/util/List.class", "duplicate".toByteArray())
                }
            }
            val cache = dir.resolve("cache")
            val plain = Path.of(CorePlatformProvider.coreJavaSdk(androidJar, cacheDir = cache)!!.bootClasspath.single())
            val extended = Path.of(CorePlatformProvider.coreJavaSdk(androidJar, cacheDir = cache, additions = additions)!!.bootClasspath.single())
            assertTrue(plain != extended && extended.fileName.toString().endsWith("-a7.jar"), "$plain vs $extended")
            ZipFile(extended.toFile()).use { z ->
                assertEquals("patched", z.getInputStream(z.getEntry("java/util/List.class")).readBytes().decodeToString())
                assertEquals("new", z.getInputStream(z.getEntry("java/lang/ScopedValue.class")).readBytes().decodeToString())
            }
            assertFalse("java/lang/ScopedValue.class" in entryNames(plain))
        }
    }

    @Test
    fun stripsAndroidNamespacesKeepsStandardJava() {
        withTempDir("core-platform") { dir ->
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
        }
    }

    @Test
    fun cachesByContentSoSecondCallReusesTheJar() {
        withTempDir("core-platform-cache") { dir ->
            val androidJar = fakeAndroidJar(dir)
            val cache = dir.resolve("cache")
            val first = CorePlatformProvider.ensureFiltered(androidJar, cache)
            val second = CorePlatformProvider.ensureFiltered(androidJar, cache)
            assertNotNull(first)
            assertEquals(first, second, "second call should reuse the cached jar path")
        }
    }

    @Test
    fun extraStubsJoinBootClasspathAfterFilteredJar() {
        withTempDir("core-platform-stubs") { dir ->
            val androidJar = fakeAndroidJar(dir)
            val stub = Files.createFile(dir.resolve("core-lambda-stubs.jar"))
            val sdk = CorePlatformProvider.coreJavaSdk(androidJar, extraStubs = listOf(stub), cacheDir = dir.resolve("cache"))
            assertNotNull(sdk)
            assertEquals(2, sdk.bootClasspath.size)
            assertTrue(sdk.bootClasspath[0].endsWith(".jar"))
            assertEquals(stub.toAbsolutePath().normalize().toString(), sdk.bootClasspath[1])
        }
    }
}
