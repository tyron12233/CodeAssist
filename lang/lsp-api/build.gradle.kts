plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// lsp-api: the SPI a plugin uses to teach the IDE a language through a Language Server Protocol server.
//
// A plugin says how to START its server (a process it ships, or any pair of streams) and which languages it
// serves. The host's LSP client (:lsp-client, not published) does everything else: the JSON-RPC session, the
// document sync, and the adapters that turn the server's answers into the IDE's own editor features
// (diagnostics, completion, navigation, rename, formatting, folding, semantic colours, ...). So a plugin for
// Dart, Go or Rust is a definition, not a reimplementation of the protocol.
//
// Deliberately protocol-free: no JSON type and no LSP message class appears here, so the client can change
// how it speaks the protocol without breaking a compiled plugin. The two places a server wants raw JSON
// (initializationOptions, workspace/configuration) take it as text.
dependencies {
    // LanguageId and ExtensionPoint appear in the public SPI -> api.
    api(project(":language-api"))
}
