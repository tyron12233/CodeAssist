package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Build output versus the project's own files: by where a file is, not only by its name or extension. */
class ProjectFilesTest {

    @Test
    fun theProjectsOwnLibrariesAreNotBuildOutput() {
        for (path in listOf(
            "app/libs/widget.aar",
            "app/libs/helper.jar",
            "libs/native.so",
            "app/src/main/jniLibs/arm64-v8a/libnative.so",
            "app/src/main/resources/lib/x86_64/libbridge.so",
            "app/src/main/resources/tools/plugin.jar",
            "gradle/wrapper/gradle-wrapper.jar",
            "app/jniLibs/armeabi-v7a/libfoo.so",
        )) {
            assertFalse(ProjectFiles.isBuildOutput(path), "$path is the project's own")
        }
    }

    @Test
    fun anythingUnderASourceTreeIsTheProjects() {
        assertFalse(ProjectFiles.isBuildOutput("app/src/main/java/com/example/build/Config.java"))
        assertFalse(ProjectFiles.isBuildOutput("app/src/main/java/com/example/build/", isDirectory = true))
        assertFalse(ProjectFiles.isBuildOutput("app/src/main/kotlin/out/Printer.kt"))
        assertFalse(ProjectFiles.isBuildOutput("app/src/main/assets/bin/tool"))
        assertFalse(ProjectFiles.isBuildOutput("app/src/main/assets/installer.apk"))
        assertFalse(ProjectFiles.isBuildOutput("app/src/main/resources/Precompiled.class"))
        assertFalse(ProjectFiles.isBuildOutput("buildSrc/src/main/kotlin/build/Deps.kt"))
    }

    @Test
    fun buildFoldersAndStrayProductsOutsideSourcesAreOutput() {
        assertTrue(ProjectFiles.isBuildOutput("app/build/outputs/apk/debug/app-debug.apk"))
        assertTrue(ProjectFiles.isBuildOutput("app/build/intermediates/merged_native_libs/libfoo.so"))
        assertTrue(ProjectFiles.isBuildOutput("app/build/outputs/aar/lib-release.aar"))
        assertTrue(ProjectFiles.isBuildOutput("app/build/", isDirectory = true))
        assertTrue(ProjectFiles.isBuildOutput("buildSrc/build/libs/buildSrc.jar"))
        // A generated source tree inside build/ is still output: build comes before src.
        assertTrue(ProjectFiles.isBuildOutput("app/build/generated/src/main/java/R.java"))
        assertTrue(ProjectFiles.isBuildOutput("app/.cxx/Debug/arm64-v8a/libnative.so"))
        assertTrue(ProjectFiles.isBuildOutput("out/production/Main.class"))
        assertTrue(ProjectFiles.isBuildOutput("app/release/app-release.apk"))
        assertTrue(ProjectFiles.isBuildOutput("classes.dex"))
        assertTrue(ProjectFiles.isBuildOutput("Payload.APK"))
    }

    @Test
    fun aCallerCanNarrowTheRule() {
        // A backup keeps a stray APK and a `bin/` folder; it only leaves out `build/`.
        assertFalse(ProjectFiles.isBuildOutput("app/release/app-release.apk", outputDirs = setOf("build"), products = false))
        assertFalse(ProjectFiles.isBuildOutput("tools/bin/run.sh", outputDirs = setOf("build"), products = false))
        assertTrue(ProjectFiles.isBuildOutput("app/build/x.txt", outputDirs = setOf("build"), products = false))
        assertFalse(ProjectFiles.isBuildOutput("app/src/main/java/exports/A.java", outputDirs = setOf("exports")))
    }

    @Test
    fun toolStateIsRecognisedAnywhere() {
        assertTrue(ProjectFiles.isToolState(".gradle/8.0/cache.bin"))
        assertTrue(ProjectFiles.isToolState("app/.idea/workspace.xml"))
        assertTrue(ProjectFiles.isToolState(".git/HEAD"))
        assertFalse(ProjectFiles.isToolState("app/src/main/java/A.java"))
    }

    @Test
    fun separatorsAndDotSegmentsAreNormalised() {
        assertFalse(ProjectFiles.isBuildOutput("app\\libs\\widget.aar"))
        assertTrue(ProjectFiles.isBuildOutput("./app/build/x.apk"))
        assertTrue(ProjectFiles.inSourceTree("app/src/main/java/A.java"))
        assertFalse(ProjectFiles.inSourceTree("app/libs/x.aar"))
    }
}
