package dev.ide.android.support.templates

import dev.ide.android.support.AndroidFacet
import dev.ide.model.BuildSystemId
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/** Shared helpers for the built-in Android templates. */
internal object AndroidTemplateSupport {
    fun pkgPath(pkg: String): String = pkg.replace('.', '/')

    /** The minSdk picker offered by both Android templates. */
    val minSdkParam = TemplateParameter.Choice(
        key = "minSdk",
        label = "Minimum SDK",
        options = listOf(
            TemplateParameter.Choice.Option("21", "API 21 · Android 5.0"),
            TemplateParameter.Choice.Option("24", "API 24 · Android 7.0"),
            TemplateParameter.Choice.Option("26", "API 26 · Android 8.0"),
            TemplateParameter.Choice.Option("34", "API 34 · Android 14"),
        ),
        defaultIndex = 1,
        help = "Lowest Android version the app supports.",
    )

    const val COMPILE_SDK = 34
}

/**
 * A native Android application: one `app` module (android-app) with an `AndroidFacet`, an editable
 * `AndroidManifest.xml`, `res/` (strings, colors, theme), and a `MainActivity`. Assembles to a signed
 * APK through the existing `AndroidBuildSystem` pipeline.
 */
object AndroidAppTemplate : ProjectTemplate {
    override val id = TemplateId("android-app")
    override val displayName = "Android App"
    override val description = "A native Android application that builds to an installable APK."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(AndroidTemplateSupport.minSdkParam)

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 24)
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            // Android module types supply their own (main/debug/release) source sets, so no addSourceSet here.
            addModule("app", scaffold.moduleType("android-app")).apply {
                languageLevel = scaffold.languageLevel
                putFacet(
                    AndroidFacet(
                        namespace = pkg,
                        compileSdk = AndroidTemplateSupport.COMPILE_SDK,
                        minSdk = minSdk,
                        targetSdk = AndroidTemplateSupport.COMPILE_SDK,
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
                <application android:label="@string/app_name" android:theme="@style/Theme.App">
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
                <string name="greeting">Hello, Android</string>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/colors.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="primary">#FF6200EE</color>
                <color name="on_primary">#FFFFFFFF</color>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/styles.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="android:Theme.Material.Light">
                    <item name="android:colorPrimary">@color/primary</item>
                </style>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/java/$path/MainActivity.java",
            """
            package $pkg;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView tv = new TextView(this);
                    tv.setText(getString(R.string.greeting));
                    setContentView(tv);
                }
            }
            """,
        )
    }
}

/**
 * A native Android library: one `lib` module (android-lib) with an `AndroidFacet(isApplication=false)`,
 * its own `res/` (merged into a consuming app's R), and a sample class referencing its own `R`.
 */
object AndroidLibraryTemplate : ProjectTemplate {
    override val id = TemplateId("android-library")
    override val displayName = "Android Library"
    override val description = "A reusable Android library module (AAR) with its own resources."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(AndroidTemplateSupport.minSdkParam)

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 24)
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule("lib", scaffold.moduleType("android-lib")).apply {
                languageLevel = scaffold.languageLevel
                putFacet(
                    AndroidFacet(
                        namespace = pkg,
                        compileSdk = AndroidTemplateSupport.COMPILE_SDK,
                        minSdk = minSdk,
                        isApplication = false,
                    ),
                )
            }
            commit()
        }

        val path = AndroidTemplateSupport.pkgPath(pkg)
        scaffold.writeText(
            "lib/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg" />
            """,
        )
        scaffold.writeText(
            "lib/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="lib_title">${args.name}</string>
            </resources>
            """,
        )
        scaffold.writeText(
            "lib/src/main/java/$path/LibraryText.java",
            """
            package $pkg;

            /** Library code resolving its OWN R (merged into a consuming app's R). */
            public final class LibraryText {
                public static int titleRes() { return R.string.lib_title; }
            }
            """,
        )
    }
}
