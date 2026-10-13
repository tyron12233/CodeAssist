package dev.ide.core.templates

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.templates.TemplateSupport

/**
 * Create-Project templates for Kotlin Multiplatform.
 *
 * A multiplatform module is several compilations and a CodeAssist module is one, so these do not author a model.
 * They write the Gradle project the Kotlin Multiplatform wizard writes (version catalog, `kotlin { }` targets,
 * `commonMain` + platform source sets, `expect`/`actual`), and the Gradle importer reads it in: one target per
 * module, switchable between Android and the desktop JVM from the compatibility banner (see `GradleKmp`). The
 * project therefore also opens unchanged in Android Studio or Fleet.
 *
 * Only Android and the JVM build here. iOS is an opt-in source set for taking the project to a Mac with Xcode.
 */
internal object KmpTemplateSupport {

    const val IOS = "ios"

    val parameters: List<TemplateParameter> = listOf(
        TemplateParameter.Toggle(
            key = IOS,
            label = "Include iOS",
            default = false,
            help = "Adds the iOS targets and an iosMain source set, to build with Xcode on a Mac. " +
                "CodeAssist builds the Android and desktop targets.",
        ),
    )

    /** The root of the Gradle build: settings, the root script, properties, the catalog, and a .gitignore. */
    fun writeRoot(scaffold: ProjectScaffold, args: TemplateArgs, module: String, rootPlugins: List<String>) {
        scaffold.writeText(
            "settings.gradle.kts",
            """
            rootProject.name = "${kotlinString(args.name)}"
            enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }

            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }

            include(":$module")
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            "build.gradle.kts",
            "plugins {\n" + rootPlugins.joinToString("") { "    alias(libs.plugins.$it) apply false\n" } + "}\n",
        )
        scaffold.writeText(
            "gradle.properties",
            """
            kotlin.code.style=official
            org.gradle.jvmargs=-Xmx4g
            android.useAndroidX=true
            android.nonTransitiveRClass=true
            """.trimIndent() + "\n",
        )
        scaffold.writeText("gradle/libs.versions.toml", CATALOG)
        scaffold.writeText(".gitignore", "*.iml\n.gradle\n.idea\n.kotlin\nbuild/\nlocal.properties\n.DS_Store\n")
    }

    /** The iOS targets inside `kotlin { }` (indented for it, after a blank line), or nothing without iOS. */
    fun iosTargets(ios: Boolean, framework: String): String = if (!ios) "" else "\n\n" + """
        listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "$framework"
                isStatic = true
            }
        }
    """.trimIndent().prependIndent("    ")

    fun src(module: String, set: String, pkg: String, file: String) =
        "$module/src/$set/kotlin/${TemplateSupport.pkgPath(pkg)}/$file"

    private fun kotlinString(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    /**
     * Versions the IDE resolves and builds against. Compose Multiplatform is the version the IDE itself ships
     * (`libs.versions.toml` `compose`): a desktop Run executes `androidx.compose` as the IDE's own copy rather
     * than the project's, so a project on another version compiles against one API and runs on a different one.
     */
    private val CATALOG = """
        [versions]
        agp = "8.7.3"
        android-compileSdk = "35"
        android-minSdk = "24"
        android-targetSdk = "35"
        androidx-activityCompose = "1.10.1"
        composeMultiplatform = "1.11.1"
        kotlin = "2.2.20"
        kotlinx-coroutines = "1.10.2"

        [libraries]
        kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
        androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activityCompose" }
        kotlinx-coroutines-swing = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "kotlinx-coroutines" }

        [plugins]
        androidApplication = { id = "com.android.application", version.ref = "agp" }
        androidLibrary = { id = "com.android.library", version.ref = "agp" }
        composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
        composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
        kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
    """.trimIndent() + "\n"
}

/**
 * One Compose UI shared by an Android app and a desktop app: `App()` in `commonMain`, an `expect`/`actual`
 * greeting, the Android activity, and the desktop `main()`.
 */
object KmpComposeAppTemplate : ProjectTemplate {
    override val id = TemplateId("kmp-compose-app")
    override val displayName = "Compose Multiplatform App"
    override val description = "One Compose UI for Android and desktop, in a Kotlin Multiplatform Gradle project."
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = KmpTemplateSupport.parameters

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val ios = args.bool(KmpTemplateSupport.IOS)
        val m = "composeApp"
        KmpTemplateSupport.writeRoot(
            scaffold, args, m,
            listOf("androidApplication", "composeMultiplatform", "composeCompiler", "kotlinMultiplatform"),
        )
        scaffold.writeText(
            "$m/build.gradle.kts",
            """
            |plugins {
            |    alias(libs.plugins.kotlinMultiplatform)
            |    alias(libs.plugins.androidApplication)
            |    alias(libs.plugins.composeMultiplatform)
            |    alias(libs.plugins.composeCompiler)
            |}
            |
            |kotlin {
            |    androidTarget()
            |    jvm("desktop")${KmpTemplateSupport.iosTargets(ios, "ComposeApp")}
            |
            |    sourceSets {
            |        val desktopMain by getting
            |
            |        commonMain.dependencies {
            |            implementation(compose.runtime)
            |            implementation(compose.foundation)
            |            implementation(compose.material3)
            |            implementation(compose.ui)
            |            implementation(compose.components.uiToolingPreview)
            |        }
            |        commonTest.dependencies {
            |            implementation(libs.kotlin.test)
            |        }
            |        androidMain.dependencies {
            |            implementation(compose.preview)
            |            implementation(libs.androidx.activity.compose)
            |        }
            |        desktopMain.dependencies {
            |            implementation(compose.desktop.currentOs)
            |            implementation(libs.kotlinx.coroutines.swing)
            |        }
            |    }
            |}
            |
            |android {
            |    namespace = "$pkg"
            |    compileSdk = libs.versions.android.compileSdk.get().toInt()
            |
            |    defaultConfig {
            |        applicationId = "$pkg"
            |        minSdk = libs.versions.android.minSdk.get().toInt()
            |        targetSdk = libs.versions.android.targetSdk.get().toInt()
            |        versionCode = 1
            |        versionName = "1.0"
            |    }
            |}
            |
            |compose.desktop {
            |    application {
            |        mainClass = "$pkg.MainKt"
            |    }
            |}
            """.trimMargin() + "\n",
        )

        scaffold.writeText(
            KmpTemplateSupport.src(m, "commonMain", pkg, "Platform.kt"),
            """
            package $pkg

            /** The platform this build runs on; each target supplies its own `actual`. */
            expect fun platformName(): String

            fun greeting(): String = "Hello from ${'$'}{platformName()}!"
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "commonMain", pkg, "App.kt"),
            """
            package $pkg

            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.Spacer
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.foundation.layout.height
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Button
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp
            import org.jetbrains.compose.ui.tooling.preview.Preview

            /** The UI every target shows: written once, in commonMain. */
            @Composable
            fun App() {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        var clicks by remember { mutableStateOf(0) }
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(greeting(), style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { clicks++ }) {
                                Text(if (clicks == 0) "Click me" else "Clicked ${'$'}clicks times")
                            }
                        }
                    }
                }
            }

            /** Shows [App] in the preview pane, on whichever target the project is imported for. */
            @Preview
            @Composable
            fun AppPreview() {
                App()
            }
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "commonTest", pkg, "GreetingTest.kt"),
            """
            package $pkg

            import kotlin.test.Test
            import kotlin.test.assertTrue

            class GreetingTest {
                @Test
                fun greetsFromThePlatform() {
                    assertTrue(greeting().startsWith("Hello from "))
                }
            }
            """.trimIndent() + "\n",
        )

        // Android
        scaffold.writeText(
            KmpTemplateSupport.src(m, "androidMain", pkg, "Platform.android.kt"),
            """
            package $pkg

            import android.os.Build

            actual fun platformName(): String = "Android ${'$'}{Build.VERSION.SDK_INT}"
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "androidMain", pkg, "MainActivity.kt"),
            """
            package $pkg

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { App() }
                }
            }
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            "$m/src/androidMain/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="@string/app_name"
                    android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            "$m/src/androidMain/res/values/strings.xml",
            """
            <resources>
                <string name="app_name">${xmlText(args.name)}</string>
            </resources>
            """.trimIndent() + "\n",
        )

        // Desktop
        scaffold.writeText(
            KmpTemplateSupport.src(m, "desktopMain", pkg, "Platform.desktop.kt"),
            """
            package $pkg

            actual fun platformName(): String = "Java ${'$'}{System.getProperty("java.version")}"
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "desktopMain", pkg, "main.kt"),
            """
            package $pkg

            import androidx.compose.ui.window.Window
            import androidx.compose.ui.window.application

            fun main() = application {
                Window(onCloseRequest = ::exitApplication, title = "${kotlinText(args.name)}") {
                    App()
                }
            }
            """.trimIndent() + "\n",
        )

        if (ios) {
            scaffold.writeText(
                KmpTemplateSupport.src(m, "iosMain", pkg, "Platform.ios.kt"),
                """
                package $pkg

                import platform.UIKit.UIDevice

                actual fun platformName(): String =
                    UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
                """.trimIndent() + "\n",
            )
            scaffold.writeText(
                KmpTemplateSupport.src(m, "iosMain", pkg, "MainViewController.kt"),
                """
                package $pkg

                import androidx.compose.ui.window.ComposeUIViewController

                fun MainViewController() = ComposeUIViewController { App() }
                """.trimIndent() + "\n",
            )
        }
    }
}

/**
 * A library shared by Android and the JVM: common code with an `expect`/`actual` pair, a common test, and the
 * two platform implementations.
 */
object KmpLibraryTemplate : ProjectTemplate {
    override val id = TemplateId("kmp-library")
    override val displayName = "Kotlin Multiplatform Library"
    override val description = "Shared Kotlin for Android and the JVM, with expect/actual and common tests."
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = KmpTemplateSupport.parameters

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val ios = args.bool(KmpTemplateSupport.IOS)
        val m = "shared"
        KmpTemplateSupport.writeRoot(scaffold, args, m, listOf("androidLibrary", "kotlinMultiplatform"))
        scaffold.writeText(
            "$m/build.gradle.kts",
            """
            |plugins {
            |    alias(libs.plugins.kotlinMultiplatform)
            |    alias(libs.plugins.androidLibrary)
            |}
            |
            |kotlin {
            |    androidTarget()
            |    jvm()${KmpTemplateSupport.iosTargets(ios, "Shared")}
            |
            |    sourceSets {
            |        commonTest.dependencies {
            |            implementation(libs.kotlin.test)
            |        }
            |    }
            |}
            |
            |android {
            |    namespace = "$pkg"
            |    compileSdk = libs.versions.android.compileSdk.get().toInt()
            |
            |    defaultConfig {
            |        minSdk = libs.versions.android.minSdk.get().toInt()
            |    }
            |}
            """.trimMargin() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "commonMain", pkg, "Platform.kt"),
            """
            package $pkg

            /** The platform this build runs on; each target supplies its own `actual`. */
            expect fun platformName(): String
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "commonMain", pkg, "Greeting.kt"),
            """
            package $pkg

            /** Shared logic: written once, used by every target. */
            class Greeting(private val name: String = "world") {
                fun greet(): String = "Hello, ${'$'}name, from ${'$'}{platformName()}!"
            }
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "commonTest", pkg, "GreetingTest.kt"),
            """
            package $pkg

            import kotlin.test.Test
            import kotlin.test.assertTrue

            class GreetingTest {
                @Test
                fun greetsByName() {
                    assertTrue(Greeting("Kotlin").greet().startsWith("Hello, Kotlin, from "))
                }
            }
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "androidMain", pkg, "Platform.android.kt"),
            """
            package $pkg

            import android.os.Build

            actual fun platformName(): String = "Android ${'$'}{Build.VERSION.SDK_INT}"
            """.trimIndent() + "\n",
        )
        scaffold.writeText(
            KmpTemplateSupport.src(m, "jvmMain", pkg, "Platform.jvm.kt"),
            """
            package $pkg

            actual fun platformName(): String = "Java ${'$'}{System.getProperty("java.version")}"
            """.trimIndent() + "\n",
        )
        if (ios) {
            scaffold.writeText(
                KmpTemplateSupport.src(m, "iosMain", pkg, "Platform.ios.kt"),
                """
                package $pkg

                import platform.UIKit.UIDevice

                actual fun platformName(): String =
                    UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
                """.trimIndent() + "\n",
            )
        }
    }
}

private fun xmlText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun kotlinText(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
