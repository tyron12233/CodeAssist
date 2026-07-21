package dev.ide.android.support

/**
 * The Maven dependencies a [BuildFeatures] flag pulls in when it is switched on. AGP adds these to a module
 * automatically when the matching `buildFeatures` flag is set (the ViewBinding runtime, the Compose runtime
 * + tooling); the IDE does the same from the Build Features toggle so an enabled feature actually compiles
 * and runs. The single source of truth, shared by the toggle and the Jetpack Compose project template.
 */
object AndroidFeatureDependencies {

    /** The ViewBinding runtime — `androidx.viewbinding.ViewBinding`, which every generated binding implements. */
    val VIEW_BINDING: List<String> = listOf("androidx.databinding:viewbinding:8.7.3")

    /** The Compose runtime + preview tooling (the set the Jetpack Compose template declares). */
    val COMPOSE: List<String> = listOf(
        "androidx.activity:activity-compose:1.9.3",
        "androidx.compose.ui:ui:1.7.5",
        "androidx.compose.foundation:foundation:1.7.5",
        "androidx.compose.material3:material3:1.3.1",
        "androidx.compose.ui:ui-tooling-preview:1.7.5",
    )

    /** The kotlin-parcelize runtime — carries the `@Parcelize`/`Parceler` annotations. Adding it puts the
     *  `@Parcelize` marker on the classpath, which is what activates the bundled parcelize compiler plugin. */
    val PARCELIZE: List<String> = listOf("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.4.0")

    /** The kotlinx.serialization runtime — carries `kotlinx.serialization.Serializable` (+ the JSON format).
     *  Adding it puts the `@Serializable` marker on the classpath, which activates the bundled serialization
     *  compiler plugin (and the editor's synthetic `serializer()` support). */
    val SERIALIZATION: List<String> = listOf("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
