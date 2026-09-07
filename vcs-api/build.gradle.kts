plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// vcs-api -> platform-core. The version-control SPI: the repository/branch/commit/status model, the
// provider extension point the host resolves a checkout through, and the account/credential/forge ports
// the sign-in flow is built on. No engine dependency, so a host can model VCS state without JGit on the
// classpath. ExtensionPoint and Disposable appear in the SPI, so platform-core is `api`.
dependencies {
    api(project(":platform-core"))
}
