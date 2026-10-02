// jvm-vm-fixtures: programs :jvm-vm's tests interpret, compiled by the real Kotlin compiler (and the Compose
// plugin) so the VM is checked against the bytecode real libraries are made of. The tests call each fixture
// for real as the oracle, then run the same class through the VM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material3)
    // The desktop UI for this machine's OS, which carries skiko's native library: the real render the
    // interpreted one is compared with.
    implementation(compose.desktop.currentOs)
}
