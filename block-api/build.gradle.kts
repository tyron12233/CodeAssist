plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// block-api — the SPI for the projectional (block-based) editor: a Scratch-style surface PROJECTED from
// the real, error-tolerant DOM the language backend produces. Blocks are a
// live view of the one shared document/AST, not a parallel model; every block edit becomes a surgical
// DocumentEdit so untouched code (and its comments/formatting) survives byte-for-byte.
//
// Everything here is expressed over the neutral DOM (language-api): a BlockMapping turns DomNode kinds
// into blocks, the projection anchors each block to its DOM node + text range, and a BlockEdit maps back
// to a minimal DocumentEdit. No engine logic lives here — the projection + edit pipeline is block-impl.
dependencies {
    // DomNode/ParsedFile/NodeKind/TextRange, DocumentEdit, LanguageId all appear in the public SPI → api.
    api(project(":language-api"))
}
