// Publishing for the modules a plugin author compiles against: the plugin SPI (:plugin-api and, for a
// plugin contributing Compose UI, :plugin-ui-api), the substrate it exposes (:platform-core), and the
// feature SPIs a plugin extends (:project-model-api, :language-api, :analysis-api, :index-api, :build-api,
// :interp-api, and :vfs-api, which the others expose transitively). Nothing else in this build is published.
//
// These carry their own version rather than the app's. The SPI changes far less often than the IDE ships,
// and whether a plugin is compatible is decided by PLUGIN_API_VERSION plus the manifest's minHostVersion,
// so republishing unchanged artifacts on every release would buy nothing. The version is read out of
// plugin-api's own PLUGIN_SPI_VERSION (in `dev.ide.spi-pom`), which is also what the Create-Project template
// writes into a scaffolded plugin's build file, so the coordinate asked for and the one published cannot
// drift. `:plugin-bom` publishes that whole set of versions as one coordinate.
//
// Every published module is GPL-3.0-or-later WITH Classpath-exception-2.0 (see LICENSE-EXCEPTION), which is
// what lets a plugin linking against them choose its own license. The rest of CodeAssist stays plain
// GPL-3.0-or-later, so applying this plugin to a module is also the decision to license it that way.

plugins {
    `java-library`
    // The coordinate, the version, the POM, the staging repository and the signing rule, shared with the
    // one other thing published from this build: `:plugin-bom`.
    id("dev.ide.spi-pom")
}

java {
    // Central requires a sources artifact, and the SPI's documentation is KDoc, so the sources jar is what
    // actually carries the docs to a plugin author's editor.
    withSourcesJar()
}

// Central also requires a javadoc artifact. Kotlin produces no javadoc without Dokka; the KDoc travels in
// the sources jar instead, so this satisfies the requirement without adding a documentation toolchain.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("spi") {
            from(components["java"])
            artifact(javadocJar)
        }
    }
}
