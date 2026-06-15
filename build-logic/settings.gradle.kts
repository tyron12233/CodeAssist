// Settings for the `build-logic` included build — home of Gradle plugins that must implement AGP types
// (AsmClassVisitorFactory) and therefore cannot live in buildSrc (whose classloader can't see AGP). The
// root build pulls this in via `pluginManagement { includeBuild("build-logic") }`.
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google() // AGP's gradle-api (com.android.build.api.instrumentation.*) is published here.
    }
}
