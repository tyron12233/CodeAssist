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
    implementation(project(":index-api"))

    testImplementation(libs.kotlinx.coroutines.test)
}
