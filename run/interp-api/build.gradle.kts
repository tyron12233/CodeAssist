plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// interp-api -> platform-core (the service key), vfs-api (paths are VirtualFile-free here, but a session's
// classpath and the lowering request are file-shaped and the SPI stays consistent with the others).
//
// Deliberately NOT `:interp-core` or `:jvm-interp`: this module is the narrowed surface over both, so that
// neither the resolver-to-interpreter contract (`ResolvedTree`) nor the VM's ASM-typed model becomes plugin
// ABI. See docs/plugin-interpreter.md.
dependencies {
    api(project(":platform-core"))
}
