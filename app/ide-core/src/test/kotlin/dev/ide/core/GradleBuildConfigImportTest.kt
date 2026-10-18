package dev.ide.core

import dev.ide.core.gradle.GradleImport
import dev.ide.core.gradle.GradleImport.BuildConfigFieldSpec
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `buildConfigField`s and `buildFeatures.buildConfig` read from both DSLs, at every level AGP takes them. */
class GradleBuildConfigImportTest {

    private fun Path.w(rel: String, text: String) {
        val f = resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text.trimIndent())
    }

    @Test
    fun readsFieldsFromDefaultConfigBuildTypesAndFlavors() {
        withTempDir("buildconfig-kts") { root ->
            root.w("settings.gradle.kts", "include(\":app\")")
            root.w("app/build.gradle.kts", """
                plugins { id("com.android.application") }
                val apiHost = "api.example.com"
                android {
                    namespace = "com.example.app"
                    compileSdk = 36
                    defaultConfig {
                        minSdk = 24
                        buildConfigField("String", "API_URL", "\"https://${'$'}apiHost\"")
                        buildConfigField("int", "RETRIES", "3")
                    }
                    buildTypes {
                        release {
                            buildConfigField("boolean", "LOGGING", "false")
                        }
                    }
                    flavorDimensions += "tier"
                    productFlavors {
                        create("free") {
                            dimension = "tier"
                            buildConfigField("String", "API_URL", "\"https://free.example.com\"")
                        }
                    }
                    buildFeatures { buildConfig = true }
                }
            """)
            val app = assertNotNull(GradleImport.parse(root)).modules.single { it.name == "app" }
            assertEquals(
                listOf(
                    BuildConfigFieldSpec("String", "API_URL", "\"https://api.example.com\""),
                    BuildConfigFieldSpec("int", "RETRIES", "3"),
                ),
                app.buildConfigFields,
                "Kotlin escapes are undone and \$vars interpolated",
            )
            assertEquals(listOf(BuildConfigFieldSpec("boolean", "LOGGING", "false")), app.buildTypes.single { it.name == "release" }.buildConfigFields)
            assertEquals("\"https://free.example.com\"", app.productFlavors.single().buildConfigFields.single().value)
            assertEquals(true, app.buildConfig)
        }
    }

    @Test
    fun readsGroovyFields() {
        val fields = GradleImport.parseBuildConfigFields(
            """
            minSdk 24
            buildConfigField "String", "API_URL", '"https://api.example.com"'
            buildConfigField 'long', 'TIMEOUT', '30L'
            """.trimIndent()
        )
        assertEquals(
            listOf(
                BuildConfigFieldSpec("String", "API_URL", "\"https://api.example.com\""),
                BuildConfigFieldSpec("long", "TIMEOUT", "30L"),
            ),
            fields,
        )
    }

    /** gmazzo's `com.github.gmazzo.buildconfig` plugin on a KMP module, in each `buildConfigField` form it takes. */
    @Test
    fun readsTheGmazzoPluginsBuildConfig() {
        withTempDir("buildconfig-gmazzo") { root ->
            root.w("settings.gradle.kts", "include(\":sharedUI\")")
            root.w("sharedUI/build.gradle.kts", """
                plugins {
                    kotlin("multiplatform")
                    id("com.android.kotlin.multiplatform.library")
                    id("com.github.gmazzo.buildconfig") version "6.1.2"
                }
                kotlin {
                    android { namespace = "org.company.app" }
                    jvm()
                }
                buildConfig {
                    className("AppConfig")
                    useKotlinOutput { internalVisibility = false }
                    buildConfigField("String", "API_URL", "\"https://api.example.com\"")
                    buildConfigField("boolean", "LOGGING", "true")
                    buildConfigField<String>("APP_NAME", "Demo")
                    buildConfigField("RETRIES", 3)
                    buildConfigField("TIMEOUT", 30L)
                    buildConfigField("VERSION", provider { project.version.toString() })
                }
            """)
            val spec = assertNotNull(GradleImport.parse(root))
            val bc = assertNotNull(spec.modules.single { it.name == "sharedUI" }.kotlinBuildConfig)
            assertEquals("org.company.app", bc.packageName, "no packageName: the Android namespace")
            assertEquals("AppConfig", bc.className)
            assertEquals(false, bc.internalVisibility)
            assertEquals(
                listOf(
                    BuildConfigFieldSpec("String", "API_URL", "\"https://api.example.com\""),
                    BuildConfigFieldSpec("Boolean", "LOGGING", "true"),
                    BuildConfigFieldSpec("String", "APP_NAME", "\"Demo\""),
                    BuildConfigFieldSpec("Int", "RETRIES", "3"),
                    BuildConfigFieldSpec("Long", "TIMEOUT", "30L"),
                ),
                bc.fields,
            )
            assertTrue(spec.report.notes.any { "computed" in it }, "the provider { } field is reported: ${spec.report.notes}")
            assertTrue(spec.report.notes.none { "does not generate" in it && "BuildConfig" in it }, "${spec.report.notes}")
        }
    }
}
