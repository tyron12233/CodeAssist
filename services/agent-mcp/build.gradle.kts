plugins {
    kotlin("jvm")
    application
}

// agent-mcp — a Model Context Protocol server that exposes the agent's tools (builtinTools over an
// AgentWorkspace) to any MCP client over stdio JSON-RPC. Pure Kotlin/JVM, so it compiles and tests under
// CI_CORE_ONLY. The `application` plugin gives the module a standalone launcher (`./gradlew
// :agent-mcp:installDist` / `:agent-mcp:run`) that a client like Claude Desktop can spawn with a
// `--project <dir>` argument; hosts embedding the server (e.g. ide-core) call the builder directly with
// their engine-backed AgentWorkspace instead.
dependencies {
    api(project(":agent-api"))
    implementation(project(":agent-impl"))
    implementation(libs.mcp)
    implementation(libs.kotlinx.coroutines.core)
    // The MCP CLIENT (dev.ide.agent.mcp.client): the agent connecting OUT to servers the user adds. Not the SDK's
    // client, whose HTTP transports are built on java.net.http, which Android does not have.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("dev.ide.agent.mcp.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
