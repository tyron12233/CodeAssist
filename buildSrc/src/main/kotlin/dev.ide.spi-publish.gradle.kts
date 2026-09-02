// Publishing for the modules a plugin author compiles against: the plugin SPI (:plugin-api), the substrate
// it exposes (:platform-core), and the feature SPIs a plugin extends (:project-model-api, :language-api,
// :analysis-api, :index-api, :build-api, and :vfs-api, which the others expose transitively). Nothing else
// in this build is published.
//
// These carry their own version rather than the app's. The SPI changes far less often than the IDE ships,
// and whether a plugin is compatible is decided by PLUGIN_API_VERSION plus the manifest's minHostVersion,
// so republishing unchanged artifacts on every release would buy nothing. The version is read out of
// plugin-api's own PLUGIN_SPI_VERSION, which is also what the Create-Project template writes into a
// scaffolded plugin's build file, so the coordinate asked for and the coordinate published cannot drift.
//
// Every published module is GPL-3.0-or-later WITH Classpath-exception-2.0 (see LICENSE-EXCEPTION), which is
// what lets a plugin linking against them choose its own license. The rest of CodeAssist stays plain
// GPL-3.0-or-later, so applying this plugin to a module is also the decision to license it that way.

plugins {
    `java-library`
    `maven-publish`
    signing
}

val projectUrl = "https://github.com/tyron12233/CodeAssist"

/** The published SPI version, taken from the constant the template also emits. */
val spiVersion: String = run {
    val source = rootProject.file("plugin-api/src/main/kotlin/dev/ide/plugin/PluginManifest.kt")
    val match = Regex("""PLUGIN_SPI_VERSION:\s*String\s*=\s*"([^"]+)"""").find(source.readText())
    requireNotNull(match) {
        "PLUGIN_SPI_VERSION not found in ${source.name}. The publish version is read from that constant so " +
            "it cannot drift from the coordinate a scaffolded plugin project asks for."
    }.groupValues[1]
}

group = "io.github.tyron12233"
version = spiVersion

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
            pom {
                name.set("CodeAssist ${project.name}")
                description.set(
                    when (project.name) {
                        "plugin-api" ->
                            "The CodeAssist plugin SPI: the Plugin entry point, its manifest, the " +
                                "registration API, and the action model."
                        "platform-core" ->
                            "The CodeAssist platform substrate the plugin SPI exposes: extension points, " +
                                "scoped services, the message bus, logging, and the settings model."
                        "project-model-api" ->
                            "The CodeAssist project model: module types, project templates, facets and " +
                                "their codecs, and file icons."
                        "language-api" ->
                            "The CodeAssist language SPI: the neutral syntax tree, resolution, file types, " +
                                "completion, formatting, and folding."
                        "analysis-api" ->
                            "The CodeAssist analysis SPI: analyzers, the diagnostic model, quick fixes, and " +
                                "editor action providers."
                        "index-api" ->
                            "The CodeAssist index SPI: persisted, incrementally maintained project indexes."
                        "build-api" ->
                            "The CodeAssist build SPI: build systems, build plugins, source generators, and " +
                                "run tasks."
                        "vfs-api" ->
                            "The CodeAssist virtual file system, which the other SPI artifacts expose."
                        else -> "A CodeAssist plugin SPI artifact."
                    }
                )
                url.set(projectUrl)
                licenses {
                    license {
                        name.set("GPL-3.0-or-later WITH Classpath-exception-2.0")
                        url.set("$projectUrl/blob/main/LICENSE-EXCEPTION")
                        distribution.set("repo")
                        comments.set(
                            "CodeAssist is GPL-3.0-or-later. This module adds the Classpath exception so a " +
                                "plugin that links against it may be released under any license."
                        )
                    }
                }
                developers {
                    developer {
                        id.set("tyron12233")
                        name.set("tyron12233")
                        url.set("https://github.com/tyron12233")
                    }
                }
                scm {
                    url.set(projectUrl)
                    connection.set("scm:git:$projectUrl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/tyron12233/CodeAssist.git")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("$projectUrl/issues")
                }
            }
        }
    }

    repositories {
        // A staging directory laid out exactly as the Central Portal expects an upload bundle. Produce it
        // with `./gradlew publishSpiPublicationToCentralBundleRepository`, then zip the contents of
        // build/central-bundle and upload that. Keeps the release a deliberate manual step and needs no
        // credentials in the build.
        maven {
            name = "centralBundle"
            url = uri(rootProject.layout.buildDirectory.dir("central-bundle"))
        }
    }
}

signing {
    // Central requires signatures; a local build and publishToMavenLocal must not. Signing therefore turns
    // itself on only when a key is supplied, so `./gradlew publishToMavenLocal` works on a clean checkout:
    //   ./gradlew publishSpiPublicationToCentralBundleRepository \
    //       -PsigningInMemoryKey="$(cat key.asc)" -PsigningInMemoryKeyPassword=...
    isRequired = false
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    if (!key.isNullOrBlank()) {
        useInMemoryPgpKeys(key, providers.gradleProperty("signingInMemoryKeyPassword").orNull.orEmpty())
        sign(publishing.publications)
    }
}
