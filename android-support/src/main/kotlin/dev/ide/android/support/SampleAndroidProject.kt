package dev.ide.android.support

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModelStore
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * The IDE's default sample: an Android multi-module app `app (android-app) → feature (android-lib) →
 * core (java-lib)`. The main module is the Android application, with an editable `AndroidManifest.xml`,
 * editable `res/` (strings + colors + a theme), the library's own resource merged into the app, the
 * decoupled library `R`, and a transitive plain-Java module.
 *
 * Module types are passed in (the host resolves + registers them). [languageLevel] is JAVA_17 on a desktop
 * JVM; the on-device host passes JAVA_8 (a bundled non-modular `android.jar` keeps DOM analysis working).
 */
object SampleAndroidProject {

    const val PROJECT = "android-demo"

    fun generate(
        store: ProjectModelStore,
        androidApp: ModuleType,
        androidLib: ModuleType,
        javaLib: ModuleType,
        languageLevel: LanguageLevel = LanguageLevel.JAVA_17,
    ) {
        store.workspace.beginModification().apply { addProject(PROJECT, BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.first { it.name == PROJECT }.beginModification().apply {
            addModule("core", javaLib).apply {
                this.languageLevel = languageLevel
                addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE))))
            }
            addModule("feature", androidLib).apply {
                this.languageLevel = languageLevel
                putFacet(AndroidFacet(namespace = "com.example.feature", compileSdk = 34, minSdk = 24, isApplication = false))
                addDependency(ModuleDependency(ModuleId("core"), DependencyScope.API, exported = true))
            }
            addModule("app", androidApp).apply {
                this.languageLevel = languageLevel
                putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                addDependency(ModuleDependency(ModuleId("feature"), DependencyScope.IMPLEMENTATION))
            }
            commit()
        }

        write(store, "core/src/main/java/com/example/core/Calc.java", CALC)
        write(store, "feature/src/main/res/values/strings.xml", FEATURE_STRINGS)
        write(store, "feature/src/main/java/com/example/feature/FeatureText.java", FEATURE_TEXT)
        write(store, "app/src/main/AndroidManifest.xml", APP_MANIFEST)
        write(store, "app/src/main/res/values/strings.xml", APP_STRINGS)
        write(store, "app/src/main/res/values/colors.xml", APP_COLORS)
        write(store, "app/src/main/res/values/themes.xml", dev.ide.android.support.templates.AndroidAppAssets.themesXml)
        write(store, "app/src/main/res/values-night/themes.xml", dev.ide.android.support.templates.AndroidAppAssets.themesNightXml)
        write(store, "app/src/main/res/values/attrs.xml", APP_ATTRS)
        write(store, "app/src/main/res/layout/activity_main.xml", APP_LAYOUT)
        for ((rel, content) in dev.ide.android.support.templates.AndroidAppAssets.launcherIconResFiles) {
            write(store, "app/src/main/res/$rel", content)
        }
        write(store, "app/src/main/java/com/example/app/MainActivity.java", APP_ACTIVITY)
        write(store, "app/src/main/java/com/example/app/MyChart.java", APP_CUSTOM_VIEW)
    }

    private fun write(store: ProjectModelStore, relPath: String, content: String) {
        val file = store.rootPath.resolve(relPath)
        Files.createDirectories(file.parent)
        file.writeText(content.trimIndent() + "\n")
    }

    private val CALC = """
        package com.example.core;

        /** Plain-Java module, reached transitively from the app via the android-lib. */
        public final class Calc {
            public static int add(int a, int b) { return a + b; }
        }
    """

    private val FEATURE_STRINGS = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="feature_title">Feature</string>
        </resources>
    """

    private val FEATURE_TEXT = """
        package com.example.feature;

        import com.example.core.Calc;

        /** android-lib code: references its OWN R (resolved via the app's merged R) and the :core module. */
        public final class FeatureText {
            public static int titleRes() { return R.string.feature_title; }
            public static int sum() { return Calc.add(2, 3); }
        }
    """

    private val APP_MANIFEST = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
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
    """

    private val APP_STRINGS = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">Android Demo</string>
            <string name="greeting">Hello, Android</string>
        </resources>
    """

    private val APP_COLORS = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <color name="primary">#FF6200EE</color>
            <color name="on_primary">#FFFFFFFF</color>
            <color name="ic_launcher_background">#3DDC84</color>
        </resources>
    """

    private val APP_ATTRS = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <declare-styleable name="MyChart">
                <attr name="barColor" format="color"/>
                <attr name="maxValue" format="integer"/>
                <attr name="chartLabel" format="string"/>
            </declare-styleable>
        </resources>
    """

    private val APP_LAYOUT = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:padding="16dp"
            android:background="@color/on_primary">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/greeting"
                android:textColor="@color/primary"
                android:textSize="20sp"/>
            <com.example.app.MyChart
                android:layout_width="match_parent"
                android:layout_height="200dp"
                app:barColor="@color/primary"
                app:maxValue="100"
                app:chartLabel="Revenue"/>
        </LinearLayout>
    """

    private val APP_CUSTOM_VIEW = """
        package com.example.app;

        import android.content.Context;
        import android.content.res.TypedArray;
        import android.graphics.Canvas;
        import android.graphics.Paint;
        import android.util.AttributeSet;
        import android.view.View;

        /** A tiny custom view with custom app: attributes — the layout-preview acceptance fixture. */
        public class MyChart extends View {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private int barColor = 0xFF6200EE;
            private int maxValue = 100;

            public MyChart(Context context, AttributeSet attrs) {
                super(context, attrs);
                TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.MyChart, 0, 0);
                barColor = a.getColor(R.styleable.MyChart_barColor, barColor);
                maxValue = a.getInt(R.styleable.MyChart_maxValue, maxValue);
                a.recycle();
                paint.setColor(barColor);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                int bars = 5;
                float bw = w / (bars * 2f);
                for (int i = 0; i < bars; i++) {
                    float bh = h * ((i + 1) / (float) bars);
                    float left = i * 2 * bw + bw / 2f;
                    canvas.drawRect(left, h - bh, left + bw, h, paint);
                }
            }
        }
    """

    private val APP_ACTIVITY = """
        package com.example.app;

        import android.app.Activity;
        import android.os.Bundle;
        import android.widget.TextView;
        import com.example.feature.FeatureText;

        /** The main Android module. Uses its own resources, the feature library's resource, and its code. */
        public class MainActivity extends Activity {
            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                TextView tv = new TextView(this);
                tv.setText(getString(R.string.greeting) + " / " + getString(R.string.feature_title) + " = " + FeatureText.sum());
                setContentView(tv);
            }
        }
    """
}
