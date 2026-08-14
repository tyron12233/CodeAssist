plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// lang-java — the IntelliJ-PSI Java LanguageBackend: editor-time code intelligence for `.java`, built on
// IntelliJ's own Java PSI parser AND its native resolution/inference engine (JavaPsiFacade +
// CoreJavaFileManager + the Cls bytecode decompiler + PsiResolveHelper/InferenceSession) rather than
// re-implementing Java semantics the way :lang-kotlin re-implements Kotlin's. Editor-only: emitting bytecode
// stays the build system's job (ecj via :lang-jdt / :jvm-build). Intended to replace :lang-jdt as the `.java`
// editor backend once at parity.
dependencies {
    api(project(":language-api"))            // the SPI (+ project-model-api / vfs-api / platform-core, transitively)
    implementation(project(":index-api"))    // shared class/member indexes for completion + go-to-symbol
    implementation(project(":analysis-api"))  // QuickFixProvider/QuickFix for the native code-fix providers

    // The unshaded IntelliJ platform + Java PSI (java-psi / java-psi-impl / java-frontback-psi) and the Kotlin
    // CLI's KotlinCoreEnvironment we stand the standalone Java resolution environment up on. One platform per
    // process (shared with :lang-kotlin / :lang-xml).
    implementation(project(":kotlin-compiler-deps"))
    // The single, process-wide IntelliJ application environment + fair parse lock + forceFullParse. Java PSI
    // parsing registers onto the SAME application env and serializes under the SAME lock (ART SIGSEGV otherwise).
    implementation(project(":intellij-psi-host"))
    implementation(libs.ow2.asm)             // classpath bytecode scanning for the completion index bridge

    testImplementation(project(":project-model-impl"))
    testImplementation(project(":index-impl"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Each test class stands up an IntelliJ platform environment, so the worker needs real heap. It does NOT
// need a fresh JVM per class: standing the environment up is the expensive part, and sharing one worker
// amortizes it across the suite (measured: 8.8s of in-test work at forkEvery(1), 2.0s sharing one worker,
// 23s → 12s wall). If the PSI footprint ever does accumulate to an OOM, set a middle `forkEvery(N)` rather
// than going back to 1.
tasks.named<Test>("test") {
    maxHeapSize = "3g"
}
