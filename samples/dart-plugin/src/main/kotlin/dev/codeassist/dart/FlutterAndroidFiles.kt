package dev.codeassist.dart

import dev.ide.model.template.ProjectScaffold

/**
 * The Android side of a Flutter app, as `flutter create` writes it into `android/app`: what an APK needs that is
 * not Flutter's. Java rather than Kotlin, because the IDE compiles Java on the device in a fraction of the time.
 */
object FlutterAndroidFiles {

    fun write(scaffold: ProjectScaffold, dir: String, appId: String, label: String) {
        val main = "$dir/src/main"
        scaffold.writeText("$main/AndroidManifest.xml", manifest(label))
        scaffold.writeText("$main/java/${appId.replace('.', '/')}/MainActivity.java", mainActivity(appId))
        scaffold.writeText("$main/java/io/flutter/plugins/GeneratedPluginRegistrant.java", PLUGIN_REGISTRANT)
        scaffold.writeText("$main/res/values/styles.xml", styles("@android:style/Theme.Light.NoTitleBar"))
        scaffold.writeText("$main/res/values-night/styles.xml", styles("@android:style/Theme.Black.NoTitleBar"))
        scaffold.writeText("$main/res/values/colors.xml", COLORS)
        scaffold.writeText("$main/res/drawable/launch_background.xml", LAUNCH_BACKGROUND)
        scaffold.writeText("$main/res/drawable/ic_launcher_foreground.xml", ICON_FOREGROUND)
        scaffold.writeText("$main/res/mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE_ICON)
    }

    /**
     * `flutter create`'s manifest, less the `${applicationName}` its Gradle plugin fills in. INTERNET is in the
     * main manifest (`flutter create` keeps it in the debug one, which this build does not merge): the debug
     * engine serves its Dart VM service on a local socket, and most apps reach the network anyway.
     */
    fun manifest(label: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">

            <uses-permission android:name="android.permission.INTERNET" />

            <application
                android:label="${xml(label)}"
                android:icon="@mipmap/ic_launcher">
                <activity
                    android:name=".MainActivity"
                    android:exported="true"
                    android:launchMode="singleTop"
                    android:taskAffinity=""
                    android:theme="@style/LaunchTheme"
                    android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"
                    android:hardwareAccelerated="true"
                    android:windowSoftInputMode="adjustResize">
                    <!-- The theme the app switches to once Flutter draws its first frame. -->
                    <meta-data
                        android:name="io.flutter.embedding.android.NormalTheme"
                        android:resource="@style/NormalTheme" />
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
                <!-- Tells the Flutter tooling and plugins this app uses the v2 Android embedding. -->
                <meta-data
                    android:name="flutterEmbedding"
                    android:value="2" />
            </application>

            <!-- Lets Flutter's text fields offer the system's text-processing actions. -->
            <queries>
                <intent>
                    <action android:name="android.intent.action.PROCESS_TEXT" />
                    <data android:mimeType="text/plain" />
                </intent>
            </queries>
        </manifest>
    """.trimIndent() + "\n"

    fun mainActivity(appId: String): String = """
        package $appId;

        import io.flutter.embedding.android.FlutterActivity;

        /** The app's one Activity. Flutter draws all of it; lib/main.dart is the app. */
        public class MainActivity extends FlutterActivity {
        }
    """.trimIndent() + "\n"

    /**
     * What `FlutterActivity` registers the app's Flutter plugins through. `flutter` generates it from the
     * pubspec's plugins; with none that have Android code (the only kind the IDE can build yet) it is empty,
     * and having it keeps the engine from warning on every start that it is missing.
     */
    private val PLUGIN_REGISTRANT = """
        package io.flutter.plugins;

        import androidx.annotation.Keep;
        import androidx.annotation.NonNull;
        import io.flutter.embedding.engine.FlutterEngine;

        /** Registers the app's Flutter plugins that have Android code. It has none. */
        @Keep
        public final class GeneratedPluginRegistrant {
            public static void registerWith(@NonNull FlutterEngine flutterEngine) {
            }
        }
    """.trimIndent() + "\n"

    private fun styles(parent: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <!-- Shown while the Flutter UI initializes: the window background is the splash screen. -->
            <style name="LaunchTheme" parent="$parent">
                <item name="android:windowBackground">@drawable/launch_background</item>
            </style>
            <!-- Behind the Flutter UI once it is up, and while the window resizes for the keyboard. -->
            <style name="NormalTheme" parent="$parent">
                <item name="android:windowBackground">?android:colorBackground</item>
            </style>
        </resources>
    """.trimIndent() + "\n"

    private val COLORS = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <color name="ic_launcher_background">#3F51B5</color>
        </resources>
    """.trimIndent() + "\n"

    private val LAUNCH_BACKGROUND = """
        <?xml version="1.0" encoding="utf-8"?>
        <!-- The splash screen. Add a centred image with an <item><bitmap android:gravity="center" .../></item>. -->
        <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
            <item android:drawable="?android:colorBackground" />
        </layer-list>
    """.trimIndent() + "\n"

    private val ICON_FOREGROUND = """
        <?xml version="1.0" encoding="utf-8"?>
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
            <path
                android:fillColor="#FFFFFF"
                android:pathData="M54,32 L76,54 L54,76 L32,54 Z" />
        </vector>
    """.trimIndent() + "\n"

    private val ADAPTIVE_ICON = """
        <?xml version="1.0" encoding="utf-8"?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            <background android:drawable="@color/ic_launcher_background" />
            <foreground android:drawable="@drawable/ic_launcher_foreground" />
        </adaptive-icon>
    """.trimIndent() + "\n"

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
}
