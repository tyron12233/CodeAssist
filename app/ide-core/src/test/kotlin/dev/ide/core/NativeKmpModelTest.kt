package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.build.jvm.buildconfig.KotlinBuildConfigFacet
import dev.ide.build.jvm.compose.ComposeResourcesFacet
import dev.ide.core.gradle.GradleKmp
import dev.ide.core.project.ProjectManager
import dev.ide.lang.kotlin.build.KotlinFacet
import dev.ide.model.LibraryDependency
import dev.ide.model.template.TemplateArgs
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin Multiplatform in the native model: a module that records its Android and desktop targets and is shaped for
 * one, created by the template (Gradle off), by New Module, or by Module Settings' Add feature, and switched between
 * targets with no build scripts involved.
 */
class NativeKmpModelTest {

    private fun IdeServices.module(name: String) = modules().single { it.name == name }
    private fun IdeServices.roots(name: String) = module(name).sourceSets.single { it.name == "main" }.contentRoots
        .map { it.dir.path.replace('\\', '/').substringAfter("$name/") }
    private fun IdeServices.libs(name: String) = module(name).dependencies.filterIsInstance<LibraryDependency>().map { it.library.name }

    @Test
    fun theTemplateWithGradleOffCreatesANativeSwitchableProject() {
        withTempDir("kmp-native-template") { tmp ->
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                val ide = manager.create(
                    "kmp-compose-app",
                    mapOf(TemplateArgs.NAME to "My App", TemplateArgs.PACKAGE to "com.example.myapp", "gradle" to "false"),
                )
                ide.use {
                    assertFalse(it.isCompatibilityMode(), "a native project")
                    assertFalse(Files.exists(it.workspaceRoot.resolve("settings.gradle.kts")), "no Gradle files")
                    assertTrue(Files.exists(it.workspaceRoot.resolve("composeApp/src/commonMain/kotlin/com/example/myapp/App.kt")))
                    // The desktop host shapes it for the JVM; both targets are recorded.
                    assertEquals("java-lib", it.module("composeApp").type.id)
                    val facet = assertNotNull(it.module("composeApp").facets.get(KotlinFacet.KEY))
                    assertEquals(setOf("android", "jvm"), facet.targets.map { t -> t.name }.toSet())
                    assertEquals("jvm", facet.activeTarget)
                    assertTrue("src/desktopMain/kotlin" in it.roots("composeApp"), "${it.roots("composeApp")}")
                    assertTrue(it.libs("composeApp").any { l -> l.startsWith("org.jetbrains.compose.desktop:desktop-jvm-") }, "${it.libs("composeApp")}")

                    val toAndroid = runBlocking { it.switchKmpTarget(GradleKmp.Platform.ANDROID) }
                    assertTrue(toAndroid.ok && toAndroid.modelChanged, toAndroid.message)
                    assertEquals("android-app", it.module("composeApp").type.id)
                    assertEquals("com.example.myapp", it.module("composeApp").facets.get(AndroidFacet.KEY)?.namespace)
                    assertTrue("src/androidMain/kotlin" in it.roots("composeApp") && "src/desktopMain/kotlin" !in it.roots("composeApp"))
                    assertTrue(it.libs("composeApp").any { l -> l.startsWith("androidx.activity:activity-compose:") })
                    assertTrue(it.libs("composeApp").none { l -> "desktop-jvm" in l })

                    // Saved: a reopened project is still on Android.
                    val toml = Files.readString(it.workspaceRoot.resolve("composeApp/module.toml"))
                    assertTrue("activeTarget = \"android\"" in toml, toml)
                }
            } finally {
                manager.dispose()
            }
        }
    }

    @Test
    fun moduleSettingsAddsFeaturesToANativeModule() {
        withTempDir("kmp-native-features") { tmp ->
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                val ide = manager.create("kotlin-console", mapOf(TemplateArgs.NAME to "Console"))
                ide.use {
                    val modules = it.moduleService
                    val config = assertNotNull(modules.getModuleConfig("app"))
                    assertEquals(listOf("compose-resources", "kotlin-build-config", "multiplatform"), config.addableFeatures.map { f -> f.id })

                    assertTrue(modules.addModuleFeature("app", "compose-resources").success)
                    val res = assertNotNull(it.module("app").facets.get(ComposeResourcesFacet.KEY))
                    assertEquals(listOf("src/main/composeResources"), res.roots)
                    assertTrue(it.libs("app").any { l -> l.startsWith("org.jetbrains.compose.components:components-resources:") })
                    assertTrue(Files.isDirectory(it.workspaceRoot.resolve("app/src/main/composeResources")))

                    assertTrue(modules.addModuleFeature("app", "kotlin-build-config").success)
                    assertNotNull(it.module("app").facets.get(KotlinBuildConfigFacet.KEY))

                    assertTrue(modules.addModuleFeature("app", "multiplatform").success)
                    val kmp = assertNotNull(it.module("app").facets.get(KotlinFacet.KEY))
                    assertEquals(setOf("android", "jvm"), kmp.targets.map { t -> t.name }.toSet())
                    assertEquals("jvm", kmp.activeTarget, "a JVM module is its desktop target")
                    assertTrue("src/main/kotlin" in it.roots("app"), "the module's own code stays: ${it.roots("app")}")
                    assertTrue("src/commonMain/kotlin" in it.roots("app"))
                    assertTrue(kmp.commonSourceRoots.containsAll(listOf("src/commonMain/kotlin", ComposeResourcesFacet.GENERATED_KOTLIN_ROOT)), "${kmp.commonSourceRoots}")
                    assertEquals(emptyList(), assertNotNull(modules.getModuleConfig("app")).addableFeatures, "nothing left to add")
                    assertEquals(listOf("android", "jvm"), assertNotNull(modules.getModuleConfig("app")).kmpTargets.sorted())
                }
            } finally {
                manager.dispose()
            }
        }
    }

    @Test
    fun newModuleCreatesAMultiplatformModule() {
        withTempDir("kmp-native-new-module") { tmp ->
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                val ide = manager.create("kotlin-console", mapOf(TemplateArgs.NAME to "Console"))
                ide.use {
                    assertTrue(it.moduleService.availableModuleTypes().any { t -> t.id == "kotlin-multiplatform" })
                    val r = it.moduleService.createModule(
                        "shared", "kotlin-multiplatform", null,
                        mapOf("kotlin-multiplatform" to mapOf("namespace" to "com.example.shared", "application" to false, "compose" to true)),
                    )
                    assertTrue(r.success, r.message)
                    assertEquals("java-lib", it.module("shared").type.id)
                    assertEquals(setOf("android", "jvm"), it.module("shared").facets.get(KotlinFacet.KEY)?.targets?.map { t -> t.name }?.toSet())
                    assertTrue(Files.isDirectory(it.workspaceRoot.resolve("shared/src/commonMain/kotlin/com/example/shared")))
                    assertNull(it.module("shared").facets.get(AndroidFacet.KEY), "shaped for the desktop host")
                }
            } finally {
                manager.dispose()
            }
        }
    }
}
