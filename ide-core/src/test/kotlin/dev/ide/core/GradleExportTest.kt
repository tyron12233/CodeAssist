package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.BuildFeatures
import dev.ide.android.support.BuildType
import dev.ide.android.support.ProductFlavor
import dev.ide.core.gradle.GradleProjectExport
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Exclusion
import dev.ide.model.FacetTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.PlatformDependency
import dev.ide.model.Coordinate
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Gradle export: a native project rendered into build scripts a real Gradle build can read. The last
 * test closes the loop by importing the export back with [GradleImport], so the two directions stay in
 * agreement.
 */
class GradleExportTest {

    private class TestType(override val id: String) : ModuleType {
        override val displayName = id
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private fun androidSourceSet(name: String) = SourceSetTemplate(
        name, DependencyScope.IMPLEMENTATION,
        mapOf(
            "src/$name/java" to setOf(ContentRole.SOURCE),
            "src/$name/res" to setOf(ContentRole.ANDROID_RES),
            "src/$name/assets" to setOf(ContentRole.ASSETS),
        ),
    )

    /**
     * A Compose app module (`app`) over a Kotlin library (`lib`): the shape most exported projects have,
     * including the parts with no one-line Gradle form (a BOM, an exclusion, a flavor, a signed release).
     */
    private fun writeProject(dir: Path, platform: PlatformCore) {
        val types = ModuleTypeRegistry(platform.extensions)
        types.register(TestType("android-app"), PluginId("android-support"))
        types.register(TestType("java-lib"), PluginId("java-support"))
        val codecs = FacetCodecRegistry().register(AndroidFacetCodec)
        val store = ProjectModel.open(dir, platform, codecs)
        store.workspace.beginModification().apply {
            addProject("Demo App", BuildSystemId.NATIVE, store.vfs.root()); commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", types.resolve("android-app")).apply {
                languageLevel = LanguageLevel.JAVA_17
                addSourceSet(androidSourceSet("main"))
                addDependency(ModuleDependency(ModuleId("lib"), DependencyScope.IMPLEMENTATION))
                addDependency(PlatformDependency(Coordinate("androidx.compose", "compose-bom", "2024.09.00")))
                addDependency(LibraryDependency(LibraryRef("androidx.activity:activity-compose:1.9.3"), DependencyScope.IMPLEMENTATION))
                addDependency(LibraryDependency(LibraryRef("androidx.compose.ui:ui"), DependencyScope.IMPLEMENTATION))
                addDependency(LibraryDependency(LibraryRef("kotlin-stdlib"), DependencyScope.IMPLEMENTATION))
                addDependency(
                    LibraryDependency(
                        LibraryRef("com.squareup.retrofit2:retrofit:2.11.0"),
                        DependencyScope.IMPLEMENTATION,
                        exclusions = listOf(Exclusion("com.squareup.okhttp3", "okhttp")),
                    ),
                )
                addDependency(LibraryDependency(LibraryRef("junit:junit:4.13.2"), DependencyScope.TEST_IMPLEMENTATION))
                putFacet(
                    AndroidFacet(
                        namespace = "com.example.demo",
                        compileSdk = 34,
                        minSdk = 24,
                        targetSdk = 34,
                        versionCode = 7,
                        versionName = "1.2.3",
                        manifestPlaceholders = mapOf("hostName" to "example.com"),
                        flavorDimensions = listOf("tier"),
                        productFlavors = listOf(ProductFlavor("free", dimension = "tier", applicationIdSuffix = ".free")),
                        buildTypes = listOf(
                            BuildType("debug", debuggable = true),
                            BuildType(
                                "release", debuggable = false, minifyEnabled = true, shrinkResources = true,
                                proguardFiles = listOf("proguard-android-optimize.txt", "proguard-rules.pro"),
                                signingConfig = "upload",
                            ),
                        ),
                        buildFeatures = BuildFeatures(compose = true, viewBinding = true),
                    ),
                )
            }
            addModule("lib", types.resolve("java-lib")).apply {
                languageLevel = LanguageLevel.JAVA_17
                addSourceSet(
                    SourceSetTemplate(
                        "main", DependencyScope.IMPLEMENTATION,
                        mapOf("src/main/kotlin" to setOf(ContentRole.SOURCE)),
                    ),
                )
                addDependency(LibraryDependency(LibraryRef("com.google.guava:guava:33.4.8-jre"), DependencyScope.API))
            }
            commit()
        }
        store.save()
        dir.writeSource("app/src/main/AndroidManifest.xml", """<manifest/>""")
        dir.writeSource("app/src/main/java/com/example/demo/MainActivity.kt", "package com.example.demo\n\nclass MainActivity")
        dir.writeSource("lib/src/main/kotlin/com/example/lib/Greeter.kt", "package com.example.lib\n\nclass Greeter")
    }

    @Test
    fun rendersSettingsAndRootScriptForEveryModule() {
        testEnv("gradle-export") { env ->
            writeProject(env.dir, env.platform)
            val files = GradleProjectExport.render(env.dir).files

            val settings = assertNotNull(files["settings.gradle.kts"])
            assertContains(settings, """rootProject.name = "Demo App"""")
            assertContains(settings, """include(":app")""")
            assertContains(settings, """include(":lib")""")
            // The directories match the paths, so nothing needs to be pointed at by hand.
            assertFalse("projectDir" in settings, "conventional layout needs no projectDir override")

            val root = assertNotNull(files["build.gradle.kts"])
            assertContains(root, """id("com.android.application") version "${GradleProjectExport.AGP_VERSION}" apply false""")
            assertContains(root, """id("org.jetbrains.kotlin.android") version "${GradleProjectExport.KOTLIN_VERSION}" apply false""")
            assertContains(root, """id("org.jetbrains.kotlin.plugin.compose") version "${GradleProjectExport.KOTLIN_VERSION}" apply false""")
            // Gradle ships java-library itself: it needs no version, so declaring it here would be noise.
            assertFalse("java-library" in root, "a core Gradle plugin is not declared in the root block")
        }
    }

    @Test
    fun rendersTheAndroidFacetIntoAnAndroidBlock() {
        testEnv("gradle-export-android") { env ->
            writeProject(env.dir, env.platform)
            val app = assertNotNull(GradleProjectExport.render(env.dir).files["app/build.gradle.kts"])

            assertContains(app, """id("com.android.application")""")
            assertContains(app, """id("org.jetbrains.kotlin.android")""")
            assertContains(app, """namespace = "com.example.demo"""")
            assertContains(app, "compileSdk = 34")
            assertContains(app, "minSdk = 24")
            assertContains(app, "targetSdk = 34")
            assertContains(app, "versionCode = 7")
            assertContains(app, """versionName = "1.2.3"""")
            assertContains(app, """manifestPlaceholders["hostName"] = "example.com"""")
            assertContains(app, """flavorDimensions += listOf("tier")""")
            assertContains(app, """create("free")""")
            assertContains(app, """applicationIdSuffix = ".free"""")
            assertContains(app, "isMinifyEnabled = true")
            assertContains(app, "isShrinkResources = true")
            assertContains(app, """proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")""")
            assertContains(app, "compose = true")
            assertContains(app, "viewBinding = true")
            assertContains(app, "sourceCompatibility = JavaVersion.VERSION_17")
            assertContains(app, "jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)")
            // The conventional debug type says nothing AGP doesn't already do, so it isn't written.
            assertFalse("""getByName("debug")""" in app, "a conventional build type is left to AGP")
            // A signing config lives in the app's keystore registry, not the project: reported, never invented.
            assertTrue(GradleProjectExport.render(env.dir).notes.any { "upload" in it && "keystore" in it })
        }
    }

    @Test
    fun rendersDependenciesWithBomsExclusionsAndProjectRefs() {
        testEnv("gradle-export-deps") { env ->
            writeProject(env.dir, env.platform)
            val files = GradleProjectExport.render(env.dir).files
            val app = assertNotNull(files["app/build.gradle.kts"])

            assertContains(app, """implementation(project(":lib"))""")
            assertContains(app, """implementation(platform("androidx.compose:compose-bom:2024.09.00"))""")
            assertContains(app, """implementation("androidx.activity:activity-compose:1.9.3")""")
            // A versionless coordinate is legal: the BOM above supplies the version.
            assertContains(app, """implementation("androidx.compose.ui:ui")""")
            assertContains(app, """testImplementation("junit:junit:4.13.2")""")
            assertContains(app, """exclude(group = "com.squareup.okhttp3", module = "okhttp")""")
            // The IDE's bundled stdlib is not a coordinate, and the Kotlin plugin brings its own.
            assertFalse("kotlin-stdlib" in app, "the bundled stdlib is not declared")

            val lib = assertNotNull(files["lib/build.gradle.kts"])
            assertContains(lib, """id("java-library")""")
            assertContains(lib, """id("org.jetbrains.kotlin.jvm")""")
            assertContains(lib, """api("com.google.guava:guava:33.4.8-jre")""")
        }
    }

    @Test
    fun writesTheSupportingFilesAProjectNeeds() {
        testEnv("gradle-export-support") { env ->
            writeProject(env.dir, env.platform)
            val files = GradleProjectExport.render(env.dir).files

            assertContains(assertNotNull(files["gradle.properties"]), "android.useAndroidX=true")
            assertContains(
                assertNotNull(files["gradle/wrapper/gradle-wrapper.properties"]),
                "gradle-${GradleProjectExport.GRADLE_VERSION}-bin.zip",
            )
            assertContains(assertNotNull(files[".gitignore"]), "local.properties")
            // The release build type names a rules file the native project never had; AGP fails without it.
            assertNotNull(files["app/proguard-rules.pro"], "a referenced R8 rules file is written")
            val notes = assertNotNull(files["GRADLE-EXPORT.md"])
            assertContains(notes, "Android Gradle plugin ${GradleProjectExport.AGP_VERSION}")
            assertContains(notes, "`:app`")
        }
    }

    @Test
    fun zipCarriesSourcesAndLeavesIdeStateBehind() {
        testEnv("gradle-export-zip") { env ->
            writeProject(env.dir, env.platform)
            val out = env.dir.resolve("out/demo-gradle.zip")

            val outcome = GradleProjectExport.exportZip(env.dir, out, "demo")
            assertTrue(Files.isRegularFile(outcome.zip))

            val entries = ZipFile(out.toFile()).use { zip -> zip.entries().toList().map { it.name } }
            assertContains(entries, "demo/settings.gradle.kts")
            assertContains(entries, "demo/app/build.gradle.kts")
            assertContains(entries, "demo/app/src/main/java/com/example/demo/MainActivity.kt")
            assertContains(entries, "demo/app/src/main/AndroidManifest.xml")
            assertFalse(entries.any { it.contains("/.platform/") }, "IDE state stays out of a Gradle export")
            assertFalse(entries.any { it.endsWith("/module.toml") }, "the IDE module manifests stay out")
        }
    }

    /**
     * Compose is switched on in the IDE by a classpath probe, not a build flag, so a project that only ever
     * declared the runtime still has to export as Compose or its first Gradle build fails on `@Composable`.
     */
    @Test
    fun composeIsInferredFromTheDeclaredRuntime() {
        testEnv("gradle-export-compose-probe") { env ->
            val types = ModuleTypeRegistry(env.platform.extensions)
            types.register(TestType("android-app"), PluginId("android-support"))
            val store = ProjectModel.open(env.dir, env.platform, FacetCodecRegistry().register(AndroidFacetCodec))
            store.workspace.beginModification().apply {
                addProject("Probe", BuildSystemId.NATIVE, store.vfs.root()); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", types.resolve("android-app")).apply {
                    addSourceSet(androidSourceSet("main"))
                    addDependency(LibraryDependency(LibraryRef("androidx.compose.material3:material3:1.3.1"), DependencyScope.IMPLEMENTATION))
                    // The facet says nothing about Compose: the runtime on the classpath is what turns it on.
                    putFacet(AndroidFacet(namespace = "com.example.probe", compileSdk = 34))
                }
                commit()
            }
            store.save()
            env.dir.writeSource("app/src/main/java/com/example/probe/Home.kt", "package com.example.probe")

            val rendered = GradleProjectExport.render(env.dir)
            val app = assertNotNull(rendered.files["app/build.gradle.kts"])
            assertContains(app, """id("org.jetbrains.kotlin.plugin.compose")""")
            assertContains(app, "compose = true")
            assertTrue(rendered.notes.any { "Compose" in it }, "an inferred plugin is reported, not silent")
        }
    }

    /**
     * An unconventional layout is what Gradle cannot infer, so it has to be written down: source roots
     * Gradle would not look in, and a manifest that is not where AGP expects it.
     */
    @Test
    fun unconventionalRootsAndManifestAreWrittenDown() {
        testEnv("gradle-export-layout") { env ->
            val types = ModuleTypeRegistry(env.platform.extensions)
            types.register(TestType("android-app"), PluginId("android-support"))
            val store = ProjectModel.open(env.dir, env.platform, FacetCodecRegistry().register(AndroidFacetCodec))
            store.workspace.beginModification().apply {
                addProject("Odd", BuildSystemId.NATIVE, store.vfs.root()); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", types.resolve("android-app")).apply {
                    addSourceSet(
                        SourceSetTemplate(
                            "main", DependencyScope.IMPLEMENTATION,
                            mapOf(
                                "sources" to setOf(ContentRole.SOURCE),
                                "resources/res" to setOf(ContentRole.ANDROID_RES),
                                "src/main/assets" to setOf(ContentRole.ASSETS),
                            ),
                        ),
                    )
                    addDependency(
                        LibraryDependency(
                            LibraryRef("com.squareup.leakcanary:leakcanary-android:2.14"),
                            DependencyScope.IMPLEMENTATION,
                            variant = "debug",
                        ),
                    )
                    putFacet(
                        AndroidFacet(
                            namespace = "com.example.odd",
                            compileSdk = 34,
                            manifest = "config/AndroidManifest.xml",
                        ),
                    )
                }
                commit()
            }
            store.save()

            val app = assertNotNull(GradleProjectExport.render(env.dir).files["app/build.gradle.kts"])
            // One block for `main`, carrying both the manifest and the roots.
            assertEquals(1, Regex("""getByName\("main"\)""").findAll(app).count())
            assertContains(app, """manifest.srcFile("config/AndroidManifest.xml")""")
            assertContains(app, """java.srcDirs("sources")""")
            assertContains(app, """res.srcDirs("resources/res")""")
            // The assets root is where AGP already looks, so it is left unsaid.
            assertFalse("assets.srcDirs" in app, "a conventional root needs no override")
            // A variant-scoped dependency keeps its variant: the configuration name carries it.
            assertContains(app, """debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")""")
        }
    }

    /** A module whose directory IS the project root has no `include`: its script folds into the root one. */
    @Test
    fun aRootModuleFoldsIntoTheRootScript() {
        testEnv("gradle-export-root-module") { env ->
            val types = ModuleTypeRegistry(env.platform.extensions)
            types.register(TestType("java-lib"), PluginId("java-support"))
            val store = ProjectModel.open(env.dir, env.platform, FacetCodecRegistry())
            store.workspace.beginModification().apply {
                addProject("Single", BuildSystemId.NATIVE, store.vfs.root()); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("single", types.resolve("java-lib")).apply {
                    dirRelPath = ""
                    addSourceSet(
                        SourceSetTemplate(
                            "main", DependencyScope.IMPLEMENTATION,
                            mapOf("src/main/java" to setOf(ContentRole.SOURCE)),
                        ),
                    )
                }
                commit()
            }
            store.save()
            env.dir.writeSource("src/main/java/com/example/Main.java", "package com.example;\npublic class Main { public static void main(String[] a) {} }")

            val files = GradleProjectExport.render(env.dir).files
            assertFalse("include(" in assertNotNull(files["settings.gradle.kts"]), "the root module is not included")
            val root = assertNotNull(files["build.gradle.kts"])
            // Its plugins carry versions and are applied here, not declared for someone else.
            assertContains(root, """id("java-library")""")
            assertFalse("apply false" in root, "the root project applies its own plugins")
            // The entry point is read off disk, so the exported project stays runnable with `gradle run`.
            assertContains(root, """id("application")""")
            assertContains(root, """mainClass.set("com.example.Main")""")
        }
    }

    /** The two directions have to agree: what the exporter writes, the importer has to read back. */
    @Test
    fun theExportImportsBackAsTheSameProject() {
        testEnv("gradle-export-roundtrip") { env ->
            writeProject(env.dir, env.platform)
            val exported = env.dir.resolve("exported")
            for ((rel, text) in GradleProjectExport.render(env.dir).files) {
                exported.writeSource(rel, text, trim = false)
            }
            // The sources the scripts describe, so the reader sees real module directories.
            exported.writeSource("app/src/main/AndroidManifest.xml", """<manifest/>""")
            exported.writeSource("lib/src/main/kotlin/com/example/lib/Greeter.kt", "package com.example.lib")

            val spec = assertNotNull(GradleImport.parse(exported))
            assertEquals("Demo App", spec.name)
            assertEquals(setOf("app", "lib"), spec.modules.map { it.name }.toSet())

            val app = spec.modules.first { it.name == "app" }
            assertEquals(GradleImport.Kind.ANDROID_APP, app.kind)
            assertEquals("com.example.demo", app.namespace)
            assertEquals(34, app.compileSdk)
            assertEquals(24, app.minSdk)
            assertEquals(7, app.versionCode)
            assertEquals("1.2.3", app.versionName)
            assertTrue(app.isKotlin)
            assertTrue(app.isCompose)
            assertTrue(app.viewBinding)
            assertEquals(listOf("lib"), app.moduleDeps.map { it.name })
            assertContains(app.mavenDeps.map { it.coordinate }, "androidx.activity:activity-compose:1.9.3")
            assertContains(app.platformDeps.map { it.coordinate }, "androidx.compose:compose-bom:2024.09.00")
            assertEquals(listOf("tier"), app.flavorDimensions)
            assertContains(app.productFlavors.map { it.name }, "free")

            val lib = spec.modules.first { it.name == "lib" }
            assertEquals(GradleImport.Kind.JAVA, lib.kind)
            assertContains(lib.mavenDeps.map { it.coordinate }, "com.google.guava:guava:33.4.8-jre")
        }
    }
}
