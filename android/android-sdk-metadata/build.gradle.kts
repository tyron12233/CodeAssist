plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Build-time generator (desktop only): reads a platform's `attrs.xml` + `android.jar` and emits the compact
// SDK metadata asset the XML completion contributor consumes. Not
// on any module's classpath — it's run on demand to (re)generate the asset, which is gitignored:
//   ./gradlew :android-sdk-metadata:run --args "<attrs.xml> <android.jar> <out.txt> <api>"
dependencies {
    implementation(project(":android-support"))
    implementation(libs.ow2.asm) // ClassReader for the View class hierarchy (no class loading)
    implementation(libs.android.r8) // BackportedMethodList: the java.* methods D8 backports, exempt from the minSdk check
    implementation(project(":lang-java")) // JavaSourceDocs: the lexer-based javadoc + parameter-name scan, for the docs pack
}

application {
    mainClass.set("dev.ide.android.support.metadata.gen.SdkMetadataGeneratorKt")
}

// The API-level table generator (api-versions.xml -> android-api-levels.bin):
//   ./gradlew :android-sdk-metadata:generateApiLevels --args "<api-versions.xml> <output.bin>"
tasks.register<JavaExec>("generateApiLevels") {
    group = "application"
    description = "Generates the bundled Android API-level table from a platform's api-versions.xml."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.ide.android.support.metadata.gen.ApiLevelsGeneratorKt")
    workingDir = rootDir
}

// The bundled docs pack generator (sources/android-N + android.jar -> android-docs.bin):
//   ./gradlew :android-sdk-metadata:generateAndroidDocs --args "<sources/android-N> <android.jar> <output.bin> <api>"
tasks.register<JavaExec>("generateAndroidDocs") {
    group = "application"
    description = "Generates the bundled Android parameter-name and javadoc pack from a platform's sources."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.ide.android.support.metadata.gen.AndroidDocsGeneratorKt")
    workingDir = rootDir
}

// The JDK API-level table generator (a JDK's lib/ct.sym -> jdk-api-levels.bin):
//   ./gradlew :android-sdk-metadata:generateJdkApiLevels --args "<ct.sym> <output.bin>"
tasks.register<JavaExec>("generateJdkApiLevels") {
    group = "application"
    description = "Generates the bundled JDK API-level table from a JDK's lib/ct.sym."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.ide.android.support.metadata.gen.JdkApiLevelsGeneratorKt")
    workingDir = rootDir
}
