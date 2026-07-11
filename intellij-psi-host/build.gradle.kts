plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// intellij-psi-host — the ONE shared IntelliJ platform application/project environment the language
// backends parse against. IntelliJ's `ApplicationManager` has a single global application slot, so there can
// be exactly one `CoreApplicationEnvironment` in the process; and `:lang-xml` may not depend on `:lang-kotlin`
// (sibling → sibling violates the acyclic, downward-only module rule). This leaf module owns the environment
// boot (via the Kotlin compiler's `KotlinCoreEnvironment.createForProduction`, which stands up the generic
// IntelliJ core), the coarse parse lock, and the `forceFullParse` materializer; both `:lang-kotlin`
// (KotlinParserHost) and `:lang-xml` register their `ParserDefinition` additively onto it and parse through it.
dependencies {
    api(project(":kotlin-compiler-deps"))
}
