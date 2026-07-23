plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// lang-xml — the XML LanguageBackend (Android layouts, values, manifest, drawables, menus, …). A
// dependency-free, error-tolerant hand-written parser produces the backend-neutral DOM, so completion
// works on the half-typed source the editor always holds, and runs identically on desktop and ART.
//
// Deliberately Android-AGNOSTIC: it knows XML, not widgets or resources. Android knowledge (the widget
// catalog, the resource repository) is injected by the host through the `XmlCompletionContributor` seam,
// keeping this module a generic XML backend that an Android layer composes on top of.
dependencies {
    api(project(":language-api"))

    // The shared IntelliJ platform environment: the XML backend now parses with the real com.intellij.psi.xml.*
    // PSI (XMLParserDefinition registered onto the one application env, alongside Kotlin) and projects it onto
    // the neutral XmlNode DOM. Brings the merged compiler/platform jar transitively (kotlin-compiler-deps api).
    implementation(project(":intellij-psi-host"))

    implementation(project(":index-api"))
    implementation(project(":analysis-api")) // owns the XML lint diagnostic provider (host data via XmlResourceHost)

    implementation(project(":plugin-api"))

    testImplementation(libs.kotlinx.coroutines.test)
}
