plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// lang-kotlin-index — the pure, compiler-FREE Kotlin symbol/index layer, shared by the two Kotlin
// backends: lang-kotlin (the hand-rolled editor backend) and lang-kotlin-aa (the K2 Analysis API
// completion runtime). It decodes classpath @kotlin.Metadata (kotlin-metadata-jvm) and Java bytecode (ASM)
// into neutral symbol/type shapes and exposes the KotlinCallableIndex / KotlinTypeShapeIndex /
// KotlinBuiltinsIndex IndexExtensions over index-api.
//
// It carries NO PSI / IntelliJ / compiler dependency, so it is safe to load into the isolated AA
// classloader alongside the un-relocated org.jetbrains.kotlin.* -for-ide artifacts.
dependencies {
    api(project(":language-api")) // the neutral symbol model surface (TypeRef/SymbolKind/Modifier/SymbolOrigin)
    api(project(":index-api"))    // IndexExtension SPI + shared value types
    // Decode Kotlin libraries' @kotlin.Metadata to recover real Kotlin signatures (extensions, properties,
    // default args, nullability) that plain bytecode erases. Small + compiler-free.
    implementation(libs.kotlin.metadata.jvm)
    // Read the @Metadata annotation values (and Java bytecode shape) off classpath .class files.
    implementation(libs.ow2.asm)
}
