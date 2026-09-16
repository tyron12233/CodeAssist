import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// kotlin-syntax (EXPERIMENTAL) — a portable Kotlin lexer + parser that mirrors the compiler's PSI shape.
//
// Why this exists: the editor backend (:lang-kotlin) reaches the Kotlin compiler ONLY to parse, and then
// walks the resulting PSI directly — 130 distinct `Kt*` types over ~3,000 references. That parse is the one
// thing in the language stack with no Kotlin/Native path, so it is what pins Kotlin completion and
// diagnostics to the JVM. Reimplementing the grammar from scratch, or adopting a third-party one
// (tree-sitter, ANTLR), means owning a tree whose shape differs from the one those 130 types assume, and
// every divergence shows up as a wrong diagnostic under correct code.
//
// So this module mirrors the compiler's own model instead: the same token vocabulary (`KtTokens`), the same
// element vocabulary (`KtNodeTypes`), a `PsiBuilder`-shaped `SyntaxTreeBuilder`, and a `Kt*` facade with the
// compiler's accessor names. That makes the port checkable rather than merely plausible: the tests diff our
// token stream and our tree against the real compiler's, over this repository's own sources.
//
// NOTHING depends on this module. It is deliberately off to the side until the differential suites are green
// across a wide corpus; see docs/kotlin-syntax.md for the state of play and what is not covered yet.
//
// Multiplatform with an iOS target from the first commit, on purpose: whether `commonMain` is portable is
// unanswerable until a non-JVM target actually compiles it, and a dependency that quietly arrives through
// the JVM stdlib is exactly what this module exists to avoid. It therefore has NO dependencies at all.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // The differential oracle: the real Kotlin compiler's lexer and parser, to diff ours against. JVM-only
        // by nature — it is the thing this module exists to stop needing — and test-only, so the production
        // source set stays dependency-free.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kotlin-compiler-deps"))
            implementation(project(":intellij-psi-host"))
        }

        // Declared by hand: `kotlin.mpp.applyDefaultHierarchyTemplate=false` (see gradle.properties), so no
        // intermediate apple/ios set is created for us.
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        val iosTest = create("iosTest") { dependsOn(getByName("commonTest")) }
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosArm64Test").dependsOn(iosTest)
    }
}

// The root build wires JUnit 5 into every `kotlin.jvm` module; a multiplatform one has to say so itself.
dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // The corpus sweep reads the repository's own .kt files; the differential suites stand up the real
    // compiler's PSI environment, which wants room.
    maxHeapSize = "2g"
    systemProperty("kotlinSyntax.corpusRoot", rootDir.absolutePath)
}
