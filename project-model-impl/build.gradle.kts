plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// project-model-impl implements project-model-api. Consumers of the impl need the api types, so the
// dependency is `api`. platform-core (model read/write lock, message bus, extension registry) arrives
// transitively through project-model-api. Persistence is pure JDK NIO + hand-rolled JSON/TOML, so no
// extra runtime dependency is needed.
dependencies {
    api(project(":project-model-api"))
}
