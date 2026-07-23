plugins { kotlin("jvm") }

// agent-api — provider-neutral SPI for the in-IDE coding agent (see docs/agentic-coding.md).
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
