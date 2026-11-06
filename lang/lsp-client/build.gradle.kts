plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// lsp-client: the host's Language Server Protocol client. Not published: plugins see only :lsp-api.
//
// It turns each LanguageServerDefinition a plugin registers into the IDE's own extension points (a
// LanguageBackend, a DiagnosticProvider, completion, navigation, references, rename, code actions), all backed
// by one JSON-RPC session per (definition, workspace root). Nothing downstream knows a server is involved: the
// editor asks the language backend for semantic tokens exactly as it does Java's.
//
// Every adapter answers synchronously with a timeout and polls the engine's cancel token while it waits. The
// engine calls language services on its own thread through `runSync`, which drives a `suspend` SPI function
// assuming it never actually suspends, and a completion request preempts whatever that thread is doing; an
// adapter that parked on a coroutine or waited without polling would break both.
dependencies {
    api(project(":lsp-api"))
    api(project(":analysis-api"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.core)
}
