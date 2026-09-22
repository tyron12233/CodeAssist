// Publishing for the published SPI modules that are multiplatform: :model-api, :platform-core, :vfs-api,
// :language-api and :project-model-api. The JVM-only ones take `dev.ide.spi-publish` instead, which applies
// `java-library`, a plugin the multiplatform one rejects. That is why this exists as a second convention
// rather than as a branch inside that one. Same coordinate, same POM, same staging repository and same
// signing rule; what differs is that a multiplatform module publishes SEVERAL components (the root module
// plus one per target) where a JVM one publishes a single jar.
//
// That difference is the whole reason this is a convention rather than eight lines in each build script.
// Central requires a javadoc artifact on every component, Kotlin produces none without Dokka, and the KDoc
// travels in the sources jar the multiplatform plugin already publishes, so the artifact is an empty
// placeholder. Each publication needs one of its own: attaching a single shared jar to all four makes all
// four signing tasks write the same `<module>-<version>-javadoc.jar.asc`, and Gradle stops the build for an
// undeclared dependency between tasks racing over one path. An appendix keeps the file names apart on disk;
// `maven-publish` names the published file from the coordinate, so the appendix never reaches the
// repository.

plugins {
    `maven-publish`
    // The coordinate, the version, the POM, the staging repository and the signing rule, shared with
    // `dev.ide.spi-publish` and `:plugin-bom`.
    id("dev.ide.spi-pom")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        val publication = name
        val javadocJar = tasks.register<Jar>("${publication}JavadocJar") {
            archiveClassifier.set("javadoc")
            archiveAppendix.set(publication)
        }
        artifact(javadocJar)
    }
}
