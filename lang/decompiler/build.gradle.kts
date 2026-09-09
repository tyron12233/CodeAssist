plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// :decompiler — the "navigate into a library class" content producer. Given a class FQN + the module's
// classpath (and any attached source jars), it returns display text: the attached SOURCE if present, else a
// DECOMPILED view. Java classes decompile full-body via CFR (a self-contained, ART-safe decompiler); Kotlin
// classes render a top-level STUB from @kotlin.Metadata (there is no standalone bytecode→Kotlin-source
// decompiler, so we reconstruct declarations from the metadata the shared reader already decodes). The
// CFR dependency is isolated here so the rest of the framework stays free of it.
dependencies {
    api(project(":language-api"))                 // VirtualFile + neutral types
    implementation(project(":lang-kotlin-index"))  // ClasspathReader + KotlinMetadata + TypeRendering (Kotlin stub)
    implementation(libs.cfr)                       // full-body Java decompiler (ART-safe, single jar)
    implementation(libs.ow2.asm)                   // @Metadata presence check + inner-class listing
    implementation(libs.kotlin.metadata.jvm)
}
