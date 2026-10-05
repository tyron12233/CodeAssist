package dev.codeassist.ndk

import dev.codeassist.ndk.jni.Jni
import dev.codeassist.ndk.jni.JvmType
import dev.codeassist.ndk.jni.NativeMethod
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * An Android app in Java or Kotlin that calls into C++ over JNI: Android Studio's "Native C++" shape.
 *
 * The activity declares one native method and shows what it returns; the C++ file implements it. The JNI
 * symbol is derived by [Jni] from the activity's package and class, the same code the editor's JNI check
 * uses, so the generated pair cannot disagree (a mismatch builds, links, and throws `UnsatisfiedLinkError`
 * on the first call).
 *
 * The activity is a plain `android.app.Activity` building its view in code, with no AndroidX and no layout,
 * so the project builds offline with nothing to download.
 */
class NativeAppTemplate : ProjectTemplate {

    override val id = TemplateId("ndk-native-app")
    override val displayName = "Native C++ App"
    override val description =
        "An Android app in Java or Kotlin that calls C++ through JNI, compiled on the device."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"

    override fun parameters(): List<TemplateParameter> = listOf(
        TemplateParameter.Choice(
            key = NdkTemplateSupport.LANGUAGE,
            label = "Language",
            options = listOf(
                TemplateParameter.Choice.Option("kotlin", "Kotlin"),
                TemplateParameter.Choice.Option("java", "Java"),
            ),
            help = "The language of the app's own code. The native half is C++ either way.",
        ),
        NdkTemplateSupport.libraryNameParam(
            "The native library the app loads with System.loadLibrary, written without the lib prefix and the .so suffix.",
        ),
        NdkTemplateSupport.standardParam(cppOnly = true),
        TemplateParameter.Choice(
            key = NdkTemplateSupport.MIN_SDK,
            label = "Minimum SDK",
            options = listOf(
                TemplateParameter.Choice.Option("26", "API 26 (Android 8.0)"),
                TemplateParameter.Choice.Option("24", "API 24 (Android 7.0)"),
                TemplateParameter.Choice.Option("29", "API 29 (Android 10)"),
                TemplateParameter.Choice.Option("33", "API 33 (Android 13)"),
            ),
            help = "Lowest Android version the app supports, and the platform the C++ is compiled against.",
        ),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val kotlin = args.string(NdkTemplateSupport.LANGUAGE, "kotlin").equals("kotlin", ignoreCase = true)
        val library = NdkTemplateSupport.libraryName(args)
        val standard = args.string(NdkTemplateSupport.STANDARD, "c++17")
        val minSdk = args.int(NdkTemplateSupport.MIN_SDK, 26)
        val packageName = args.packageName

        NdkTemplateSupport.scaffoldModule(
            scaffold = scaffold,
            projectName = args.name,
            moduleTypeId = "android-app",
            ndk = NdkTemplateSupport.withStandard(NdkFacet(libraryName = library, minSdk = minSdk), standard),
            android = mapOf(
                "namespace" to packageName,
                "compileSdk" to NdkTemplateSupport.COMPILE_SDK,
                "minSdk" to minSdk,
                "targetSdk" to NdkTemplateSupport.COMPILE_SDK,
            ),
        )

        val method = NativeMethod(
            className = "$packageName.MainActivity",
            name = "stringFromJNI",
            params = emptyList(),
            returnType = JvmType("Ljava/lang/String;"),
            isStatic = false,
            nameOffset = 0,
        )
        val packagePath = packageName.replace('.', '/')
        if (kotlin) scaffold.writeText("app/src/main/kotlin/$packagePath/MainActivity.kt", kotlinActivity(packageName, library))
        else scaffold.writeText("app/src/main/java/$packagePath/MainActivity.java", javaActivity(packageName, library))
        scaffold.writeText("app/src/main/cpp/$library.cpp", nativeSource(Jni.shortName(method)))
        scaffold.writeText("app/src/main/AndroidManifest.xml", manifest(packageName))
        scaffold.writeText("app/src/main/res/values/strings.xml", strings(args.name))
        scaffold.writeText("README.md", readme(library, kotlin))
    }

    private fun kotlinActivity(pkg: String, library: String) = """
        package $pkg

        import android.app.Activity
        import android.os.Bundle
        import android.view.Gravity
        import android.widget.TextView

        class MainActivity : Activity() {

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(TextView(this).apply {
                    text = stringFromJNI()
                    textSize = 22f
                    gravity = Gravity.CENTER
                })
            }

            /** Implemented in C++: app/src/main/cpp/$library.cpp. */
            external fun stringFromJNI(): String

            companion object {
                init {
                    // Loads lib$library.so, which the build compiles from app/src/main/cpp.
                    System.loadLibrary("$library")
                }
            }
        }
    """.trimIndent() + "\n"

    private fun javaActivity(pkg: String, library: String) = """
        package $pkg;

        import android.app.Activity;
        import android.os.Bundle;
        import android.view.Gravity;
        import android.widget.TextView;

        public class MainActivity extends Activity {

            static {
                // Loads lib$library.so, which the build compiles from app/src/main/cpp.
                System.loadLibrary("$library");
            }

            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                TextView text = new TextView(this);
                text.setText(stringFromJNI());
                text.setTextSize(22f);
                text.setGravity(Gravity.CENTER);
                setContentView(text);
            }

            /** Implemented in C++: app/src/main/cpp/$library.cpp. */
            public native String stringFromJNI();
        }
    """.trimIndent() + "\n"

    private fun nativeSource(symbol: String) = """
        #include <jni.h>
        #include <string>

        // The name is how the JVM finds this function: Java_ + the class + _ + the method. Renaming the
        // activity or moving it to another package means renaming this too; the editor warns when they differ.
        extern "C" JNIEXPORT jstring JNICALL
        $symbol(JNIEnv* env, jobject /* this */) {
            std::string hello = "Hello from C++";
            return env->NewStringUTF(hello.c_str());
        }
    """.trimIndent() + "\n"

    private fun manifest(packageName: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">

            <application
                android:label="@string/app_name"
                android:theme="@android:style/Theme.Material.Light.DarkActionBar">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>

        </manifest>
    """.trimIndent() + "\n"

    private fun strings(appName: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">$appName</string>
        </resources>
    """.trimIndent() + "\n"

    private fun readme(library: String, kotlin: Boolean) = """
        # Native C++ app

        An Android app whose ${if (kotlin) "Kotlin" else "Java"} code calls C++ through JNI, all compiled on the
        device by the NDK plugin's bundled clang.

        - `MainActivity` declares `${if (kotlin) "external fun" else "native"} stringFromJNI()` and loads `lib$library.so`.
        - `app/src/main/cpp/$library.cpp` implements it. The function's name encodes the class and method; the
          editor flags a native method with no C++ function (with a fix that writes one), and a C++ function
          no method matches.
        - The `[ndk]` table in `app/module.toml` is the native build configuration: source directories, ABIs,
          the language standard, the C++ runtime (`stl`), optimization, extra flags and the libraries to link.
    """.trimIndent() + "\n"
}
