plugins {
    `java-platform`
    id("dev.ide.spi-pom")
}

// plugin-bom — every version a plugin has to get right, as one coordinate.
//
// A plugin compiles against the SPI artifacts and, if it contributes UI, against the exact Compose the IDE
// bundles: its `@Composable` bodies bind to the IDE's own copy at runtime, so a version the host does not
// provide is a link error the author sees only after installing the APK on a device. Nine coordinates plus
// four Compose ones is a lot of numbers to keep right by hand, and the failure lands late. With this, they
// are one number:
//
//     dependencies {
//         compileOnly(platform("io.github.tyron12233:plugin-bom:<version>"))
//         compileOnly("io.github.tyron12233:plugin-api")
//         compileOnly("io.github.tyron12233:plugin-ui-api")
//         compileOnly("androidx.compose.runtime:runtime")
//     }
//
// The Create-Project template keeps writing explicit versions instead: it generates for the on-device build,
// and its pins come from the same constants this file reads, so the two cannot disagree. This is for a
// plugin built with Gradle outside the IDE, which is where a hand-written version goes wrong.
//
// A BOM is pom-packaged, so unlike the SPI jars it needs no sources or javadoc artifact for the Central
// Portal to accept it.

/** What the Create-Project template pins a scaffolded plugin to, read from the template itself. */
val templateSource = rootProject
    .file("ide-core/src/main/kotlin/dev/ide/core/templates/PluginTemplate.kt")
    .readText()

fun templatePin(constant: String): String {
    val match = Regex("""const val $constant = "([^"]+)"""").find(templateSource)
    return requireNotNull(match) {
        "$constant not found in PluginTemplate.kt. The Compose versions in this BOM are read from the " +
            "template's own constants so a plugin built outside the IDE and one scaffolded inside it " +
            "cannot be pinned to different Compose."
    }.groupValues[1]
}

val composeVersion = templatePin("COMPOSE")
val material3Version = templatePin("MATERIAL3")

dependencies {
    constraints {
        // The published SPI. Project dependencies rather than literal coordinates, so a module that changes
        // its name or its group cannot be left behind here. PluginBomTest checks the list is complete.
        api(project(":plugin-api"))
        api(project(":plugin-ui-api"))
        api(project(":platform-core"))
        api(project(":project-model-api"))
        api(project(":language-api"))
        api(project(":analysis-api"))
        api(project(":index-api"))
        api(project(":build-api"))
        api(project(":vfs-api"))
        api(project(":interp-api"))

        // The Compose the IDE provides at runtime. `androidx.compose.*` rather than the Compose
        // Multiplatform coordinates, because a plugin is an Android app and that is what those map to there.
        api("androidx.compose.runtime:runtime:$composeVersion")
        api("androidx.compose.foundation:foundation:$composeVersion")
        api("androidx.compose.ui:ui:$composeVersion")
        api("androidx.compose.material3:material3:$material3Version")
    }
}

publishing {
    publications {
        create<MavenPublication>("spi") {
            from(components["javaPlatform"])
        }
    }
}
