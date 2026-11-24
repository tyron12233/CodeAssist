package dev.codeassist.dart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FlutterPluginsTest {

    @TempDir
    lateinit var dir: Path

    private fun write(rel: String, text: String): Path =
        dir.resolve(rel).also { Files.createDirectories(it.parent); Files.write(it, text.trimIndent().toByteArray()) }

    /** A package at `pkgs/<name>` with [pubspec], listed in the app's package config. */
    private fun pkg(name: String, pubspec: String) = write("pkgs/$name/pubspec.yaml", pubspec)

    private fun packageConfig(vararg names: String) = write(
        "app/.dart_tool/package_config.json",
        """{"configVersion":2,"packages":[""" + (names.toList() + "app").joinToString(",") { n ->
            val uri = if (n == "app") "../" else "../../pkgs/$n/"
            """{"name":"$n","rootUri":"$uri","packageUri":"lib/"}"""
        } + "]}",
    )

    @Test
    fun discoverFindsTheAndroidPluginsTheAppsDependenciesReach() {
        write("app/pubspec.yaml", """
            name: app
            dependencies:
              flutter:
                sdk: flutter
              shared_preferences: ^2.0.0
            dev_dependencies:
              test_helper_plugin: ^1.0.0
        """)
        pkg("shared_preferences", """
            name: shared_preferences
            flutter:
              plugin:
                platforms:
                  android:
                    default_package: shared_preferences_android
            dependencies:
              shared_preferences_android: ^2.0.0
        """)
        pkg("shared_preferences_android", """
            name: shared_preferences_android
            flutter:
              plugin:
                implements: shared_preferences
                platforms:
                  android:
                    package: io.flutter.plugins.sharedpreferences
                    pluginClass: SharedPreferencesPlugin
                    dartPluginClass: SharedPreferencesAndroid
            dependencies:
              jni: ^1.0.0
        """)
        pkg("jni", """
            name: jni
            flutter:
              plugin:
                platforms:
                  android:
                    ffiPlugin: true
                    package: com.github.dart_lang.jni
                    pluginClass: JniPlugin
        """)
        pkg("test_helper_plugin", """
            name: test_helper_plugin
            flutter:
              plugin:
                platforms:
                  android:
                    package: x.y
                    pluginClass: Helper
        """)
        for (n in listOf("shared_preferences_android", "jni", "test_helper_plugin")) Files.createDirectories(dir.resolve("pkgs/$n/android"))
        packageConfig("shared_preferences", "shared_preferences_android", "jni", "test_helper_plugin")

        val plugins = FlutterPlugins.discover(dir.resolve("app"))

        // The app-facing package registers nothing for Android itself; a dev dependency's plugin is left out.
        assertEquals(listOf("jni", "shared_preferences_android"), plugins.map { it.name })
        val prefs = plugins.single { it.name == "shared_preferences_android" }
        assertEquals("SharedPreferencesAndroid", prefs.dartPluginClass)
        assertEquals("shared_preferences", prefs.implements)
        assertEquals(listOf("jni"), prefs.dependsOn, "a plugin's Android library depends on the plugins its package does")
        assertTrue(plugins.single { it.name == "jni" }.ffi)
        assertTrue(plugins.all { it.hasAndroidCode })
    }

    @Test
    fun aDartOnlyImplementationHasNoAndroidLibrary() {
        pkg("path_provider_android", """
            name: path_provider_android
            flutter:
              plugin:
                implements: path_provider
                platforms:
                  android:
                    dartPluginClass: PathProviderAndroid
        """)
        val plugin = FlutterPlugins.describe(
            "path_provider_android", dir.resolve("pkgs/path_provider_android"),
            MiniYaml.parse(String(Files.readAllBytes(dir.resolve("pkgs/path_provider_android/pubspec.yaml")))) as Map<*, *>,
        )
        assertNotNull(plugin)
        assertFalse(plugin!!.hasAndroidCode)
        assertEquals("PathProviderAndroid", plugin.dartPluginClass)
    }

    @Test
    fun aKotlinDslBuildFileReads() {
        // shared_preferences_android 2.5.5's android/build.gradle.kts, cut down.
        val build = FlutterPlugins.parseAndroidBuild(
            """
            plugins {
                id("com.android.library")
            }
            android {
                namespace = "io.flutter.plugins.sharedpreferences"
                compileSdk = flutter.compileSdkVersion
                defaultConfig {
                    minSdk = 24
                }
                dependencies {
                    implementation("androidx.datastore:datastore:1.2.1")
                    implementation("androidx.datastore:datastore-preferences:1.2.1")
                    implementation("androidx.preference:preference:1.2.1")
                    // implementation("commented:out:1.0")
                    testImplementation("junit:junit:4.13.2")
                    implementation(libs.some.catalog.entry)
                }
            }
            """.trimIndent(),
        )
        assertEquals("io.flutter.plugins.sharedpreferences", build.namespace)
        assertEquals(24, build.minSdk)
        assertEquals(
            listOf(
                "implementation" to "androidx.datastore:datastore:1.2.1",
                "implementation" to "androidx.datastore:datastore-preferences:1.2.1",
                "implementation" to "androidx.preference:preference:1.2.1",
            ),
            build.dependencies,
        )
        assertEquals(listOf("implementation libs.some.catalog.entry"), build.unreadDependencies)
        assertNull(build.cmakePath)
    }

    @Test
    fun aGroovyBuildFileWithExtraSourcesAndCMakeReads() {
        // jni 1.2.1's android/build.gradle, cut down.
        val build = FlutterPlugins.parseAndroidBuild(
            """
            apply plugin: 'com.android.library'
            android {
                if (project.android.hasProperty("namespace")) {
                    namespace = 'com.github.dart_lang.jni'
                }
                sourceSets {
                    main {
                        java {
                            srcDirs '../java/src/main/java'
                        }
                    }
                }
                compileSdk = 35
                externalNativeBuild {
                    cmake {
                        path = "../src/CMakeLists.txt"
                    }
                }
                defaultConfig {
                    minSdkVersion 21
                }
            }
            """.trimIndent(),
        )
        assertEquals("com.github.dart_lang.jni", build.namespace)
        assertEquals(21, build.minSdk)
        assertEquals(listOf("../java/src/main/java"), build.extraJavaDirs)
        assertEquals("../src/CMakeLists.txt", build.cmakePath)
        assertTrue(build.dependencies.isEmpty())
    }

    @Test
    fun theCMakeLibraryOfAnFfiPluginReads() {
        // jni 1.2.1's src/CMakeLists.txt, the parts that matter.
        val lib = FlutterPlugins.parseCMake(
            """
            if (JNI_AVAILABLE)
                add_library(jni SHARED
                    "dartjni.c"
                    "third_party/global_jni_env.c"
                    "include/dart_api_dl.c"
                )
                set_target_properties(jni PROPERTIES
                    PUBLIC_HEADER dartjni.h
                    OUTPUT_NAME "dartjni"
                )
                target_compile_definitions(jni PUBLIC DART_SHARED_LIB)
                if (ANDROID)
                    target_link_libraries(jni log)
                else()
                    target_link_libraries(jni ${'$'}{JNI_LIBRARIES})
                endif()
            endif()
            """.trimIndent(),
        )
        assertNotNull(lib)
        assertEquals("dartjni", lib!!.outputName)
        assertEquals(listOf("dartjni.c", "third_party/global_jni_env.c", "include/dart_api_dl.c"), lib.sources)
        assertEquals(listOf("DART_SHARED_LIB"), lib.definitions)
        assertEquals(listOf("log"), lib.linkLibraries)
    }

    @Test
    fun copyingLaysTheLibraryOutAsOneModule() {
        write("pkgs/jni/android/src/main/AndroidManifest.xml", "<manifest/>")
        write("pkgs/jni/java/src/main/java/com/github/dart_lang/jni/JniPlugin.java", "class JniPlugin {}")
        write("pkgs/jni/src/dartjni.c", "int x;")
        write("pkgs/jni/src/dartjni.h", "")
        write("pkgs/jni/src/include/dart_api_dl.c", "")
        write("pkgs/jni/src/include/dart_api_dl.h", "")
        write("pkgs/jni/src/test/ignored.c", "")
        val plugin = FlutterPlugin("jni", dir.resolve("pkgs/jni"), ffi = true, pluginClass = "JniPlugin")
        val build = PluginAndroidBuild(extraJavaDirs = listOf("../java/src/main/java"), cmakePath = "../src/CMakeLists.txt")
        val cmake = CMakeLibrary("jni", "dartjni", listOf("dartjni.c", "include/dart_api_dl.c"))
        val dest = dir.resolve("app/.dart_tool/codeassist/android-plugins/jni")

        FlutterPlugins.copyAndroidLibrary(plugin, build, cmake, dest)

        assertTrue(Files.isRegularFile(dest.resolve("src/main/AndroidManifest.xml")))
        assertTrue(Files.isRegularFile(dest.resolve("src/main/java/com/github/dart_lang/jni/JniPlugin.java")))
        assertTrue(Files.isRegularFile(dest.resolve("src/main/cpp/dartjni.c")))
        assertTrue(Files.isRegularFile(dest.resolve("src/main/cpp/include/dart_api_dl.c")))
        assertTrue(Files.isRegularFile(dest.resolve("src/main/cpp/include/dart_api_dl.h")), "headers come along")
        assertFalse(Files.exists(dest.resolve("src/main/cpp/test/ignored.c")), "only the library's own sources are built")
    }

    @Test
    fun theRegistrantsNameEachPluginOnce() {
        val prefs = FlutterPlugin(
            "shared_preferences_android", dir, androidPackage = "io.flutter.plugins.sharedpreferences",
            pluginClass = "SharedPreferencesPlugin", dartPluginClass = "SharedPreferencesAndroid", implements = "shared_preferences",
        )
        val other = FlutterPlugin("other_prefs", dir, dartPluginClass = "OtherPrefs", implements = "shared_preferences")
        val java = FlutterPlugins.javaRegistrant(listOf(prefs, FlutterPlugin("dart_only", dir, dartPluginClass = "X")))
        assertTrue("new io.flutter.plugins.sharedpreferences.SharedPreferencesPlugin()" in java)
        assertFalse("dart_only" in java)
        assertTrue(java.startsWith("package io.flutter.plugins;"))

        // One implementation per interface: the app's direct dependency wins.
        val dart = FlutterPlugins.dartRegistrant(listOf(prefs, other), directDependencies = setOf("other_prefs"))!!
        assertTrue("other_prefs.OtherPrefs.registerWith();" in dart)
        assertFalse("SharedPreferencesAndroid" in dart)
        assertTrue("import 'package:other_prefs/other_prefs.dart' as other_prefs;" in dart)
        assertNull(FlutterPlugins.dartRegistrant(listOf(FlutterPlugin("p", dir, pluginClass = "P")), emptySet()))

        val args = FlutterPlugins.dartRegistrantArgs(dir.resolve("r.dart"))
        assertEquals("package:flutter/src/dart_plugin_registrant.dart", args[3])
        assertTrue(args.last().startsWith("-Dflutter.dart_plugin_registrant=file:"))
    }
}
