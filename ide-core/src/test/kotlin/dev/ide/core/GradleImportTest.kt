package dev.ide.core

import dev.ide.core.gradle.GradleProjectImporter
import dev.ide.platform.ProgressReporter
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import dev.ide.model.DependencyScope
import dev.ide.model.sync.SyncReason
import dev.ide.model.sync.SyncRequest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradleImportTest {

    /**
     * The libGDX Android shape, which is where a four-part coordinate is most often met: a hand-created
     * `natives` configuration carrying one classifier jar per ABI, plus the copy task that unpacks them.
     * Both the coordinates and the configuration used to be dropped, the coordinates because a fourth
     * segment matched no coordinate pattern and the configuration because it is not a Gradle built-in.
     */
    @Test
    fun importsAHandCreatedNativesConfigurationWithItsClassifierCoordinates() {
        withTempDir("gradle-import-natives") { dir ->
            fun w(rel: String, text: String) {
                val f = dir.resolve(rel); Files.createDirectories(f.parent); f.writeText(text.trimIndent())
            }
            w("settings.gradle", """
                rootProject.name = 'MyGame'
                include ':app'
            """)
            w("build.gradle", "// top-level")
            w("app/build.gradle", """
                apply plugin: 'com.android.application'
                android {
                    namespace "com.example.game"
                    compileSdkVersion 34
                    defaultConfig { minSdkVersion 24 }
                }
                configurations { natives }
                dependencies {
                    implementation "com.badlogicgames.gdx:gdx:1.14.2"
                    implementation "com.badlogicgames.gdx:gdx-backend-android:1.14.2"
                    natives "com.badlogicgames.gdx:gdx-platform:1.14.2:natives-armeabi-v7a"
                    natives "com.badlogicgames.gdx:gdx-platform:1.14.2:natives-arm64-v8a"
                    natives "com.badlogicgames.gdx:gdx-platform:1.14.2:natives-x86_64"
                }
            """)
            Files.createDirectories(dir.resolve("app/src/main/java"))

            val app = assertNotNull(GradleImport.parse(dir)).modules.single()
            assertEquals(
                listOf(
                    "com.badlogicgames.gdx:gdx-platform:1.14.2:natives-arm64-v8a",
                    "com.badlogicgames.gdx:gdx-platform:1.14.2:natives-armeabi-v7a",
                    "com.badlogicgames.gdx:gdx-platform:1.14.2:natives-x86_64",
                ),
                app.mavenDeps.filter { it.scope == DependencyScope.NATIVES }.map { it.coordinate }.sorted(),
            )
            assertEquals(
                listOf("com.badlogicgames.gdx:gdx-backend-android:1.14.2", "com.badlogicgames.gdx:gdx:1.14.2"),
                app.mavenDeps.filter { it.scope == DependencyScope.IMPLEMENTATION }.map { it.coordinate }.sorted(),
            )
        }
    }

    /** Lay down a two-module legacy Gradle project (android `app` -> java `core`) like older versions produced. */
    private fun writeLegacyGradleProject(dir: Path) {
        fun w(rel: String, text: String) {
            val f = dir.resolve(rel)
            Files.createDirectories(f.parent)
            f.writeText(text.trimIndent())
        }
        w("settings.gradle", """
            rootProject.name = 'MyApp'
            include ':app', ':core'
        """)
        w("build.gradle", "// top-level")
        w("app/build.gradle", """
            apply plugin: 'com.android.application'
            android {
                namespace "com.example.myapp"
                compileSdkVersion 33
                defaultConfig {
                    minSdkVersion 24
                    targetSdkVersion 33
                }
            }
            dependencies {
                implementation 'androidx.appcompat:appcompat:1.6.1'
                api project(':core')
                testImplementation 'junit:junit:4.13.2'
            }
        """)
        w("app/src/main/AndroidManifest.xml", """<manifest package="com.example.myapp"/>""")
        w("app/src/main/java/com/example/myapp/MainActivity.java", "package com.example.myapp; class MainActivity {}")
        w("core/build.gradle", """
            apply plugin: 'java-library'
            dependencies {
                implementation 'com.google.guava:guava:31.1-jre'
            }
        """)
        w("core/src/main/java/com/example/core/Core.java", "package com.example.core; public class Core {}")
    }

    /** The tolerant reader extracts modules, types, the android SDK/namespace, and dependency coordinates+scopes. */
    @Test
    fun parsesModulesTypesAndDependencies() {
        withTempDir("gradle-parse") { tmp ->
            val proj = tmp.resolve("MyApp")
            writeLegacyGradleProject(proj)

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            assertEquals("MyApp", spec.name)
            assertEquals(setOf("app", "core"), spec.modules.map { it.name }.toSet())

            val app = spec.modules.first { it.name == "app" }
            assertEquals(GradleImport.Kind.ANDROID_APP, app.kind)
            assertEquals("com.example.myapp", app.namespace)
            assertEquals(33, app.compileSdk)
            assertEquals(24, app.minSdk)
            assertEquals(33, app.targetSdk)
            assertEquals(listOf("core"), app.moduleDeps.map { it.name })
            assertEquals(DependencyScope.API, app.moduleDeps.first().scope)
            // appcompat (implementation) + junit (testImplementation) are kept; the project(:core) dep is not a maven coord.
            assertEquals(
                setOf("androidx.appcompat:appcompat:1.6.1", "junit:junit:4.13.2"),
                app.mavenDeps.map { it.coordinate }.toSet(),
            )

            val core = spec.modules.first { it.name == "core" }
            assertEquals(GradleImport.Kind.JAVA, core.kind)
            assertEquals(listOf("com.google.guava:guava:31.1-jre"), core.mavenDeps.map { it.coordinate })
        }
    }

    /** End to end: a legacy Gradle project in a legacy data dir is imported into the picker in compatibility mode. */
    @Test
    fun importsGradleProjectInCompatibilityMode() {
        withTempDir("gradle-import") { tmp ->
            val legacyHome = tmp.resolve("legacy")
            writeLegacyGradleProject(legacyHome.resolve("MyApp"))

            val manager = ProjectManager.desktop(
                tmp.resolve("projects"),
                legacyDataDirs = listOf(legacyHome),
            )
            assertTrue(manager.isEmpty(), "fresh root starts empty")

            assertEquals(1, manager.importLegacyProjects(), "one Gradle project recovered")

            val listed = manager.list()
            assertEquals(1, listed.size)
            assertEquals("MyApp", listed.first().name)
            assertEquals(2, listed.first().moduleCount, "app + core modules")
            assertTrue(listed.first().compatibility, "flagged as compatibility mode")

            // It opens, and the imported modules + sources are present.
            manager.open(listed.first().rootPath).use { ide ->
                assertEquals(setOf("app", "core"), ide.moduleNames().toSet())
            }
            val dest = Path.of(listed.first().rootPath)
            assertTrue(
                Files.exists(dest.resolve("app/src/main/java/com/example/myapp/MainActivity.java")),
                "sources were copied into the imported project",
            )
        }
    }

    /** A modern AGP project: Kotlin DSL, a version catalog, a Compose BOM platform, variables, build types + flavors. */
    private fun writeModernGradleProject(dir: Path) {
        fun w(rel: String, text: String) {
            val f = dir.resolve(rel)
            Files.createDirectories(f.parent)
            f.writeText(text.trimIndent())
        }
        w("settings.gradle.kts", """
            rootProject.name = "Modern"
            include(":app", ":core")
        """)
        w("gradle.properties", "leakVersion=2.12")
        w("gradle/libs.versions.toml", """
            [versions]
            coreKtx = "1.12.0"
            kotlin = "1.9.22"
            junit = "4.13.2"

            [libraries]
            androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
            compose-ui = { group = "androidx.compose.ui", name = "ui", version = "1.6.1" }
            compose-material3 = "androidx.compose.material3:material3:1.2.0"
            junit = { module = "junit:junit", version.ref = "junit" }

            [bundles]
            compose = ["compose-ui", "compose-material3"]

            [plugins]
            kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
        """)
        w("app/build.gradle.kts", """
            plugins {
                id("com.android.application")
                alias(libs.plugins.kotlin.android)
            }
            android {
                namespace = "com.example.modern"
                compileSdk = 34
                defaultConfig {
                    minSdk = 24
                    targetSdk = 34
                }
                flavorDimensions += "tier"
                productFlavors {
                    create("free") { dimension = "tier" }
                    create("paid") { dimension = "tier" }
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                    }
                    debug {
                    }
                }
                buildFeatures {
                    compose = true
                }
            }
            dependencies {
                implementation(platform("androidx.compose:compose-bom:2024.02.00"))
                implementation(libs.androidx.core.ktx)
                implementation(libs.bundles.compose)
                debugImplementation("com.squareup.leakcanary:leakcanary-android:${'$'}leakVersion")
                implementation(project(":core"))
                testImplementation(libs.junit)
            }
        """)
        w("app/src/main/AndroidManifest.xml", """<manifest package="com.example.modern"/>""")
        w("core/build.gradle", """
            plugins {
                id 'java-library'
            }
            ext {
                guavaVersion = '32.1.3-jre'
            }
            dependencies {
                // a commented-out line must be ignored
                // implementation 'should.not:appear:1.0'
                implementation "com.google.guava:guava:${'$'}guavaVersion"
            }
        """)
        w("core/src/main/java/com/example/core/Core.java", "package com.example.core; public class Core {}")
    }

    /** The tolerant reader resolves version-catalog accessors, `$var`/property interpolation, and BOM platforms. */
    @Test
    fun parsesCatalogVariablesAndPlatforms() {
        withTempDir("gradle-modern") { tmp ->
            val proj = tmp.resolve("Modern")
            writeModernGradleProject(proj)

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            assertEquals("Modern", spec.name)

            val app = spec.modules.first { it.name == "app" }
            assertEquals(GradleImport.Kind.ANDROID_APP, app.kind)
            assertTrue(app.isKotlin, "kotlin.android plugin resolved through the catalog alias")
            assertTrue(app.isCompose, "buildFeatures { compose = true }")
            assertEquals("com.example.modern", app.namespace)
            assertEquals(34, app.compileSdk)
            assertEquals(24, app.minSdk)

            val coords = app.mavenDeps.map { it.coordinate }.toSet()
            assertContains(coords, "androidx.core:core-ktx:1.12.0")          // library { module, version.ref }
            assertContains(coords, "androidx.compose.ui:ui:1.6.1")           // bundle → { group, name, version }
            assertContains(coords, "androidx.compose.material3:material3:1.2.0") // bundle → shorthand string
            assertContains(coords, "junit:junit:4.13.2")                     // testImplementation via catalog
            assertContains(coords, "com.squareup.leakcanary:leakcanary-android:2.12") // $var from gradle.properties

            // The Compose BOM is a platform, not a normal library.
            assertEquals(listOf("androidx.compose:compose-bom:2024.02.00"), app.platformDeps.map { it.coordinate })
            assertFalse(coords.any { it.startsWith("androidx.compose:compose-bom") }, "BOM isn't a library dep")

            // junit is test-scoped; the debug-only dep carries its variant qualifier.
            assertEquals(DependencyScope.TEST_IMPLEMENTATION, app.mavenDeps.first { it.coordinate.startsWith("junit:") }.scope)
            assertEquals("debug", app.mavenDeps.first { it.coordinate.contains("leakcanary") }.variant)

            // Build types + flavors.
            val release = app.buildTypes.first { it.name == "release" }
            assertTrue(release.minifyEnabled && release.shrinkResources)
            assertTrue(app.buildTypes.any { it.name == "debug" })
            assertEquals(listOf("tier"), app.flavorDimensions)
            assertEquals(setOf("free", "paid"), app.productFlavors.map { it.name }.toSet())
            assertTrue(app.productFlavors.all { it.dimension == "tier" })

            assertEquals(listOf("core"), app.moduleDeps.map { it.name })

            // core: Groovy, ext-var version, and the commented-out dependency must be ignored.
            val core = spec.modules.first { it.name == "core" }
            assertEquals(GradleImport.Kind.JAVA, core.kind)
            assertEquals(listOf("com.google.guava:guava:32.1.3-jre"), core.mavenDeps.map { it.coordinate })
        }
    }

    /** An unresolved catalog/variable reference is reported in the sync notes rather than silently dropped. */
    @Test
    fun reportsUnresolvedReferences() {
        withTempDir("gradle-report") { tmp ->
            val proj = tmp.resolve("Rep")
            Files.createDirectories(proj)
            proj.resolve("settings.gradle").writeText("rootProject.name = 'Rep'\ninclude ':app'")
            val app = proj.resolve("app")
            Files.createDirectories(app.resolve("src/main"))
            app.resolve("build.gradle").writeText(
                """
                apply plugin: 'java-library'
                dependencies {
                    implementation libs.does.not.exist
                    implementation "com.example:thing:${'$'}undefinedVersion"
                }
                """.trimIndent(),
            )

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            assertTrue(spec.modules.first().mavenDeps.isEmpty(), "neither unresolved dep is added")
            assertTrue(spec.report.notes.any { "libs.does.not.exist" in it }, "catalog miss noted")
            assertTrue(spec.report.notes.any { "undefinedVersion" in it }, "variable miss noted")
        }
    }

    /** Re-sync re-reads the (edited) scripts into an open project: a new module + a new dependency appear. */
    @Test
    fun resyncsFromEditedScripts() {
        withTempDir("gradle-resync") { tmp ->
            val legacyHome = tmp.resolve("legacy")
            writeLegacyGradleProject(legacyHome.resolve("MyApp"))
            val manager = ProjectManager.desktop(tmp.resolve("projects"), legacyDataDirs = listOf(legacyHome))
            assertEquals(1, manager.importLegacyProjects())
            val summary = manager.list().first()
            val dest = Path.of(summary.rootPath)

            manager.open(summary.rootPath).use { ide ->
                assertEquals(setOf("app", "core"), ide.moduleNames().toSet())
                // Add a brand-new module and a new dependency to the (copied) scripts, then sync.
                dest.resolve("settings.gradle").writeText("rootProject.name = 'MyApp'\ninclude ':app', ':core', ':lib'")
                val lib = dest.resolve("lib")
                Files.createDirectories(lib.resolve("src/main/java/com/example/lib"))
                lib.resolve("build.gradle").writeText("apply plugin: 'java-library'\n")
                lib.resolve("src/main/java/com/example/lib/Lib.java").writeText("package com.example.lib; public class Lib {}")

                val outcome = runBlocking { ide.syncFromBuildFiles() }
                assertTrue(outcome.ok, outcome.message)
                assertContains(ide.moduleNames().toSet(), "lib")
                assertTrue(ide.isCompatibilityMode(), "still flagged as compatibility mode after a sync")
            }
        }
    }

    /** A `build-logic` precompiled convention plugin + shared `object Deps` constants: a module applying the
     *  convention id inherits its plugins, `android {}`, and `dependencies {}`, and `Deps.x` refs resolve. */
    @Test
    fun mergesConventionPluginAndConstants() {
        withTempDir("gradle-convention") { tmp ->
            val proj = tmp.resolve("Conv")
            fun w(rel: String, text: String) {
                val f = proj.resolve(rel); Files.createDirectories(f.parent); f.writeText(text.trimIndent())
            }
            w("settings.gradle.kts", """
                rootProject.name = "Conv"
                include(":feature")
            """)
            w("build-logic/convention/src/main/kotlin/Deps.kt", """
                object Deps {
                    const val okhttp = "com.squareup.okhttp3:okhttp:4.12.0"
                }
            """)
            w("build-logic/convention/src/main/kotlin/myapp.android.library.gradle.kts", """
                plugins {
                    id("com.android.library")
                    id("org.jetbrains.kotlin.android")
                }
                android {
                    compileSdk = 34
                    defaultConfig { minSdk = 21 }
                }
                dependencies {
                    implementation("androidx.core:core-ktx:1.12.0")
                    implementation(Deps.okhttp)
                }
            """)
            w("feature/build.gradle.kts", """
                plugins {
                    id("myapp.android.library")
                }
                android {
                    namespace = "com.example.feature"
                }
            """)
            w("feature/src/main/AndroidManifest.xml", """<manifest package="com.example.feature"/>""")

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            val feature = spec.modules.first { it.name == "feature" }
            assertEquals(GradleImport.Kind.ANDROID_LIB, feature.kind, "com.android.library from the convention")
            assertTrue(feature.isKotlin, "kotlin.android from the convention")
            assertEquals("com.example.feature", feature.namespace, "module's own namespace wins")
            assertEquals(34, feature.compileSdk, "compileSdk inherited from the convention")
            assertEquals(21, feature.minSdk)
            val coords = feature.mavenDeps.map { it.coordinate }.toSet()
            assertContains(coords, "androidx.core:core-ktx:1.12.0")
            assertContains(coords, "com.squareup.okhttp3:okhttp:4.12.0") // resolved via `object Deps` constant
        }
    }

    /** An imperative `Plugin<Project>` convention (registered via `gradlePlugin`) can't be read, so the Android
     *  kind is inferred from the module's manifest/res and a note is recorded rather than silently dropping it. */
    @Test
    fun infersAndroidKindWhenConventionUnreadable() {
        withTempDir("gradle-imperative") { tmp ->
            val proj = tmp.resolve("Imp")
            fun w(rel: String, text: String) {
                val f = proj.resolve(rel); Files.createDirectories(f.parent); f.writeText(text.trimIndent())
            }
            w("settings.gradle.kts", """
                rootProject.name = "Imp"
                include(":app")
            """)
            w("build-logic/convention/build.gradle.kts", """
                gradlePlugin {
                    plugins {
                        register("androidApp") {
                            id = "myapp.android.application"
                            implementationClass = "AndroidApplicationConventionPlugin"
                        }
                    }
                }
            """)
            w("app/build.gradle.kts", """
                plugins {
                    id("myapp.android.application")
                }
            """)
            w("app/src/main/AndroidManifest.xml", """
                <manifest package="com.example.imp">
                    <application>
                        <activity android:name=".Main">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """)

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            val app = spec.modules.first { it.name == "app" }
            assertEquals(GradleImport.Kind.ANDROID_APP, app.kind, "inferred from the manifest launcher activity")
            assertTrue(
                spec.report.notes.any { "myapp.android.application" in it || "convention plugin" in it },
                "the unreadable convention plugin is noted",
            )
        }
    }

    /** Custom Maven repositories from settings are captured to `.platform/repositories.txt`, and a re-sync
     *  merges (never clobbers) a repo the user added through the Repositories manager. */
    @Test
    fun capturesAndMergesSettingsRepositories() {
        withTempDir("gradle-repos") { tmp ->
            val legacyHome = tmp.resolve("legacy")
            val src = legacyHome.resolve("Repo")
            fun w(rel: String, text: String) {
                val f = src.resolve(rel); Files.createDirectories(f.parent); f.writeText(text.trimIndent())
            }
            w("settings.gradle.kts", """
                rootProject.name = "Repo"
                include(":app")
                dependencyResolutionManagement {
                    repositories {
                        google()
                        mavenCentral()
                        maven { url = uri("https://jitpack.io") }
                        maven("https://plugins.example.com/m2")
                    }
                }
            """)
            w("app/build.gradle.kts", """
                plugins { id("java-library") }
            """)
            w("app/src/main/java/com/example/App.java", "package com.example; class App {}")

            // Parser captures both custom repos (defaults skipped).
            val spec = GradleImport.parse(src)
            assertNotNull(spec)
            assertEquals(
                setOf("https://jitpack.io", "https://plugins.example.com/m2"),
                spec.customRepos.map { it.url }.toSet(),
            )

            val manager = ProjectManager.desktop(tmp.resolve("projects"), legacyDataDirs = listOf(legacyHome))
            assertEquals(1, manager.importLegacyProjects())
            val dest = Path.of(manager.list().first().rootPath)
            val reposFile = dest.resolve(".platform/repositories.txt")
            assertTrue(Files.exists(reposFile), "repositories.txt written at import")
            assertTrue(reposFile.readText().contains("https://jitpack.io"))

            // Simulate a user-added repo, then re-sync: both survive (merge, not clobber).
            reposFile.writeText(reposFile.readText() + "MyCorp\thttps://repo.mycorp.com/m2\n")
            manager.open(dest.toString()).use { ide -> assertTrue(runBlocking { ide.syncFromBuildFiles() }.ok) }
            val after = reposFile.readText()
            assertTrue(after.contains("https://repo.mycorp.com/m2"), "manually-added repo preserved across re-sync")
            assertTrue(after.contains("https://jitpack.io"))
        }
    }

    /** The richer `android {}` fields flow onto the ModuleSpec; an unmodeled feature is noted, not silent. */
    @Test
    fun parsesRicherAndroidConfig() {
        withTempDir("gradle-rich") { tmp ->
            val proj = tmp.resolve("Rich")
            fun w(rel: String, text: String) {
                val f = proj.resolve(rel); Files.createDirectories(f.parent); f.writeText(text.trimIndent())
            }
            w("settings.gradle.kts", "rootProject.name = \"Rich\"\ninclude(\":app\")")
            w("app/build.gradle.kts", """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                    id("kotlin-parcelize")
                }
                android {
                    namespace = "com.example.rich"
                    compileSdk = 34
                    defaultConfig {
                        minSdk = 24
                        versionCode = 7
                        versionName = "1.2.3"
                        buildConfigField("String", "API_URL", "\"https://x\"")
                    }
                    buildTypes {
                        release {
                            isMinifyEnabled = true
                            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                            applicationIdSuffix = ".prod"
                        }
                        debug {
                            isDebuggable = true
                            versionNameSuffix = "-dev"
                        }
                    }
                    buildFeatures {
                        viewBinding = true
                    }
                }
            """)
            w("app/src/main/AndroidManifest.xml", """<manifest package="com.example.rich"/>""")

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            val app = spec.modules.first { it.name == "app" }
            assertEquals(7, app.versionCode)
            assertEquals("1.2.3", app.versionName)
            assertTrue(app.viewBinding, "buildFeatures { viewBinding = true }")
            assertTrue(app.parcelize, "kotlin-parcelize plugin")
            val release = app.buildTypes.first { it.name == "release" }
            assertTrue(release.minifyEnabled)
            assertEquals(".prod", release.applicationIdSuffix)
            assertContains(release.proguardFiles, "proguard-android-optimize.txt")
            assertContains(release.proguardFiles, "proguard-rules.pro")
            val debug = app.buildTypes.first { it.name == "debug" }
            assertEquals(true, debug.debuggable)
            assertEquals("-dev", debug.versionNameSuffix)
            assertTrue(spec.report.notes.any { "buildConfigField" in it }, "unmodeled buildConfigField noted")
        }
    }

    /**
     * `manifestPlaceholders`, in every form the two DSLs write them, all the way to the encoded Android facet.
     *
     * Reported against 3.9.9: `com.github.myketstore:myket-billing-client`'s manifest names
     * `${marketPermission}`/`${marketApplicationId}`/`${marketBindAddress}`, and the setup its docs give is
     * `defaultConfig.manifestPlaceholders`. The reader used to skip the block with a note, so the values never
     * reached the manifest merge and aapt2 rejected the merged manifest of an otherwise-valid project.
     */
    @Test
    fun parsesManifestPlaceholders() {
        withTempDir("gradle-placeholders") { tmp ->
            val proj = tmp.resolve("Placeholders")
            fun w(rel: String, text: String) {
                val f = proj.resolve(rel); Files.createDirectories(f.parent); f.writeText(text.trimIndent())
            }
            w("settings.gradle", "rootProject.name = 'Placeholders'\ninclude ':app', ':kts'")
            // Groovy: the map literal, spread over lines, with a `$var` value and a build-type override.
            w("app/build.gradle", """
                apply plugin: 'com.android.application'
                ext.marketId = 'ir.mservices.market'
                android {
                    namespace 'com.example.app'
                    compileSdk 34
                    defaultConfig {
                        minSdk 24
                        manifestPlaceholders = [
                            marketApplicationId: "${'$'}{marketId}",
                            marketPermission   : 'ir.mservices.market.BILLING',
                            marketBindAddress  : "ir.mservices.market.InAppBillingService.BIND"
                        ]
                    }
                    buildTypes {
                        release {
                            manifestPlaceholders = [marketApplicationId: "ir.mservices.market.release"]
                        }
                    }
                }
            """)
            w("app/src/main/AndroidManifest.xml", """<manifest package="com.example.app"/>""")
            // Kotlin DSL: the per-key assignment and `+= mapOf(...)`, the forms modern KTS uses.
            w("kts/build.gradle.kts", """
                plugins { id("com.android.library") }
                android {
                    namespace = "com.example.kts"
                    compileSdk = 34
                    defaultConfig {
                        manifestPlaceholders["host"] = "example.com"
                        manifestPlaceholders += mapOf("scheme" to "https")
                    }
                }
            """)
            w("kts/src/main/AndroidManifest.xml", """<manifest package="com.example.kts"/>""")

            val spec = GradleImport.parse(proj)
            assertNotNull(spec)
            val app = spec.modules.first { it.name == "app" }
            assertEquals(
                mapOf(
                    "marketApplicationId" to "ir.mservices.market",
                    "marketPermission" to "ir.mservices.market.BILLING",
                    "marketBindAddress" to "ir.mservices.market.InAppBillingService.BIND",
                ),
                app.manifestPlaceholders,
                "defaultConfig placeholders (with \$var interpolated) must be read",
            )
            assertEquals(
                mapOf("marketApplicationId" to "ir.mservices.market.release"),
                app.buildTypes.first { it.name == "release" }.manifestPlaceholders,
                "a build type's own placeholders override defaultConfig's at build time",
            )
            assertEquals(
                mapOf("host" to "example.com", "scheme" to "https"),
                spec.modules.first { it.name == "kts" }.manifestPlaceholders,
                "the Kotlin DSL's per-key and += forms must be read too",
            )
            assertFalse(
                spec.report.notes.any { "manifestPlaceholders" in it },
                "nothing to warn about: they were all read (${spec.report.notes})",
            )

            // …and they cross into the model as the Android facet's own, which is what reaches the merge.
            val outcome = runBlocking {
                GradleProjectImporter().resolve(SyncRequest(proj, NoProgress, SyncReason.IMPORT))
            }
            val facet = outcome.model!!.modules.first { it.name == "app" }.facets.first { it.table == "android" }
            assertEquals(
                listOf(
                    "marketApplicationId=ir.mservices.market",
                    "marketPermission=ir.mservices.market.BILLING",
                    "marketBindAddress=ir.mservices.market.InAppBillingService.BIND",
                ),
                facet.values["manifestPlaceholders"],
                "the facet carries them as `key=value` entries",
            )
        }
    }

    private object NoProgress : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled = false
    }

    /** Convert moves the Gradle files to a backup dir, drops the compat marker, and keeps the native model;
     *  revert restores them and re-enters compatibility mode. */
    @Test
    fun convertToNativeAndRevert() {
        withTempDir("gradle-convert") { tmp ->
            val legacyHome = tmp.resolve("legacy")
            writeLegacyGradleProject(legacyHome.resolve("MyApp"))
            val manager = ProjectManager.desktop(tmp.resolve("projects"), legacyDataDirs = listOf(legacyHome))
            assertEquals(1, manager.importLegacyProjects())
            val dest = Path.of(manager.list().first().rootPath)

            manager.open(dest.toString()).use { ide ->
                assertTrue(ide.isCompatibilityMode())
                val outcome = ide.convertToNative()
                assertTrue(outcome.ok, outcome.message)
                assertTrue(outcome.canRevert)
                assertFalse(ide.isCompatibilityMode(), "marker dropped after convert")
            }

            // Scripts moved to the backup; the module + workspace model is intact.
            assertFalse(Files.exists(dest.resolve("settings.gradle")), "root settings moved out")
            assertFalse(Files.exists(dest.resolve("app/build.gradle")), "module script moved out")
            assertTrue(Files.exists(dest.resolve(".platform/gradle-backup/settings.gradle")), "backed up")
            assertTrue(Files.exists(dest.resolve(".platform/gradle-backup/app/build.gradle")), "backed up (nested)")
            assertTrue(Files.exists(dest.resolve(".platform/workspace.json")), "native model kept")
            assertTrue(Files.exists(dest.resolve("app/module.toml")), "module.toml kept")
            assertTrue(
                Files.exists(dest.resolve("app/src/main/java/com/example/myapp/MainActivity.java")),
                "sources untouched",
            )
            assertFalse(GradleImport.isGradleProject(dest), "no longer looks like a Gradle project")

            // It still opens as a native project.
            manager.open(dest.toString()).use { ide ->
                assertEquals(setOf("app", "core"), ide.moduleNames().toSet())
                // Revert restores the scripts and compatibility mode.
                assertTrue(ide.revertToGradle().ok)
                assertTrue(ide.isCompatibilityMode(), "compatibility mode restored")
            }
            assertTrue(Files.exists(dest.resolve("settings.gradle")), "root settings restored")
            assertTrue(Files.exists(dest.resolve("app/build.gradle")), "module script restored")
            assertFalse(Files.exists(dest.resolve(".platform/gradle-backup")), "backup dir removed after revert")
        }
    }
}
