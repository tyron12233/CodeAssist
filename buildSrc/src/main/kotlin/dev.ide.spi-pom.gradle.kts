// The publishing identity every artifact a plugin author resolves shares: the coordinate, the SPI version,
// the POM the Central Portal requires, the staging repository, and opt-in signing.
//
// Applied by `dev.ide.spi-publish` for the SPI jars, and directly by `:plugin-bom`, which is a
// `java-platform` and so cannot apply that one (a project is a library or a platform, not both). Publications
// are configured as they are created, so each of those decides what it publishes and this decides what the
// POM says about it.

plugins {
    `maven-publish`
    signing
}

val projectUrl = "https://github.com/tyron12233/CodeAssist"

/** The published SPI version, taken from the constant the Create-Project template also emits. */
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

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("CodeAssist ${project.name}")
            description.set(
                when (project.name) {
                    "plugin-api" ->
                        "The CodeAssist plugin SPI: the Plugin entry point, its manifest, the " +
                            "registration API, and the action model."
                    "plugin-ui-api" ->
                        "The CodeAssist plugin UI SPI: what a plugin implements to contribute a tool " +
                            "window, a screen, or an overlay, and the context those bodies render against."
                    "platform-core" ->
                        "The CodeAssist platform substrate the plugin SPI exposes: extension points, " +
                            "scoped services, the message bus, logging, and the settings model."
                    "project-model-api" ->
                        "The CodeAssist project model: module types, project templates, facets and " +
                            "their codecs, and file icons."
                    "language-api" ->
                        "The CodeAssist language SPI: the neutral syntax tree, resolution, file types, " +
                            "completion, formatting, folding, and a language's compilation context."
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
                    "interp-api" ->
                        "The CodeAssist interpreter SPI: lowering a project's Kotlin and running it, or its " +
                            "compiled classes, from a plugin, for a preview or a run of the plugin's own."
                    "plugin-bom" ->
                        "Versions for everything a CodeAssist plugin compiles against: the SPI artifacts " +
                            "and the Compose the IDE provides at runtime."
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
