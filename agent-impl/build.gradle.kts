import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// agent-impl — the agent engine behind agent-api (see docs/agentic-coding.md): OkHttp/SSE transport, the
// Anthropic/OpenAI/Gemini providers, the agent loop, the built-in tools, and the CodeAssist system prompt.
dependencies {
    api(project(":agent-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}

// The Antigravity OAuth client id/secret are injected at build time from a gitignored `agent.properties` at the
// repo root, so they are never committed (GitHub push protection would block them). The generated file lands in
// build/ (already ignored); when the properties file is absent (CI, other contributors) the values are empty and
// the Antigravity provider simply can't authenticate.
val generateAntigravitySecrets by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/antigravity/kotlin")
    val propsFile = rootProject.file("agent.properties")
    inputs.files(propsFile).withPropertyName("agentProperties").optional()
    outputs.dir(outDir)
    doLast {
        val props = Properties()
        if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }
        val clientId = props.getProperty("antigravity.clientId", "")
        val clientSecret = props.getProperty("antigravity.clientSecret", "")
        val target = outDir.get().file("dev/ide/agent/impl/AntigravitySecrets.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            "package dev.ide.agent.impl\n\n" +
                "/** Build-time-injected Antigravity OAuth client (see agent-impl/build.gradle.kts); empty when\n" +
                " *  `agent.properties` is absent. NOT committed. */\n" +
                "internal object AntigravitySecrets {\n" +
                "    const val CLIENT_ID = \"" + clientId + "\"\n" +
                "    const val CLIENT_SECRET = \"" + clientSecret + "\"\n" +
                "}\n",
        )
    }
}

kotlin.sourceSets.named("main") { kotlin.srcDir(generateAntigravitySecrets) }
