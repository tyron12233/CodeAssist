plugins {
    alias(libs.plugins.kotlin.jvm)
}

// spi-compat — the published SPI's binary surface, pinned.
//
// A plugin is compiled against the published artifacts and linked against the IDE's own copies of those
// classes: `compileOnly` coordinates at build time, the host's classloader as its parent at runtime. So the
// contract a plugin actually depends on is not the SPI's SOURCE but its bytecode — every class, method,
// constructor and field name, with its exact descriptor. Kotlin lets that contract break while the source
// stays compatible: a top-level function moves between files and its facade class changes with it, a
// constructor becomes a factory function, a defaulted parameter is added and the `$default` bridge changes
// shape. None of it fails the build, none of it fails at load, and the plugin dies with a
// `NoSuchMethodError` the first time the user touches the feature.
//
// This module has no production code. It carries the one test that reads the compiled surface of every
// published module off its own test classpath and holds it against a checked-in baseline, so a member that
// a plugin can already name cannot quietly stop existing.
//
// The baselines live next to the code they describe (`<module>/api/<module>.api`), so the diff that says
// "this changes what a plugin links against" lands in the same review as the change that caused it.
dependencies {
    // Every published module, which is what puts its compiled classes on this module's test classpath. The
    // test fails if one of them is missing here, so the list cannot drift from the set of modules that
    // apply the publishing convention. `:plugin-bom` is the exception: it is a `java-platform` and has no
    // classes to pin.
    //
    // The bytecode is READ, never loaded, so a module's `compileOnly` dependencies (Compose, in
    // `:plugin-ui-api`) do not have to be resolvable here for its surface to be readable.
    testImplementation(project(":plugin-api"))
    testImplementation(project(":plugin-ui-api"))
    testImplementation(project(":model-api"))
    testImplementation(project(":platform-core"))
    testImplementation(project(":vfs-api"))
    testImplementation(project(":project-model-api"))
    testImplementation(project(":language-api"))
    testImplementation(project(":index-api"))
    testImplementation(project(":analysis-api"))
    testImplementation(project(":build-api"))
    testImplementation(project(":block-api"))
    testImplementation(project(":interp-api"))
    testImplementation(project(":vcs-api"))
    testImplementation(project(":agent-api"))

    // Reading class files without loading them.
    testImplementation(libs.ow2.asm)
}

tasks.test {
    // `-Dspi.updateBaselines=true` reaches the test worker only if it is forwarded, the same way the
    // regression suites' own flag is forwarded in the root build.
    System.getProperty("spi.updateBaselines")?.let { systemProperty("spi.updateBaselines", it) }
}
