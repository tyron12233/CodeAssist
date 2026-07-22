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
