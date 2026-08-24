package dev.ide.android.support

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
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.impl.ProjectModel
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A library module reading ANOTHER library module's resources. With non-transitive R classes an `android-lib`
 * gets an `R` holding only its own resources, so `feature` must name the dependency's package to read one of
 * its strings (`com.example.base.R.string.base_greeting`) — exactly what AGP puts on a library's compile
 * classpath as the dependency's own R.jar. Only the *app* used to get dependency R classes (aapt2
 * `--extra-packages` over the merged table), so this compiled in an app but not one module down.
 *
 * A dependency AAR is the same problem one level out: it ships an `R.txt` symbol table but no `R` classes at
 * all, so a library reading `com.example.aarlib.R.string.x` needs one generated for it — in the build (from
 * `R.txt`) and in the editor (sliced out of the merged resource repository).
 */
class AndroidLibDependencyRTest {

    @Test
    fun androidLibCompilesAgainstDependencyLibraryR() {
        val sdk = assumeAndroidSdk()

        testEnv("android-lib-dep-r") { env ->
            val dir = env.dir
            val platform = env.platform
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(platform.extensions)
            AndroidSupport.register(types, FacetCodecRegistry())
            val appType = types.resolve("android-app")
            val libType = types.resolve("android-lib")

            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("base", libType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.base", compileSdk = 34, minSdk = 24, isApplication = false))
                }
                addModule("feature", libType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.feature", compileSdk = 34, minSdk = 24, isApplication = false))
                    addDependency(ModuleDependency(ModuleId("base"), DependencyScope.IMPLEMENTATION))
                }
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(ModuleDependency(ModuleId("feature"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            dir.writeSource("base/src/main/res/values/strings.xml", BASE_STRINGS)
            dir.writeSource("feature/src/main/res/values/strings.xml", FEATURE_STRINGS)
            dir.writeSource("feature/src/main/java/com/example/feature/FeatureText.java", FEATURE_TEXT)
            dir.writeSource("app/src/main/AndroidManifest.xml", APP_MANIFEST)
            dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", APP_ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val graph = AndroidBuildSystem.inProcess(sdk, signing).createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.COMPILE_ONLY),
            )
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "library-to-library R reference failed to compile:\n$log")
            assertTrue(
                Files.exists(dir.resolve("feature/build/classes/com/example/feature/FeatureText.class")),
                "the dependent library's class was not compiled",
            )
        }
    }

    @Test
    fun androidLibCompilesAgainstDependencyAarR() {
        val sdk = assumeAndroidSdk()

        testEnv("android-aar-r") { env ->
            val dir = env.dir
            val platform = env.platform
            val aar = buildAar(dir.resolve("aarlib.aar"))
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(platform.extensions)
            AndroidSupport.register(types, FacetCodecRegistry())

            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.libraryTable.create("aarlib").apply {
                kind = LibraryKind.AAR; addClassesRoot(store.vfs.fileFor(aar)); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("feature", types.resolve("android-lib")).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.feature", compileSdk = 34, minSdk = 24, isApplication = false))
                    addDependency(LibraryDependency(LibraryRef("aarlib"), DependencyScope.IMPLEMENTATION))
                }
                addModule("app", types.resolve("android-app")).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(ModuleDependency(ModuleId("feature"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            dir.writeSource("feature/src/main/res/values/strings.xml", FEATURE_STRINGS)
            dir.writeSource("feature/src/main/java/com/example/feature/AarText.java", FEATURE_AAR_TEXT)
            dir.writeSource("app/src/main/AndroidManifest.xml", APP_MANIFEST)
            dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", APP_AAR_ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val graph = AndroidBuildSystem.inProcess(sdk, signing).createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.COMPILE_ONLY),
            )
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "library-to-AAR R reference failed to compile:\n$log")
            assertTrue(
                Files.exists(dir.resolve("feature/build/intermediates/r/aar-R.jar")),
                "the dependency AAR's R.jar should be generated for the library module",
            )
        }
    }

    @Test
    fun syntheticRCoversDependencyAarPackages() {
        testEnv("android-aar-synthetic-r") { env ->
            val dir = env.dir
            val platform = env.platform
            val aar = buildAar(dir.resolve("aarlib.aar"))
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(platform.extensions)
            AndroidSupport.register(types, FacetCodecRegistry())

            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.libraryTable.create("aarlib").apply {
                kind = LibraryKind.AAR; addClassesRoot(store.vfs.fileFor(aar)); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("feature", types.resolve("android-lib")).apply {
                    putFacet(AndroidFacet(namespace = "com.example.feature", compileSdk = 34, minSdk = 24, isApplication = false))
                    addDependency(LibraryDependency(LibraryRef("aarlib"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            dir.writeSource("feature/src/main/res/values/strings.xml", FEATURE_STRINGS)

            val module = store.workspace.projects.single().modules.single()
            val classes = AndroidRClassProvider().classesFor(object : SyntheticClassContext {
                override val module = module
                override val workspace = store.workspace
            })

            val aarR = classes.singleOrNull { it.fqName == "com.example.aarlib.R" }
            assertTrue(aarR != null, "the dependency AAR's R should be contributed: ${classes.map { it.fqName }}")
            assertTrue("aar_greeting" in fieldNames(aarR, "string"), "AAR string missing from its R: ${fieldNames(aarR, "string")}")
            assertTrue("AarChart" in fieldNames(aarR, "styleable"), "AAR styleable missing from its R: ${fieldNames(aarR, "styleable")}")
            // Non-transitive: the AAR's R holds the AAR's resources only, never the consuming module's.
            assertTrue("feature_title" !in fieldNames(aarR, "string"), "the module's own resource leaked into the AAR's R")

            val ownR = classes.singleOrNull { it.fqName == "com.example.feature.R" }
            assertTrue(ownR != null, "the module's own R should still be contributed")
            assertTrue("feature_title" in fieldNames(ownR, "string"), "own R lost its own resource")
            // One merged id assignment: a resource has the SAME id whichever package's R names it.
            assertEquals(
                constantOf(ownR, "string", "aar_greeting"), constantOf(aarR, "string", "aar_greeting"),
                "the AAR's R and the module's R must agree on a resource's id",
            )
        }
    }

    private fun fieldNames(r: SyntheticClass?, type: String): List<String> =
        r?.nestedClasses?.firstOrNull { it.fqName.endsWith(".$type") }?.fields?.map { it.name } ?: emptyList()

    private fun constantOf(r: SyntheticClass?, type: String, field: String): String? =
        r?.nestedClasses?.firstOrNull { it.fqName.endsWith(".$type") }?.fields?.firstOrNull { it.name == field }?.constant

    /** A minimal `.aar`: a manifest (its package names the R), a `res/` and the `R.txt` symbol table. */
    private fun buildAar(aar: Path): Path {
        Files.createDirectories(aar.parent)
        ZipOutputStream(Files.newOutputStream(aar)).use { zos ->
            fun put(entry: String, text: String) {
                zos.putNextEntry(ZipEntry(entry)); zos.write(text.trimIndent().toByteArray()); zos.closeEntry()
            }
            put("AndroidManifest.xml", AAR_MANIFEST)
            put("res/values/values.xml", AAR_VALUES)
            put("R.txt", AAR_R_TXT)
        }
        return aar
    }

    private companion object {
        val AAR_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.aarlib"/>
        """
        val AAR_VALUES = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="aar_greeting">Hello from the AAR</string>
                <attr name="barColor" format="color"/>
                <declare-styleable name="AarChart"><attr name="barColor"/></declare-styleable>
            </resources>
        """
        // What an AAR really ships: placeholder ids (0x0) — only the app's link assigns real ones, which is
        // why the generated fields must be non-final.
        val AAR_R_TXT = """
            int attr barColor 0x0
            int string aar_greeting 0x0
            int[] styleable AarChart { 0x0 }
            int styleable AarChart_barColor 0
        """
        // Reads the AAR's R by package: a scalar id and a styleable int[] (the two shapes R.txt carries).
        val FEATURE_AAR_TEXT = """
            package com.example.feature;
            public final class AarText {
                public static int greeting() { return com.example.aarlib.R.string.aar_greeting; }
                public static int[] chart() { return com.example.aarlib.R.styleable.AarChart; }
                public static int barColorIndex() { return com.example.aarlib.R.styleable.AarChart_barColor; }
            }
        """
        val APP_AAR_ACTIVITY = """
            package com.example.app;

            import android.app.Activity;
            import com.example.feature.AarText;

            public class MainActivity extends Activity {
                public int res() { return AarText.greeting(); }
            }
        """
        val BASE_STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="base_greeting">Hello from base</string></resources>
        """
        val FEATURE_STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="feature_title">Feature</string></resources>
        """
        // Its OWN R for its own string, the DEPENDENCY's R (by package) for the dependency's string.
        val FEATURE_TEXT = """
            package com.example.feature;
            public final class FeatureText {
                public static int titleRes() { return R.string.feature_title; }
                public static int baseRes() { return com.example.base.R.string.base_greeting; }
            }
        """
        val APP_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application><activity android:name=".MainActivity" android:exported="true"/></application>
            </manifest>
        """
        val APP_ACTIVITY = """
            package com.example.app;

            import android.app.Activity;
            import com.example.feature.FeatureText;

            public class MainActivity extends Activity {
                public int res() { return FeatureText.baseRes(); }
            }
        """
    }
}
