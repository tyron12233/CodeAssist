package dev.ide.android.support

import com.android.tools.profgen.ArtProfile
import com.android.tools.profgen.DexFile
import dev.ide.android.support.tools.ArtProfiles
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildOutcome
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleId
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import dev.ide.vfs.local.fileFor
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Baseline Profiles: a release build merges the app's and its AARs' `baseline-prof.txt` rules and compiles them
 * (vendored profgen) against the dex it packages. The checks read the packaged profile back and look up each
 * profiled method in the packaged dex, because the whole point of the binary format is that it names methods
 * by index in one specific dex: a profile that parses but points at the wrong file or index does nothing.
 */
class AndroidArtProfileTest {

    @Test
    fun releaseApkCarriesAProfileCompiledAgainstItsOwnDex() {
        val sdk = assumeAndroidSdk()
        testEnv("art-profile-apk") { env ->
            val dir = env.dir
            val (outcome, log) = build(env, dir, sdk, "release", BuildGoal.PACKAGE, minify = false)
            assertTrue(outcome.succeeded, "release build failed:\n$log")
            assertTrue(outcome.ranTasks.any { it.value == ":app:compileArtProfileRelease" }, "ran: ${outcome.ranTasks.map { it.value }}")

            val apk = dir.resolve("app/build/outputs/apk/release/app-release.apk")
            ZipFile(apk.toFile()).use { zip ->
                for (name in listOf("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm")) {
                    val entry = assertNotNull(zip.getEntry(name), "$name missing from the APK")
                    // profileinstaller opens these as file descriptors, which a compressed entry cannot give.
                    assertEquals(ZipEntry.STORED, entry.method, "$name must be STORED")
                }
                val profiled = profiledMethods(zip).map { "${it.parent}->${it.name}" }.toSet()
                // From the app's src/main/baseline-prof.txt.
                assertTrue("Lcom/example/app/MainActivity;->onCreate" in profiled, "profiled: $profiled")
                // From src/main/baselineProfiles/ and from the AAR: both merged in.
                assertTrue("Lcom/example/app/Helper;->greet" in profiled, "profiled: $profiled")
                assertTrue("Lcom/example/app/Helper;->farewell" in profiled, "AAR rules must be merged: $profiled")
            }
        }
    }

    /** R8 renames classes; the rules are written against source names and still have to land. */
    @Test
    fun minifiedReleaseResolvesRulesThroughTheMapping() {
        val sdk = assumeAndroidSdk()
        testEnv("art-profile-r8") { env ->
            val dir = env.dir
            val (outcome, log) = build(env, dir, sdk, "release", BuildGoal.PACKAGE, minify = true)
            assertTrue(outcome.succeeded, "minified build failed:\n$log")

            val mapping = Files.readAllLines(dir.resolve("app/build/outputs/mapping/release/mapping.txt"))
            val obfuscated = mapping.first { it.startsWith("com.example.app.Helper -> ") }
                .substringAfter(" -> ").removeSuffix(":")
            assertTrue(obfuscated != "com.example.app.Helper", "Helper must be renamed for this test to mean anything")
            val descriptor = "L" + obfuscated.replace('.', '/') + ";"

            ZipFile(dir.resolve("app/build/outputs/apk/release/app-release.apk").toFile()).use { zip ->
                val parents = profiledMethods(zip).map { it.parent }.toSet()
                assertTrue(descriptor in parents, "renamed Helper ($descriptor) must be profiled: $parents")
            }
        }
    }

    @Test
    fun releaseBundleCarriesTheProfileAsBundleMetadata() {
        val sdk = assumeAndroidSdk()
        testEnv("art-profile-aab") { env ->
            val dir = env.dir
            val (outcome, log) = build(env, dir, sdk, "release", BuildGoal.BUNDLE, minify = false)
            assertTrue(outcome.succeeded, "bundle build failed:\n$log")
            val entries = ZipFile(dir.resolve("app/build/outputs/bundle/release/app-release.aab").toFile())
                .use { zip -> zip.entries().toList().map { it.name }.toSet() }
            assertTrue("BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof" in entries, "entries: $entries")
            assertTrue("BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm" in entries, "entries: $entries")
        }
    }

    /** ART does not AOT-compile a debuggable app, so AGP compiles no profile for one and neither do we. */
    @Test
    fun debugBuildPackagesNoProfile() {
        val sdk = assumeAndroidSdk()
        testEnv("art-profile-debug") { env ->
            val dir = env.dir
            val (outcome, log) = build(env, dir, sdk, "debug", BuildGoal.PACKAGE, minify = false)
            assertTrue(outcome.succeeded, "debug build failed:\n$log")
            assertFalse(outcome.ranTasks.any { "ArtProfile" in it.value }, "ran: ${outcome.ranTasks.map { it.value }}")
            val names = ZipFile(dir.resolve("app/build/outputs/apk/debug/app-debug.apk").toFile())
                .use { zip -> zip.entries().toList().map { it.name } }
            assertFalse(names.any { it.startsWith("assets/dexopt/") }, "entries: $names")
        }
    }

    /** A rule of the project's own that does not parse fails the build and says where (AGP does the same). */
    @Test
    fun aMalformedRuleInTheAppsOwnProfileFailsTheBuild() {
        val sdk = assumeAndroidSdk()
        testEnv("art-profile-bad") { env ->
            val dir = env.dir
            val (outcome, log) = build(env, dir, sdk, "release", BuildGoal.PACKAGE, minify = false) {
                dir.writeSource("app/src/main/baseline-prof.txt", MALFORMED_RULE)
            }
            assertFalse(outcome.succeeded, "a malformed rule must fail the build:\n$log")
            assertTrue("baseline-prof.txt:1:" in log, "the failure must name the file and line:\n$log")
        }
    }

    /**
     * Every method the APK's profile names, looked up in the dex it names: each profiled dex must be an entry of
     * the APK with the checksum the profile recorded, or ART would discard that part of the profile.
     */
    private fun profiledMethods(zip: ZipFile): List<com.android.tools.profgen.DexMethod> {
        val profile = assertNotNull(zip.getInputStream(zip.getEntry("assets/dexopt/baseline.prof")).use { ArtProfile(it) })
        assertTrue(profile.profileData.isNotEmpty(), "the profile names no dex")
        return profile.profileData.flatMap { (profiledDex, data) ->
            val entry = assertNotNull(zip.getEntry(profiledDex.name), "profile names ${profiledDex.name}, which the APK lacks")
            val dex = zip.getInputStream(entry).use { DexFile(it, profiledDex.name) }
            assertEquals(dex.dexChecksum, profiledDex.dexChecksum, "profile must be keyed to the packaged ${profiledDex.name}")
            data.methods.keys.map { dex.methodPool[it] }
        }
    }

    /** A library's rule that does not parse costs that library's rules, not the build. */
    @Test
    fun aMalformedLibraryRuleIsSkippedWithAWarning() {
        testEnv("art-profile-lib-bad") { env ->
            val lib = env.dir.resolve("lib-prof.txt")
            Files.write(lib, MALFORMED_RULE.toByteArray())
            val out = env.dir.resolve("out")
            val result = ArtProfiles.compile(
                listOf(ArtProfiles.RulesFile(lib, own = false)), emptyList(), null,
                out.resolve("merged.txt"), out.resolve("baseline.prof"), out.resolve("baseline.profm"),
            )
            val empty = assertIs<ArtProfiles.Result.Empty>(result)
            assertTrue(empty.warnings.single().contains("lib-prof.txt:1:"), "warnings: ${empty.warnings}")
            assertFalse(Files.exists(out.resolve("baseline.prof")))
        }
    }

    private fun build(
        env: dev.ide.testkit.TestEnv,
        dir: Path,
        sdk: dev.ide.android.support.tools.AndroidSdk,
        variant: String,
        goal: BuildGoal,
        minify: Boolean,
        tweak: () -> Unit = {},
    ): Pair<BuildOutcome, String> {
        val platform = env.platform
        val aar = dir.resolve("libs/widget.aar")
        writeAar(aar, AAR_RULES)

        val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
        ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
        val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
        store.workspace.libraryTable.create("widget").apply {
            kind = LibraryKind.AAR
            addClassesRoot(store.vfs.fileFor(aar))
            commit()
        }
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", appType).apply {
                languageLevel = LanguageLevel.JAVA_17
                putFacet(
                    AndroidFacet(
                        namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34, isApplication = true,
                        buildTypes = listOf(
                            BuildType("debug", debuggable = true),
                            BuildType(
                                "release", debuggable = false, minifyEnabled = minify,
                                // Keep Helper's methods but let R8 rename them, so the profile can only find them
                                // through the mapping.
                                proguardRules = listOf("-keep,allowobfuscation class com.example.app.Helper { *; }"),
                            ),
                        ),
                    ),
                )
                addDependency(LibraryDependency(LibraryRef("widget"), DependencyScope.IMPLEMENTATION))
            }
            commit()
        }
        dir.writeSource("app/src/main/AndroidManifest.xml", MANIFEST)
        dir.writeSource("app/src/main/res/values/strings.xml", STRINGS)
        dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", ACTIVITY)
        dir.writeSource("app/src/main/java/com/example/app/Helper.java", HELPER)
        dir.writeSource("app/src/main/baseline-prof.txt", APP_RULES)
        dir.writeSource("app/src/main/baselineProfiles/helper-prof.txt", "HSPLcom/example/app/Helper;->greet()Ljava/lang/String;\n")
        // R8's startup layout file shares the folder but is not profile rules: if it were merged, this line
        // would fail the build.
        dir.writeSource("app/src/main/baselineProfiles/startup-prof.txt", MALFORMED_RULE)
        tweak()

        val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
        val buildSystem = AndroidBuildSystem.inProcess(sdk, signing, bootClasspath = listOf(sdk.androidJar))
        val request = BuildRequest(listOf(ModuleId("app")), VariantSelector(variant), goal)
        val log = StringBuilder()
        val outcome = runBlocking {
            TaskExecutorImpl(BuildCache(dir.resolve(".caches/build")))
                .execute(buildSystem.createBuildGraph(store.workspace.projects.single(), request), SimpleTaskContext(log = { log.appendLine(it) }), 2)
        }
        return outcome to log.toString()
    }

    /** A code-free AAR whose only job here is to ship `baseline-prof.txt`. */
    private fun writeAar(aar: Path, rules: String) {
        Files.createDirectories(aar.parent)
        val classesJar = java.io.ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { jar ->
                jar.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                jar.write("Manifest-Version: 1.0\r\n\r\n".toByteArray())
                jar.closeEntry()
            }
        }.toByteArray()
        ZipOutputStream(Files.newOutputStream(aar)).use { zos ->
            zos.putNextEntry(ZipEntry("classes.jar")); zos.write(classesJar); zos.closeEntry()
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.widget"/>""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("baseline-prof.txt")); zos.write(rules.toByteArray()); zos.closeEntry()
        }
    }

    private companion object {
        /** An unterminated parameter list: profgen rejects it (the malformed-rule test pins that it does). */
        const val MALFORMED_RULE = "HSPLcom/example/app/MainActivity;->onCreate(\n"

        val APP_RULES = """
            # The activity's startup path.
            HSPLcom/example/app/MainActivity;->onCreate(Landroid/os/Bundle;)V
            Lcom/example/app/MainActivity;
        """.trimIndent() + "\n"

        val AAR_RULES = "HSPLcom/example/app/Helper;->farewell()Ljava/lang/String;\n"

        val ACTIVITY = """
            package com.example.app;
            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;
            public class MainActivity extends Activity {
                @Override protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    TextView tv = new TextView(this);
                    tv.setText(new Helper().greet() + new Helper().farewell());
                    setContentView(tv);
                }
            }
        """
        val HELPER = """
            package com.example.app;
            public class Helper {
                public String greet() { return "hi " + System.nanoTime(); }
                public String farewell() { return "bye " + System.nanoTime(); }
            }
        """
        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name"><activity android:name=".MainActivity" android:exported="true"/></application>
            </manifest>
        """
        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources><string name="app_name">Prof</string></resources>
        """
    }
}
