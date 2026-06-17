plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// layout-preview-impl — the owned layout-preview engine behind layout-preview-api. Pure kotlin/jvm (never
// links android.jar): the resource value resolver (over android-support's merged ResourceRepository + SDK
// attr metadata), the tolerant XML → RenderNode inflater (over lang-xml), the built-in renderers, the
// PreviewEngine, and the ASM BridgeRemapper that reparents a user view onto the owned base. The platform
// Bridge bases (device android.view.View subclasses; the desktop Robolectric/Skiko shim) live in the IDE
// shells, which is where android.jar / the shim are on the classpath.
dependencies {
    api(project(":layout-preview-api"))
    implementation(project(":android-support"))     // ResourceRepository, AndroidSdkMetadata, AndroidColor
    implementation(project(":lang-xml"))             // tolerant XmlTreeParser → neutral XML DOM
    implementation(project(":project-model-api"))    // Module / Workspace for resource discovery
    implementation(libs.ow2.asm)                     // BridgeRemapper: reparent + remap user bytecode
    implementation(libs.ow2.asm.tree)
    implementation(libs.ow2.asm.commons)             // ClassRemapper / Remapper

    testImplementation(libs.kotlinx.coroutines.test)
}
