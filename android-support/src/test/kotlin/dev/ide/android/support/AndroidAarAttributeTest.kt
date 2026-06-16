package dev.ide.android.support

import dev.ide.android.support.tasks.ApkPackaging
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.JavaCompile
import dev.ide.build.engine.JavaCompileResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleId
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves a custom-view attribute from an external AAR resolves through aapt2. The regression: a Maven
 * dependency that resolves to an AAR is stored in the model as its *exploded* `classes.jar` (with `res/`
 * and `AndroidManifest.xml` siblings), NOT as the `.aar` file — so the build must recognise that layout,
 * merge the AAR's `res/values/attrs.xml`, and pass the AAR's package to aapt2 `--extra-packages`. Without
 * the fix, the app layout's `app:customText` attribute is unknown and aapt2 link fails.
 */
class AndroidAarAttributeTest {

    @Test
    fun resolvesCustomAttributeFromExplodedAar() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!

        val dir = Files.createTempDirectory("android-aar-attr")
        val platform = PlatformCore()
        try {
            // The exploded-AAR layout the Maven resolver produces: classes.jar + res/ + manifest + .extracted.
            val exploded = explodeAar(dir.resolve("exploded/customview"), sdk.androidJar)

            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }

            // The library points at the exploded classes.jar (as addDependency does for a Maven AAR), not the .aar.
            store.workspace.libraryTable.create("customview").apply {
                kind = LibraryKind.AAR; addClassesRoot(store.vfs.fileFor(exploded)); commit()
            }

            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("customview"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            write(dir, "app/src/main/AndroidManifest.xml", APP_MANIFEST)
            write(dir, "app/src/main/res/layout/main.xml", APP_LAYOUT)   // uses app:customText from the AAR
            write(dir, "app/src/main/java/com/example/app/MainActivity.java", APP_ACTIVITY)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.inProcess(jdtCompile(), sdk, signing)
            val graph = buildSystem.createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), BuildGoal.PACKAGE),
            )
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }
            assertTrue(outcome.succeeded, "custom-attribute APK build failed (AAR attrs.xml not merged?):\n$log")

            // aapt2 --extra-packages generated the AAR's own R (so its classes + attrs resolve).
            val aarR = dir.resolve("app/build/intermediates/android/debug/gen/io/example/customview/R.java")
            assertTrue(Files.isRegularFile(aarR), "AAR package R.java not generated (extra-packages missing): $aarR")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun jdtCompile(): JavaCompile = JavaCompile { sources, classpath, out, level ->
        val r = JdtBatchCompiler.compile(sources, classpath, out, level)
        JavaCompileResult(r.success, r.messages)
    }

    /** Lay out an already-exploded AAR (classes.jar + res/values/attrs.xml + manifest + marker); returns the jar. */
    private fun explodeAar(dir: Path, androidJar: Path): Path {
        write(dir.resolve("src"), "io/example/customview/CustomView.java", CUSTOM_VIEW)
        val r = JdtBatchCompiler.compile(listOf(dir.resolve("src/io/example/customview/CustomView.java")),
            listOf(androidJar), dir.resolve("classes"), "17")
        check(r.success) { "AAR class compile failed: ${r.messages}" }
        val classesJar = dir.resolve("classes.jar")
        ApkPackaging.jarClasses(dir.resolve("classes"), classesJar)
        write(dir, "res/values/attrs.xml", AAR_ATTRS)
        write(dir, "AndroidManifest.xml", AAR_MANIFEST)
        Files.writeString(dir.resolve(".extracted"), "")
        return classesJar
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
    }

    private companion object {
        val CUSTOM_VIEW = """
            package io.example.customview;
            import android.content.Context;
            import android.view.View;
            public class CustomView extends View {
                public CustomView(Context c) { super(c); }
            }
        """
        val AAR_ATTRS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <declare-styleable name="CustomView">
                    <attr name="customText" format="string"/>
                </declare-styleable>
            </resources>
        """
        val AAR_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.example.customview">
                <application/>
            </manifest>
        """
        val APP_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="App">
                    <activity android:name=".MainActivity" android:exported="true"/>
                </application>
            </manifest>
        """
        // The custom view + its AAR-declared attribute, referenced via the res-auto namespace.
        val APP_LAYOUT = """
            <?xml version="1.0" encoding="utf-8"?>
            <io.example.customview.CustomView
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                app:customText="hello"/>
        """
        val APP_ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.main);
                }
            }
        """
    }
}
