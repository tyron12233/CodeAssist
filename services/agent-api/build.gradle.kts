plugins {
    kotlin("jvm")
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// agent-api — provider-neutral SPI for the in-IDE coding agent (see docs/agentic-coding.md).
//
// `api`, not `implementation`: `LlmProvider.chat` returns a `Flow`, so a plugin implementing a provider or
// reading a stream needs coroutines on its own compile classpath. `:plugin-bom` pins the version, and it has
// to be the one the IDE bundles: a plugin's suspend code binds to the IDE's copy at runtime.
dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
