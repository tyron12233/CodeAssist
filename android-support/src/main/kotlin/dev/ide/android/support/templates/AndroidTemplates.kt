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

/** Shared helpers for the built-in Android templates. */
internal object AndroidTemplateSupport {
    fun pkgPath(pkg: String): String = pkg.replace('.', '/')

    /** The minSdk picker offered by both Android templates. */
    val minSdkParam = TemplateParameter.Choice(
        key = "minSdk",
        label = "Minimum SDK",
        options = listOf(
            TemplateParameter.Choice.Option("21", "API 21 · Android 5.0"),
            TemplateParameter.Choice.Option("23", "API 23 · Android 6.0"),
            TemplateParameter.Choice.Option("24", "API 24 · Android 7.0"),
            TemplateParameter.Choice.Option("26", "API 26 · Android 8.0"),
            TemplateParameter.Choice.Option("28", "API 28 · Android 9.0"),
            TemplateParameter.Choice.Option("30", "API 30 · Android 11"),
            TemplateParameter.Choice.Option("33", "API 33 · Android 13"),
            TemplateParameter.Choice.Option("34", "API 34 · Android 14"),
        ),
        defaultIndex = 2,
        help = "Lowest Android version the app supports.",
    )

    /** The targetSdk picker — the API level the app is tested/optimised against. */
    val targetSdkParam = TemplateParameter.Choice(
        key = "targetSdk",
        label = "Target SDK",
        options = listOf(
            TemplateParameter.Choice.Option("30", "API 30 · Android 11"),
            TemplateParameter.Choice.Option("33", "API 33 · Android 13"),
            TemplateParameter.Choice.Option("34", "API 34 · Android 14"),
        ),
        defaultIndex = 2,
        help = "The API level the app is built and optimised against.",
    )

    /** Source language for the generated starter code. */
    val languageParam = TemplateParameter.Choice(
        key = "language",
        label = "Language",
        options = listOf(
            TemplateParameter.Choice.Option("java", "Java"),
            TemplateParameter.Choice.Option("kotlin", "Kotlin"),
        ),
        defaultIndex = 0,
        help = "Language of the starter source files.",
    )

    const val COMPILE_SDK = 34

    /** Google's Material Components for Android — the library behind Material You theming + the FAB/Snackbar. */
    const val MATERIAL_COORDINATE = "com.google.android.material:material:1.12.0"

    fun isKotlin(args: TemplateArgs): Boolean = args.string("language", "java").equals("kotlin", ignoreCase = true)
}

/**
 * A native Android application: one `app` module (android-app) with an `AndroidFacet`, an editable
 * `AndroidManifest.xml`, `res/` (strings, colors, theme, and an `activity_main` layout), and a
 * `MainActivity` that inflates that layout to show a "Hello, World!" page. A complete, dependency-free
 * starter app that assembles to a signed APK through the existing `AndroidBuildSystem` pipeline.
 */
object AndroidAppTemplate : ProjectTemplate {
    override val id = TemplateId("android-app")
    override val displayName = "Android App"
    override val description = "A native Android application that builds to an installable APK."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(
        AndroidTemplateSupport.languageParam,
        AndroidTemplateSupport.minSdkParam,
        AndroidTemplateSupport.targetSdkParam,
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 26)
        val targetSdk = args.int("targetSdk", AndroidTemplateSupport.COMPILE_SDK)
        val kotlin = AndroidTemplateSupport.isKotlin(args)
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
                <string name="hello_world">Hello, World!</string>
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
                ${AndroidAppAssets.ICON_BACKGROUND_COLOR_XML}
            </resources>
            """,
        )
        scaffold.writeText("app/src/main/res/values/themes.xml", AndroidAppAssets.themesXml)
        scaffold.writeText("app/src/main/res/values-night/themes.xml", AndroidAppAssets.themesNightXml)
        for ((rel, content) in AndroidAppAssets.launcherIconResFiles) {
            scaffold.writeText("app/src/main/res/$rel", content)
        }
        scaffold.writeText(
            "app/src/main/res/layout/activity_main.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:gravity="center">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/hello_world"
                    android:textSize="24sp"/>
            </LinearLayout>
            """,
        )
        if (kotlin) {
            scaffold.writeText(
                "app/src/main/kotlin/$path/MainActivity.kt",
                """
                package $pkg

                import android.app.Activity
                import android.os.Bundle

                class MainActivity : Activity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                    }
                }
                """,
            )
        } else {
            scaffold.writeText(
                "app/src/main/java/$path/MainActivity.java",
                """
                package $pkg;

                import android.app.Activity;
                import android.os.Bundle;

                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_main);
                    }
                }
                """,
            )
        }
    }
}

/**
 * A Material You (Material 3) Android application: one `app` module wired to **Google's Material Components
 * library** ([AndroidTemplateSupport.MATERIAL_COORDINATE], resolved by the host after generation). The app
 * theme extends `Theme.Material3.DynamicColors.DayNight` so it adopts the system **dynamic colour** palette
 * on Android 12+ and a light/dark Material 3 baseline below it, and the starter screen is the canonical
 * **FAB example**: a `CoordinatorLayout` with a `FloatingActionButton` whose tap shows a `Snackbar`. The
 * `MainActivity` extends `AppCompatActivity` (pulled in transitively by Material) so the Material 3 theme
 * resolves. Assembles to a signed APK through the existing `AndroidBuildSystem` pipeline (AAR resources +
 * D8 dexing of the Material/AndroidX closure).
 */
object MaterialYouAppTemplate : ProjectTemplate {
    override val id = TemplateId("android-material-you")
    override val displayName = "Material You App"
    override val description = "A Material 3 app with dynamic colour theming and a Floating Action Button."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(
        AndroidTemplateSupport.languageParam,
        // Material Components requires minSdk 21; drop the lower options the plain app template offers.
        AndroidTemplateSupport.minSdkParam.copy(
            options = AndroidTemplateSupport.minSdkParam.options.filter { it.value.toInt() >= 21 },
            defaultIndex = 0,
        ),
        AndroidTemplateSupport.targetSdkParam,
    )

    override fun dependencies(args: TemplateArgs): List<TemplateDependency> =
        listOf(TemplateDependency(module = "app", coordinate = AndroidTemplateSupport.MATERIAL_COORDINATE))

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 21)
        val targetSdk = args.int("targetSdk", AndroidTemplateSupport.COMPILE_SDK)
        val kotlin = AndroidTemplateSupport.isKotlin(args)
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
                <string name="hello_world">Hello, Material You!</string>
                <string name="fab_description">Add</string>
                <string name="fab_clicked">FAB clicked</string>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/colors.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="primary">#FF6750A4</color>
                <color name="on_primary">#FFFFFFFF</color>
                ${AndroidAppAssets.ICON_BACKGROUND_COLOR_XML}
            </resources>
            """,
        )
        // Theme.Material3.DynamicColors.DayNight: dynamic (wallpaper-derived) colour on Android 12+, a
        // Material 3 light/dark baseline below it. No values-night override needed — DayNight handles it.
        scaffold.writeText(
            "app/src/main/res/values/themes.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="Theme.Material3.DynamicColors.DayNight">
                    <item name="colorPrimary">@color/primary</item>
                    <item name="colorOnPrimary">@color/on_primary</item>
                </style>
            </resources>
            """,
        )
        for ((rel, content) in AndroidAppAssets.launcherIconResFiles) {
            scaffold.writeText("app/src/main/res/$rel", content)
        }
        scaffold.writeText(
            "app/src/main/res/layout/activity_main.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <androidx.coordinatorlayout.widget.CoordinatorLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent"
                android:layout_height="match_parent">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center"
                    android:text="@string/hello_world"
                    android:textAppearance="?attr/textAppearanceHeadlineSmall"/>

                <com.google.android.material.floatingactionbutton.FloatingActionButton
                    android:id="@+id/fab"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="bottom|end"
                    android:layout_margin="16dp"
                    android:contentDescription="@string/fab_description"
                    android:src="@android:drawable/ic_input_add"/>
            </androidx.coordinatorlayout.widget.CoordinatorLayout>
            """,
        )
        if (kotlin) {
            scaffold.writeText(
                "app/src/main/kotlin/$path/MainActivity.kt",
                """
                package $pkg

                import android.os.Bundle
                import androidx.appcompat.app.AppCompatActivity
                import com.google.android.material.floatingactionbutton.FloatingActionButton
                import com.google.android.material.snackbar.Snackbar

                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { view ->
                            Snackbar.make(view, R.string.fab_clicked, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                """,
            )
        } else {
            scaffold.writeText(
                "app/src/main/java/$path/MainActivity.java",
                """
                package $pkg;

                import android.os.Bundle;
                import android.view.View;
                import androidx.appcompat.app.AppCompatActivity;
                import com.google.android.material.floatingactionbutton.FloatingActionButton;
                import com.google.android.material.snackbar.Snackbar;

                public class MainActivity extends AppCompatActivity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_main);
                        FloatingActionButton fab = findViewById(R.id.fab);
                        fab.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                Snackbar.make(view, R.string.fab_clicked, Snackbar.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                """,
            )
        }
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

    override fun parameters(): List<TemplateParameter> = listOf(
        AndroidTemplateSupport.languageParam,
        AndroidTemplateSupport.minSdkParam,
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 26)
        val kotlin = AndroidTemplateSupport.isKotlin(args)
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
        if (kotlin) {
            scaffold.writeText(
                "lib/src/main/kotlin/$path/LibraryText.kt",
                """
                package $pkg

                /** Library code resolving its OWN R (merged into a consuming app's R). */
                object LibraryText {
                    fun titleRes(): Int = R.string.lib_title
                }
                """,
            )
        } else {
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
}
