plugins {
    `java-library`
}

// applog-runtime — a tiny, dependency-free Java runtime that the Android build system injects into DEBUG
// builds (never release). It ships as a jar of plain .class files; the debug APK pipeline adds it to the
// app's external dex scope and registers its ContentProvider in the merged manifest, so on app start the
// provider boots a background bridge that forwards the app's own logs (logcat of its own PID, plus
// System.out/err and uncaught exceptions as a fallback) over an abstract-namespace LocalSocket back to the
// IDE, which shows them in a live "Logcat" console tab. See docs/app-log-forwarding.md.
//
// It is written in Java (not Kotlin) and links NOTHING at runtime so the injected footprint is a couple of
// classes with no kotlin-stdlib. The android.* framework types it uses are provided by a throwaway stub
// android.jar at compile time only (compileOnly) — they resolve against the real android.jar when D8 dexes
// this jar into a user's app. That keeps the module buildable with no Android SDK (CI_CORE_ONLY).
dependencies {
    compileOnly(libs.android.stub)
}

java {
    // Java 8 bytecode: D8 rejects class files newer than it supports, and these classes are dexed into every
    // instrumented debug APK (whatever the user's language level). No lambdas/records — plain 8.
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
