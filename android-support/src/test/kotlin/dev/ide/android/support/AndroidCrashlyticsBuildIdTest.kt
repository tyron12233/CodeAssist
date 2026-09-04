package dev.ide.android.support

import dev.ide.android.support.crashlytics.Crashlytics
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crashlytics needs a *Gradle-plugin half* CodeAssist has to supply itself: its runtime reads a build-id
 * string resource that only `com.google.firebase.crashlytics`'s plugin writes, and it self-initializes from
 * the `<provider>` merged out of the Firebase AARs, so an app that merely HAS the library on its classpath
 * died at startup with `IllegalStateException: The Crashlytics build ID is missing`, before any user code ran.
 *
 * This asserts the generated resource is produced AND reaches the resource merge (the wiring), and that an
 * app without Crashlytics gets no such task or resource.
 */
class AndroidCrashlyticsBuildIdTest {

    @Test
    fun anAppWithCrashlyticsGetsTheGeneratedBuildIdResource() {
        val sdk = assumeAndroidSdk()

        testEnv("android-crashlytics") { env ->
            val dir = env.dir
            // A Maven-layout path is the only coordinate signal a transitively-resolved artifact carries.
            val aar = dir.resolve("deps/com/google/firebase/firebase-crashlytics/20.1.0/firebase-crashlytics-20.1.0.aar")
            stubAar(aar, "com.google.firebase.crashlytics")

            val store = ProjectModel.open(dir, env.platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(env.platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(env.platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.libraryTable.create("crashlytics").apply {
                kind = LibraryKind.AAR; addClassesRoot(store.vfs.fileFor(aar)); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("crashlytics"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            dir.writeSource("app/src/main/AndroidManifest.xml", APP_MANIFEST)
            dir.writeSource("app/src/main/res/values/strings.xml", APP_STRINGS)

            val log = build(dir, sdk, store.workspace.projects.single())

            val generated = dir.resolve("app/build/generated/res/crashlytics/debug/values")
                .resolve(Crashlytics.RESOURCE_FILE_NAME)
            assertTrue(Files.isRegularFile(generated), "build-id resource not generated:\n$log")
            assertTrue(
                generated.readText().contains(Crashlytics.MAPPING_FILE_ID_RESOURCE),
                "generated file does not declare the resource the runtime looks up by name",
            )
            // The generated dir is worthless unless the resource merge actually consumes it.
            assertTrue(
                mergedResDeclaresBuildId(dir),
                "the generated resource never reached merged-res; the app would still crash at startup:\n$log",
            )
        }
    }

    @Test
    fun anAppWithoutCrashlyticsGeneratesNothing() {
        val sdk = assumeAndroidSdk()

        testEnv("android-no-crashlytics") { env ->
            val dir = env.dir
            val store = ProjectModel.open(dir, env.platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(env.platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(env.platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                }
                commit()
            }
            dir.writeSource("app/src/main/AndroidManifest.xml", APP_MANIFEST)
            dir.writeSource("app/src/main/res/values/strings.xml", APP_STRINGS)

            val log = build(dir, sdk, store.workspace.projects.single())

            assertFalse(
                Files.exists(dir.resolve("app/build/generated/res/crashlytics")),
                "an app without Crashlytics must not get the generated resource:\n$log",
            )
            assertFalse(mergedResDeclaresBuildId(dir), "unexpected build-id resource in merged-res:\n$log")
        }
    }

    private fun build(dir: Path, sdk: AndroidSdk, project: dev.ide.model.Project): String {
        val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
        val graph = AndroidBuildSystem.inProcess(sdk, signing).createBuildGraph(
            project,
            BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE),
        )
        val log = StringBuilder()
        val outcome = runBlocking {
            TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                .execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
        }
        assertTrue(outcome.succeeded, "build failed:\n$log")
        return log.toString()
    }

    /** Whether any file under the module's merged-res declares the Crashlytics build-id resource. */
    private fun mergedResDeclaresBuildId(dir: Path): Boolean {
        val merged = dir.resolve("app/build/intermediates/android/debug/merged-res")
        if (!Files.isDirectory(merged)) return false
        Files.walk(merged).use { paths ->
            return paths.filter { Files.isRegularFile(it) }
                .anyMatch { runCatching { it.readText() }.getOrDefault("").contains(Crashlytics.MAPPING_FILE_ID_RESOURCE) }
        }
    }

    /** A minimal, valid AAR standing in for the real artifact: only its PATH is the signal under test. */
    private fun stubAar(aar: Path, pkg: String) {
        Files.createDirectories(aar.parent)
        val classesJar = Files.createTempFile("classes", ".jar")
        ZipOutputStream(Files.newOutputStream(classesJar)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); zos.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); zos.closeEntry()
        }
        ZipOutputStream(Files.newOutputStream(aar)).use { zos ->
            fun put(entry: String, bytes: ByteArray) { zos.putNextEntry(ZipEntry(entry)); zos.write(bytes); zos.closeEntry() }
            put("classes.jar", Files.readAllBytes(classesJar))
            put(
                "AndroidManifest.xml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
                    <application/>
                </manifest>
                """.trimIndent().toByteArray(),
            )
        }
    }

    private companion object {
        val APP_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name"/>
            </manifest>
        """
        val APP_STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">Demo</string></resources>
        """
    }
}
