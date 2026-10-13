package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.core.gradle.GradleImport
import dev.ide.core.gradle.GradleKmp
import dev.ide.core.gradle.GradleProjectImporter
import dev.ide.core.project.ProjectManager
import dev.ide.lang.kotlin.build.KotlinFacet
import dev.ide.model.ContentRole
import dev.ide.model.template.TemplateArgs
import dev.ide.model.LibraryDependency
import dev.ide.model.DependencyScope
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.SyncReason
import dev.ide.model.sync.SyncRequest
import dev.ide.platform.ProgressReporter
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin Multiplatform projects opened straight from their scripts: one target per module, its source sets
 * collapsed into one compilation, with the common half named so `expect`/`actual` compiles.
 */
class GradleKmpImportTest {

    private fun Path.w(rel: String, text: String) {
        val f = resolve(rel); Files.createDirectories(f.parent); f.writeText(text.trimIndent())
    }

    private fun Path.dir(rel: String) { Files.createDirectories(resolve(rel)) }

    /** The shape the Kotlin Multiplatform wizard generates: an Android + iOS + desktop Compose app over a
     *  shared module, wired with a version catalog, `compose.*` accessors and type-safe project accessors. */
    private fun writeWizardProject(proj: Path) {
        proj.w("settings.gradle.kts", """
            rootProject.name = "KotlinProject"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
            include(":composeApp")
            include(":shared")
        """)
        proj.w("build.gradle.kts", """
            plugins {
                alias(libs.plugins.androidApplication) apply false
                alias(libs.plugins.androidLibrary) apply false
                alias(libs.plugins.composeMultiplatform) apply false
                alias(libs.plugins.composeCompiler) apply false
                alias(libs.plugins.kotlinMultiplatform) apply false
            }
        """)
        proj.w("gradle/libs.versions.toml", """
            [versions]
            agp = "8.7.3"
            android-compileSdk = "35"
            android-minSdk = "24"
            androidx-activityCompose = "1.10.1"
            composeMultiplatform = "1.8.2"
            kotlin = "2.2.0"

            [libraries]
            kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
            androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activityCompose" }

            [plugins]
            androidApplication = { id = "com.android.application", version.ref = "agp" }
            androidLibrary = { id = "com.android.library", version.ref = "agp" }
            composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
            composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
            kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
        """)
        proj.w("composeApp/build.gradle.kts", """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                alias(libs.plugins.kotlinMultiplatform)
                alias(libs.plugins.androidApplication)
                alias(libs.plugins.composeMultiplatform)
                alias(libs.plugins.composeCompiler)
            }

            kotlin {
                androidTarget {
                    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
                }
                listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
                    iosTarget.binaries.framework { baseName = "ComposeApp"; isStatic = true }
                }
                jvm("desktop")

                sourceSets {
                    val desktopMain by getting

                    androidMain.dependencies {
                        implementation(compose.preview)
                        implementation(libs.androidx.activity.compose)
                    }
                    commonMain.dependencies {
                        implementation(compose.runtime)
                        implementation(compose.foundation)
                        implementation(compose.material3)
                        implementation(compose.components.resources)
                        implementation(projects.shared)
                    }
                    commonTest.dependencies {
                        implementation(libs.kotlin.test)
                    }
                    desktopMain.dependencies {
                        implementation(compose.desktop.currentOs)
                    }
                }
            }

            android {
                namespace = "org.example.project"
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                defaultConfig {
                    applicationId = "org.example.project"
                    minSdk = libs.versions.android.minSdk.get().toInt()
                    targetSdk = libs.versions.android.compileSdk.get().toInt()
                }
            }

            dependencies {
                debugImplementation(compose.uiTooling)
            }
        """)
        proj.w("composeApp/src/androidMain/AndroidManifest.xml", "<manifest><application/></manifest>")
        proj.dir("composeApp/src/commonMain/kotlin")
        proj.dir("composeApp/src/androidMain/kotlin")
        proj.dir("composeApp/src/androidMain/res/values")
        proj.dir("composeApp/src/commonMain/composeResources/drawable")
        proj.dir("composeApp/src/commonTest/kotlin")
        proj.dir("composeApp/src/desktopMain/kotlin")
        proj.dir("composeApp/src/iosMain/kotlin")

        proj.w("shared/build.gradle.kts", """
            plugins {
                alias(libs.plugins.kotlinMultiplatform)
                alias(libs.plugins.androidLibrary)
            }
            kotlin {
                androidTarget()
                iosArm64()
                jvm()
                sourceSets {
                    commonMain.dependencies { }
                }
            }
            android {
                namespace = "org.example.project.shared"
                compileSdk = 35
                defaultConfig { minSdk = 24 }
            }
        """)
        proj.dir("shared/src/commonMain/kotlin")
        proj.dir("shared/src/androidMain/kotlin")
    }

    @Test
    fun readsTheWizardAppAsItsAndroidTarget() {
        withTempDir("kmp-wizard") { tmp ->
            val proj = tmp.resolve("KotlinProject")
            writeWizardProject(proj)
            GradleKmp.setPreference(proj, GradleKmp.Platform.ANDROID)

            val spec = assertNotNull(GradleImport.parse(proj))
            val app = spec.modules.first { it.name == "composeApp" }
            assertEquals(GradleImport.Kind.ANDROID_APP, app.kind)
            val kmp = assertNotNull(app.kmp)
            assertEquals(GradleKmp.Platform.ANDROID, kmp.platform)
            assertEquals(
                listOf("commonMain" to true, "androidMain" to false),
                kmp.sourceSets.filter { !it.test }.map { it.name to it.common },
            )
            assertEquals("org.example.project", app.namespace)
            assertEquals(35, app.compileSdk)
            assertEquals(24, app.minSdk)
            assertEquals("src/androidMain/AndroidManifest.xml", app.manifest)

            val coords = app.mavenDeps.associate { it.coordinate to it }
            for (c in listOf(
                "org.jetbrains.compose.runtime:runtime:1.8.2",
                "org.jetbrains.compose.foundation:foundation:1.8.2",
                "org.jetbrains.compose.material3:material3:1.8.2",
                "org.jetbrains.compose.components:components-resources:1.8.2",
                "org.jetbrains.compose.ui:ui-tooling-preview:1.8.2",
                "androidx.activity:activity-compose:1.10.1",
            )) assertEquals(DependencyScope.IMPLEMENTATION, coords[c]?.scope, "$c is a main dependency")
            assertEquals(DependencyScope.TEST_IMPLEMENTATION, coords["org.jetbrains.kotlin:kotlin-test:2.2.0"]?.scope)
            // The framework the Kotlin plugin adds for `kotlin("test")`, where `kotlin.test.Test` actually lives.
            assertEquals(DependencyScope.TEST_IMPLEMENTATION, coords["org.jetbrains.kotlin:kotlin-test-junit:2.2.0"]?.scope)
            assertEquals("debug", coords["org.jetbrains.compose.ui:ui-tooling:1.8.2"]?.variant)
            assertTrue(coords.keys.none { "desktop-jvm" in it }, "the desktop source set is not modeled: $coords")
            assertEquals(listOf("shared"), app.moduleDeps.map { it.name })

            val res = assertNotNull(app.composeResources)
            assertEquals("kotlinproject.composeapp.generated.resources", res.packageName)
            assertEquals(listOf("src/commonMain/composeResources"), res.dirs)

            val note = spec.report.notes.first { it.startsWith("composeApp: Kotlin Multiplatform") }
            assertTrue("iosX64" in note && "desktop" in note, note)

            val shared = spec.modules.first { it.name == "shared" }
            assertEquals(GradleImport.Kind.ANDROID_LIB, shared.kind)
            assertEquals(GradleKmp.Platform.ANDROID, shared.kmp?.platform)
            assertNull(shared.composeResources)
        }
    }

    @Test
    fun mapsTheWizardAppOntoOneCompilationWithItsCommonHalfNamed() {
        withTempDir("kmp-wizard-model") { tmp ->
            val proj = tmp.resolve("KotlinProject")
            writeWizardProject(proj)
            GradleKmp.setPreference(proj, GradleKmp.Platform.ANDROID)

            val model = runBlocking {
                GradleProjectImporter().resolve(SyncRequest(proj, NoProgress, SyncReason.IMPORT))
            }.model!!
            val app = model.modules.first { it.name == "composeApp" }
            assertEquals("android-app", app.typeId)

            // Folded into `main`, the only name an Android variant compiles.
            val main = app.sourceSets.first { it.name == "main" }
            assertEquals(setOf(ContentRole.SOURCE), main.roots["src/commonMain/kotlin"])
            assertEquals(setOf(ContentRole.SOURCE), main.roots["src/androidMain/kotlin"])
            assertEquals(setOf(ContentRole.ANDROID_RES), main.roots["src/androidMain/res"])
            assertTrue(main.roots.keys.none { "desktopMain" in it || "iosMain" in it }, "${main.roots}")
            assertEquals(
                setOf("src/commonTest/kotlin"),
                app.sourceSets.first { it.name == "test" }.roots.keys,
            )

            val facets = app.facets.associateBy { it.table }
            assertEquals(listOf("src/commonMain/kotlin"), facets.getValue("kotlin").values["commonSourceRoots"])
            assertEquals("src/androidMain/AndroidManifest.xml", facets.getValue("android").values["manifest"])
            assertEquals(
                "kotlinproject.composeapp.generated.resources",
                facets.getValue("composeResources").values["package"],
            )
            assertTrue(app.dependencies.any { it is ExternalModuleRef && it.moduleName == "shared" })
            assertTrue(app.dependencies.any {
                it is ExternalLibrary && it.coordinate == "org.jetbrains.compose.runtime:runtime:1.8.2"
            })
        }
    }

    /** With no choice recorded, the host decides: the desktop (these tests' JVM) imports the desktop target. */
    @Test
    fun theDesktopHostImportsTheWizardForItsJvmTarget() {
        withTempDir("kmp-wizard-desktop") { tmp ->
            val proj = tmp.resolve("KotlinProject")
            writeWizardProject(proj)
            assertEquals(GradleKmp.Platform.JVM, GradleKmp.hostDefault())
            assertNull(GradleKmp.readPreference(proj))

            val spec = assertNotNull(GradleImport.parse(proj))
            val app = spec.modules.first { it.name == "composeApp" }
            assertEquals(GradleImport.Kind.JAVA, app.kind, "the Android plugin does not make a desktop import Android")
            val kmp = assertNotNull(app.kmp)
            assertEquals(GradleKmp.Platform.JVM, kmp.platform)
            assertEquals(setOf(GradleKmp.Platform.ANDROID, GradleKmp.Platform.JVM), kmp.available)
            assertEquals(listOf("commonMain", "desktopMain"), kmp.sourceSets.filter { !it.test }.map { it.name })
            val coords = app.mavenDeps.map { it.coordinate }
            assertTrue(coords.any { it.startsWith("org.jetbrains.compose.desktop:desktop-jvm-") }, "$coords")
            assertTrue("androidx.activity:activity-compose:1.10.1" !in coords, "$coords")
            assertNull(app.manifest)
            assertTrue("android" in spec.report.notes.first { it.startsWith("composeApp:") })
            assertEquals(GradleKmp.Platform.JVM, spec.modules.first { it.name == "shared" }.kmp?.platform)

            // The project offers the choice, currently the host's.
            assertEquals(GradleKmp.Platform.JVM, GradleImport.kmpTargetChoice(proj))
            GradleKmp.setPreference(proj, GradleKmp.Platform.ANDROID)
            assertEquals(GradleKmp.Platform.ANDROID, GradleKmp.readPreference(proj))
            assertEquals(GradleKmp.Platform.ANDROID, GradleImport.kmpTargetChoice(proj))
        }
    }

    /**
     * Switching target in an open project rebuilds the modules it changes: a refresh alone would keep the
     * desktop import's type and `desktopMain` root next to the Android ones, and the reverse leaves the
     * `[android]` facet behind.
     */
    @Test
    fun switchingTargetRebuildsTheModulesItChanges() {
        withTempDir("kmp-switch") { tmp ->
            val source = tmp.resolve("KotlinProject")
            writeWizardProject(source)
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                val ide = assertNotNull(manager.importExternalProject(source), "the Gradle importer claims the wizard")
                ide.use {
                    fun app() = it.modules().single { m -> m.name == "composeApp" }
                    fun roots() = app().sourceSets.single { s -> s.name == "main" }.contentRoots
                        .map { r -> r.dir.path.replace('\\', '/').substringAfter("composeApp/") }
                    assertEquals(GradleKmp.Platform.JVM, it.kmpTargetChoice())
                    assertEquals("java-lib", app().type.id)
                    assertTrue("src/desktopMain/kotlin" in roots(), "${roots()}")

                    val toAndroid = runBlocking { it.switchKmpTarget(GradleKmp.Platform.ANDROID) }
                    assertTrue(toAndroid.ok, toAndroid.message)
                    assertEquals(GradleKmp.Platform.ANDROID, it.kmpTargetChoice())
                    assertEquals("android-app", app().type.id)
                    assertTrue("src/androidMain/kotlin" in roots() && "src/desktopMain/kotlin" !in roots(), "${roots()}")
                    assertEquals("src/androidMain/AndroidManifest.xml", app().facets.get(AndroidFacet.KEY)?.manifest)
                    assertEquals(listOf("src/commonMain/kotlin"), app().facets.get(KotlinFacet.KEY)?.commonSourceRoots)
                    assertEquals("android-lib", it.modules().single { m -> m.name == "shared" }.type.id)

                    val toJvm = runBlocking { it.switchKmpTarget(GradleKmp.Platform.JVM) }
                    assertTrue(toJvm.ok, toJvm.message)
                    assertEquals("java-lib", app().type.id)
                    assertNull(app().facets.get(AndroidFacet.KEY), "the Android import's facet does not survive")
                    assertTrue("src/androidMain/kotlin" !in roots() && "src/desktopMain/kotlin" in roots(), "${roots()}")
                }
            } finally {
                manager.dispose()
            }
        }
    }

    /**
     * The Compose Multiplatform template, created as the Create-Project screen creates it: it writes a Gradle
     * KMP project and the importer reads it in, so the result is a compatibility-mode project on the host's
     * target that can switch to Android.
     */
    @Test
    fun theComposeMultiplatformTemplateCreatesAnImportedKmpProject() {
        withTempDir("kmp-template-app") { tmp ->
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                val ide = manager.create(
                    "kmp-compose-app",
                    mapOf(TemplateArgs.NAME to "My App", TemplateArgs.PACKAGE to "com.example.myapp"),
                )
                ide.use {
                    assertTrue(it.isCompatibilityMode(), "a Gradle-backed project")
                    assertEquals(GradleKmp.Platform.JVM, it.kmpTargetChoice())
                    val app = it.modules().single()
                    assertEquals("composeApp", app.name)
                    assertEquals("java-lib", app.type.id)
                    val roots = app.sourceSets.flatMap { s -> s.contentRoots }.map { r -> r.dir.path.replace('\\', '/') }
                    assertTrue(roots.any { r -> r.endsWith("composeApp/src/commonMain/kotlin") }, "$roots")
                    assertTrue(roots.any { r -> r.endsWith("composeApp/src/desktopMain/kotlin") }, "$roots")
                    assertEquals(listOf("src/commonMain/kotlin"), app.facets.get(KotlinFacet.KEY)?.commonSourceRoots)
                    val libs = app.dependencies.filterIsInstance<LibraryDependency>().map { d -> d.library.name }
                    assertTrue("org.jetbrains.compose.runtime:runtime:1.11.1" in libs, "$libs")
                    assertTrue("org.jetbrains.compose.material3:material3:1.9.0" in libs, "$libs")
                    assertTrue("org.jetbrains.compose.components:components-ui-tooling-preview:1.11.1" in libs, "$libs")
                    val appSource = Files.readString(it.workspaceRoot.resolve("composeApp/src/commonMain/kotlin/com/example/myapp/App.kt"))
                    assertTrue("@Preview" in appSource && "fun AppPreview()" in appSource, "the template ships a preview")
                    assertTrue(libs.any { l -> l.startsWith("org.jetbrains.compose.desktop:desktop-jvm-") }, "$libs")
                    assertTrue(manager.list().any { p -> p.rootPath == it.workspaceRoot.toString() }, "it lists in the picker")

                    val toAndroid = runBlocking { it.switchKmpTarget(GradleKmp.Platform.ANDROID) }
                    assertTrue(toAndroid.ok, toAndroid.message)
                    val android = it.modules().single()
                    assertEquals("android-app", android.type.id)
                    val facet = assertNotNull(android.facets.get(AndroidFacet.KEY))
                    assertEquals("com.example.myapp", facet.namespace)
                    assertEquals("src/androidMain/AndroidManifest.xml", facet.manifest)
                }
            } finally {
                manager.dispose()
            }
        }
    }

    /** The library template: one `shared` module with Android and JVM targets and a common test. */
    @Test
    fun theKmpLibraryTemplateCreatesASharedModule() {
        withTempDir("kmp-template-lib") { tmp ->
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                manager.create("kmp-library", mapOf(TemplateArgs.NAME to "Shared Lib", TemplateArgs.PACKAGE to "com.example.shared")).use {
                    val shared = it.modules().single()
                    assertEquals("shared", shared.name)
                    assertEquals("java-lib", shared.type.id)
                    val sets = shared.sourceSets.associate { s -> s.name to s.contentRoots.map { r -> r.dir.path.replace('\\', '/').substringAfter("shared/") } }
                    assertTrue("src/jvmMain/kotlin" in sets.getValue("main"), "$sets")
                    assertEquals(listOf("src/commonTest/kotlin"), sets.getValue("test"))

                    assertTrue(runBlocking { it.switchKmpTarget(GradleKmp.Platform.ANDROID) }.ok)
                    assertEquals("android-lib", it.modules().single().type.id)
                }
            } finally {
                manager.dispose()
            }
        }
    }

    /** Opting into iOS adds its targets and `iosMain`, which the import reports as not modeled. */
    @Test
    fun theIosOptionWritesTargetsTheImportSkips() {
        withTempDir("kmp-template-ios") { tmp ->
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                val args = mapOf(TemplateArgs.NAME to "Everywhere", TemplateArgs.PACKAGE to "com.example.everywhere", "ios" to "true")
                manager.create("kmp-compose-app", args).use {
                    val root = it.workspaceRoot
                    val script = Files.readString(root.resolve("composeApp/build.gradle.kts"))
                    assertTrue("    jvm(\"desktop\")\n\n    listOf(iosArm64(), iosSimulatorArm64())" in script, script)
                    assertTrue(Files.isRegularFile(root.resolve("composeApp/src/iosMain/kotlin/com/example/everywhere/MainViewController.kt")))
                    assertTrue(it.compatibilityNotes().any { n -> "iosArm64" in n }, "${it.compatibilityNotes()}")
                    val roots = it.modules().single().sourceSets.flatMap { s -> s.contentRoots }.map { r -> r.dir.path }
                    assertTrue(roots.none { r -> "iosMain" in r }, "$roots")
                }
            } finally {
                manager.dispose()
            }
        }
    }

    /** `kotlin("test")` under `useJUnitPlatform()` gets JUnit 5's framework, versionless like the shorthand;
     *  an explicitly declared framework is left alone. */
    @Test
    fun kotlinTestGetsTheFrameworkTheKotlinPluginWouldAdd() {
        withTempDir("kotlin-test-framework") { tmp ->
            val proj = tmp.resolve("Jvm")
            proj.w("settings.gradle.kts", "rootProject.name = \"Jvm\"\ninclude(\":app\", \":lib\")")
            proj.w("app/build.gradle.kts", """
                plugins { kotlin("jvm") version "2.2.20" }
                dependencies { testImplementation(kotlin("test")) }
                tasks.test { useJUnitPlatform() }
            """)
            proj.w("lib/build.gradle.kts", """
                plugins { kotlin("jvm") version "2.2.20" }
                dependencies {
                    testImplementation(kotlin("test"))
                    testImplementation(kotlin("test-testng"))
                }
            """)
            val modules = assertNotNull(GradleImport.parse(proj)).modules.associateBy { it.name }
            val app = modules.getValue("app").mavenDeps.associate { it.coordinate to it.scope }
            assertEquals(DependencyScope.TEST_IMPLEMENTATION, app["org.jetbrains.kotlin:kotlin-test-junit5"], "$app")
            val lib = modules.getValue("lib").mavenDeps.map { it.coordinate }
            assertEquals(listOf("org.jetbrains.kotlin:kotlin-test", "org.jetbrains.kotlin:kotlin-test-testng"), lib)
        }
    }

    /** A project whose modules can only be one thing has no choice to offer. */
    @Test
    fun aSingleTargetProjectOffersNoChoice() {
        withTempDir("kmp-no-choice") { tmp ->
            val proj = tmp.resolve("Lib")
            proj.w("settings.gradle.kts", "rootProject.name = \"Lib\"\ninclude(\":core\")")
            proj.w("core/build.gradle.kts", """
                plugins { kotlin("multiplatform") }
                kotlin { jvm(); iosArm64() }
            """)
            assertNull(GradleImport.kmpTargetChoice(proj))
        }
    }

    /**
     * The Compose plugin pins Material 3 at 1.9.0 from 1.9 on and the extended icons at 1.7.3 from 1.8 on,
     * while the rest follow the plugin (read from the published plugin jars).
     */
    @Test
    fun versionsComposeAccessorsAsThePluginDoes() {
        withTempDir("kmp-compose-versions") { tmp ->
            val proj = tmp.resolve("App")
            proj.w("settings.gradle.kts", "rootProject.name = \"App\"\ninclude(\":app\")")
            proj.w("build.gradle.kts", """
                plugins { id("org.jetbrains.compose") version "1.10.0" apply false }
            """)
            proj.w("app/build.gradle.kts", """
                plugins {
                    kotlin("multiplatform")
                    id("org.jetbrains.compose")
                }
                kotlin {
                    jvm()
                    sourceSets {
                        commonMain.dependencies {
                            implementation(compose.runtime)
                            implementation(compose.material3)
                            implementation(compose.materialIconsExtended)
                        }
                    }
                }
            """)
            val app = assertNotNull(GradleImport.parse(proj)).modules.single()
            assertEquals(
                listOf(
                    "org.jetbrains.compose.runtime:runtime:1.10.0",
                    "org.jetbrains.compose.material3:material3:1.9.0",
                    "org.jetbrains.compose.material:material-icons-extended:1.7.3",
                ),
                app.mavenDeps.map { it.coordinate },
            )
        }
    }

    /** A JVM-only module whose platform set sits under a hand-made intermediate set, which must be common too. */
    @Test
    fun followsDependsOnToAnIntermediateSourceSet() {
        withTempDir("kmp-intermediate") { tmp ->
            val proj = tmp.resolve("Lib")
            proj.w("settings.gradle.kts", "rootProject.name = \"Lib\"\ninclude(\":core\")")
            proj.w("core/build.gradle.kts", """
                plugins { kotlin("multiplatform") version "2.2.0" }
                kotlin {
                    jvm()
                    iosArm64()
                    sourceSets {
                        val jvmShared by creating {
                            dependsOn(commonMain.get())
                        }
                        jvmMain.get().dependsOn(jvmShared)
                        jvmMain.dependencies {
                            implementation("com.squareup.okio:okio:3.9.0")
                        }
                        iosMain.dependencies {
                            implementation("io.ktor:ktor-client-darwin:3.0.0")
                        }
                    }
                }
            """)
            proj.dir("core/src/commonMain/kotlin")
            proj.dir("core/src/jvmShared/kotlin")
            proj.dir("core/src/jvmMain/kotlin")

            val core = assertNotNull(GradleImport.parse(proj)).modules.single()
            assertEquals(GradleImport.Kind.JAVA, core.kind)
            val kmp = assertNotNull(core.kmp)
            assertEquals(GradleKmp.Platform.JVM, kmp.platform)
            assertEquals(
                listOf("commonMain" to true, "jvmShared" to true, "jvmMain" to false),
                kmp.sourceSets.filter { !it.test }.map { it.name to it.common },
            )
            assertEquals(listOf("com.squareup.okio:okio:3.9.0"), core.mavenDeps.map { it.coordinate })

            val model = runBlocking {
                GradleProjectImporter().resolve(SyncRequest(proj, NoProgress, SyncReason.IMPORT))
            }.model!!.modules.single()
            assertEquals(
                listOf("src/commonMain/kotlin", "src/jvmShared/kotlin"),
                model.facets.first { it.table == "kotlin" }.values["commonSourceRoots"],
            )
        }
    }

    /** AGP's KMP library plugin keeps the Android config inside `kotlin { androidLibrary { } }`. */
    @Test
    fun readsTheAndroidConfigOfTheKmpLibraryPlugin() {
        withTempDir("kmp-android-library") { tmp ->
            val proj = tmp.resolve("Lib")
            proj.w("settings.gradle.kts", "rootProject.name = \"Lib\"\ninclude(\":data\")")
            proj.w("data/build.gradle.kts", """
                plugins {
                    id("org.jetbrains.kotlin.multiplatform")
                    id("com.android.kotlin.multiplatform.library")
                }
                kotlin {
                    androidLibrary {
                        namespace = "com.example.data"
                        compileSdk = 35
                        minSdk = 26
                    }
                    sourceSets {
                        androidMain {
                            dependencies { implementation("androidx.core:core-ktx:1.15.0") }
                        }
                    }
                }
            """)

            val data = assertNotNull(GradleImport.parse(proj)).modules.single()
            assertEquals(GradleImport.Kind.ANDROID_LIB, data.kind)
            assertEquals("com.example.data", data.namespace)
            assertEquals(35, data.compileSdk)
            assertEquals(26, data.minSdk)
            assertEquals(listOf("androidx.core:core-ktx:1.15.0"), data.mavenDeps.map { it.coordinate })
        }
    }

    private object NoProgress : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled = false
    }
}
