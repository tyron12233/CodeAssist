package dev.ide.android.support.templates

import dev.ide.android.support.AndroidFacet
import dev.ide.model.BuildSystemId
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateDependency
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.platform.log.Log

/**
 * A Jetpack Compose application: one `app` module (android-app) whose UI is built in Compose, with
 * `@Composable` functions and `@Preview`s that the editor can render through the on-device Compose
 * interpreter (see `docs/compose-interpreter.md`). Kotlin-only; Compose requires minSdk 21.
 *
 * The starter screen is a `Greeting` composable shown via `setContent`, plus two `@Preview` composables — a
 * single `Text` and a `Column` of `Text`s — to showcase the editor Preview button on both a leaf and a
 * nested (content-lambda) composable.
 */
object JetpackComposeAppTemplate : ProjectTemplate {
    override val id = TemplateId("compose-app")
    override val displayName = "Jetpack Compose App"
    override val description = "An Android app with a Jetpack Compose UI and @Preview composables you can render in the editor."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    private val log = Log.logger("Jetpack Compose Template Generator")

    override fun parameters(): List<TemplateParameter> = listOf(
        // Compose requires minSdk 21+; drop the lower options.
        AndroidTemplateSupport.minSdkParam.copy(
            options = AndroidTemplateSupport.minSdkParam.options.filter { it.value.toInt() >= 21 },
            defaultIndex = 0,
        ),
        AndroidTemplateSupport.targetSdkParam,
    )

    override fun dependencies(args: TemplateArgs): List<TemplateDependency> = listOf(
        TemplateDependency("app", "androidx.activity:activity-compose:$ACTIVITY_COMPOSE"),
        TemplateDependency("app", "androidx.compose.ui:ui:$COMPOSE"),
        TemplateDependency("app", "androidx.compose.foundation:foundation:$COMPOSE"),
        TemplateDependency("app", "androidx.compose.material3:material3:$MATERIAL3"),
        TemplateDependency("app", "androidx.compose.ui:ui-tooling-preview:$COMPOSE"),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 21)
        val targetSdk = args.int("targetSdk", AndroidTemplateSupport.COMPILE_SDK)
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule("app", scaffold.moduleType("android-app")).apply {
                languageLevel = scaffold.languageLevel
                putFacet(
                    AndroidFacet(
                        namespace = pkg,
                        compileSdk = AndroidTemplateSupport.COMPILE_SDK,
                        minSdk = minSdk,
                        targetSdk = targetSdk,
                    ),
                )
            }
            commit()
        }

        val path = AndroidTemplateSupport.pkgPath(pkg)
        scaffold.writeText(
            "app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:label="@string/app_name"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.App">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${args.name}</string>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/colors.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                ${AndroidAppAssets.ICON_BACKGROUND_COLOR_XML}
            </resources>
            """,
        )
        // A NoActionBar framework theme — Compose handles its own theming, so no Material XML theme is needed.
        scaffold.writeText(
            "app/src/main/res/values/themes.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="android:Theme.Material.Light.NoActionBar"/>
            </resources>
            """,
        )
        for ((rel, content) in AndroidAppAssets.launcherIconResFiles) {
            scaffold.writeText("app/src/main/res/$rel", content)
        }
        scaffold.writeText(
            "app/src/main/kotlin/$path/MainActivity.kt",
            """
            package $pkg

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { Greeting("World") }
                }
            }

            @Composable
            fun Greeting(name: String) {
                Text(text = "Hello, " + name + "!")
            }

            // Press the Preview button in the editor toolbar to render these through the Compose interpreter.
            @Preview
            @Composable
            fun GreetingPreview() {
                Greeting("Compose")
            }

            @Preview
            @Composable
            fun CardPreview() {
                Column {
                    Text("Title")
                    Text("Body")
                }
            }
            """,
        )
    }

    private const val COMPOSE = "1.7.5"
    private const val MATERIAL3 = "1.3.1"
    private const val ACTIVITY_COMPOSE = "1.9.3"
}
