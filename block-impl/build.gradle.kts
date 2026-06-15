plugins {
    alias(libs.plugins.kotlin.jvm)
}

// block-impl — the projection engine behind block-api. It turns a
// ParsedFile into a BlockTree by generic "gap carving" (interleave the literal source between child
// ranges as chrome with each child projected into a slot), with a per-language BlockMapping deciding
// which kinds decompose vs collapse to an editable text slot, and an opaque fallback for the rest. A
// BlockEdit is compiled to a minimal DocumentEdit so untouched code survives byte-for-byte.
//
// Backend-neutral: it consumes only the language-api DOM, so it works for any backend. The Java mapping
// (statements + key expressions) is here; tests exercise it against the real JDT DOM (lang-jdt).
dependencies {
    api(project(":block-api"))

    // Tests project + edit ACTUAL parsed Java via JDT's tolerant DOM (the tree the IDE really uses).
    testImplementation(project(":lang-jdt"))
    testImplementation(libs.kotlinx.coroutines.core)
}
