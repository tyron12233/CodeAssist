package dev.ide.ios

import dev.ide.model.Coordinate
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.random.Random

/**
 * Which Compose Multiplatform release a project's androidx Compose runs as in a preview: the one whose
 * Android variant IS the declared androidx artifact, read from the releases' module metadata.
 */
class IosPreviewDependenciesTest {
    // One root per test: what one finds is persisted under it, and must not answer for another.
    private val root = IosFiles.join(NSTemporaryDirectory().trimEnd('/'), "ios-preview-deps-${Random.nextLong().toULong().toString(16)}")

    private fun module(androidVersion: String): ByteArray = """
        {
          "formatVersion": "1.1",
          "component": { "group": "org.jetbrains.compose.ui", "module": "ui", "version": "x" },
          "variants": [
            {
              "name": "androidReleaseRuntimeElements-published",
              "attributes": { "org.jetbrains.kotlin.platform.type": "androidJvm", "org.gradle.usage": "java-runtime" },
              "available-at": { "url": "../../../androidx/compose/ui/ui-android/$androidVersion/ui-android-$androidVersion.module",
                                "group": "androidx.compose.ui", "module": "ui-android", "version": "$androidVersion" }
            },
            {
              "name": "desktopRuntimeElements-published",
              "attributes": { "org.jetbrains.kotlin.platform.type": "jvm", "org.gradle.usage": "java-runtime" },
              "available-at": { "url": "../../ui-desktop/x/ui-desktop-x.module",
                                "group": "org.jetbrains.compose.ui", "module": "ui-desktop", "version": "x" }
            }
          ]
        }
    """.trimIndent().encodeToByteArray()

    /** The current form: the Android variant depends on the androidx artifact rather than redirecting to it. */
    private fun moduleByDependency(androidVersion: String): ByteArray = """
        {
          "formatVersion": "1.1",
          "component": { "group": "org.jetbrains.compose.ui", "module": "ui", "version": "x" },
          "variants": [
            {
              "name": "androidRuntimeElements-published",
              "attributes": { "org.jetbrains.kotlin.platform.type": "jvm", "org.gradle.usage": "java-runtime" },
              "dependencies": [
                { "group": "androidx.annotation", "module": "annotation", "version": { "requires": "1.9.1" } },
                { "group": "androidx.compose.ui", "module": "ui", "version": { "requires": "$androidVersion" } }
              ]
            }
          ]
        }
    """.trimIndent().encodeToByteArray()

    private fun maven(): FixtureMaven {
        val maven = FixtureMaven()
        val base = "${IosDependencies.REPOSITORIES.first().url}/org/jetbrains/compose/ui/ui"
        maven.put("$base/maven-metadata.xml", """
            <metadata><groupId>org.jetbrains.compose.ui</groupId><artifactId>ui</artifactId>
            <versioning><versions><version>1.9.0</version><version>1.10.0</version><version>1.11.1</version><version>1.12.0</version></versions></versioning></metadata>
        """.trimIndent().encodeToByteArray())
        maven.put("$base/1.9.0/ui-1.9.0.module", module("1.9.0"))
        maven.put("$base/1.10.0/ui-1.10.0.module", module("1.10.0"))
        maven.put("$base/1.11.1/ui-1.11.1.module", module("1.10.5"))
        maven.put("$base/1.12.0/ui-1.12.0.module", moduleByDependency("1.11.2"))
        return maven
    }

    @Test
    fun anAndroidxComposeArtifactRunsAsTheReleaseThatAliasesIt() = runTest {
        val deps = IosPreviewDependencies(root, maven())
        assertEquals(
            Coordinate("org.jetbrains.compose.ui", "ui", "1.11.1"),
            deps.desktopEquivalent(Coordinate("androidx.compose.ui", "ui", "1.10.5")),
        )
        assertEquals(
            Coordinate("org.jetbrains.compose.ui", "ui", "1.10.0"),
            deps.desktopEquivalent(Coordinate("androidx.compose.ui", "ui", "1.10.0")),
        )
        assertEquals(
            Coordinate("org.jetbrains.compose.ui", "ui", "1.12.0"),
            deps.desktopEquivalent(Coordinate("androidx.compose.ui", "ui", "1.11.2")),
            "a release that depends on the androidx artifact aliases it too",
        )
    }

    /**
     * A Compose Multiplatform library whose desktop build depends on androidx's runtime, whose own desktop
     * build is a stub: the preview must end up with Compose Multiplatform's runtime and not the stub, though
     * nothing the project declared names the runtime at all.
     */
    @Test
    fun aTransitiveAndroidxComposeArtifactIsSwappedToo() = runTest {
        val maven = FixtureMaven()
        val central = IosDependencies.REPOSITORIES[0].url
        val google = IosDependencies.REPOSITORIES[1].url
        fun path(group: String, name: String, version: String) = "${group.replace('.', '/')}/$name/$version/$name-$version"
        fun jvmVariant(deps: String = "", files: String = "", at: String? = null) = """
            { "name": "desktopRuntimeElements-published",
              "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime",
                              "org.jetbrains.kotlin.platform.type": "jvm", "org.gradle.jvm.environment": "standard-jvm" }
              ${if (at != null) ", \"available-at\": $at" else ""}
              ${if (deps.isNotEmpty()) ", \"dependencies\": [$deps]" else ""}
              ${if (files.isNotEmpty()) ", \"files\": [$files]" else ""} }
        """
        fun androidVariant(dep: String) = """
            { "name": "releaseRuntimeElements-published",
              "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime",
                              "org.jetbrains.kotlin.platform.type": "androidJvm", "org.gradle.jvm.environment": "android" },
              "dependencies": [$dep] }
        """
        fun dep(group: String, name: String, version: String) = """{ "group": "$group", "module": "$name", "version": { "requires": "$version" } }"""
        fun at(group: String, name: String, version: String) = """{ "url": "x", "group": "$group", "module": "$name", "version": "$version" }"""
        fun module(group: String, name: String, version: String, variants: String) =
            """{ "formatVersion": "1.1", "component": { "group": "$group", "module": "$name", "version": "$version" }, "variants": [$variants] }""".encodeToByteArray()
        fun pom(group: String, name: String, version: String) =
            "<project><groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version></project>".encodeToByteArray()
        fun publishModule(base: String, group: String, name: String, version: String, variants: String, jar: Boolean = false) {
            val p = "$base/${path(group, name, version)}"
            maven.put("$p.module", module(group, name, version, variants))
            maven.put("$p.pom", pom(group, name, version))
            if (jar) maven.put("$p.jar", "$group:$name".encodeToByteArray())
        }
        fun versions(base: String, group: String, name: String, vararg v: String) = maven.put(
            "$base/${group.replace('.', '/')}/$name/maven-metadata.xml",
            "<metadata><versioning><versions>${v.joinToString("") { "<version>$it</version>" }}</versions></versioning></metadata>".encodeToByteArray(),
        )
        val m3 = "org.jetbrains.compose.material3"
        val rt = "org.jetbrains.compose.runtime"
        // What the project declares, and the Compose Multiplatform release that is it.
        versions(central, m3, "material3", "1.9.0")
        publishModule(central, m3, "material3", "1.9.0",
            androidVariant(dep("androidx.compose.material3", "material3", "1.4.0")) + "," + jvmVariant(at = at(m3, "material3-desktop", "1.9.0")))
        publishModule(central, m3, "material3-desktop", "1.9.0",
            jvmVariant(deps = dep("androidx.compose.runtime", "runtime", "1.10.5"), files = """{ "name": "m.jar", "url": "material3-desktop-1.9.0.jar" }"""), jar = true)
        // androidx's runtime, whose desktop build is the stub.
        publishModule(google, "androidx.compose.runtime", "runtime", "1.10.5", jvmVariant(at = at("androidx.compose.runtime", "runtime-desktop", "1.10.5")))
        publishModule(google, "androidx.compose.runtime", "runtime-desktop", "1.10.5",
            jvmVariant(files = """{ "name": "r.jar", "url": "runtime-desktop-1.10.5.jar" }"""), jar = true)
        // Compose Multiplatform's runtime: the real one.
        versions(central, rt, "runtime", "1.10.5")
        publishModule(central, rt, "runtime", "1.10.5",
            androidVariant(dep("androidx.compose.runtime", "runtime", "1.10.5")) + "," + jvmVariant(at = at(rt, "runtime-desktop", "1.10.5")))
        publishModule(central, rt, "runtime-desktop", "1.10.5",
            jvmVariant(files = """{ "name": "r.jar", "url": "runtime-desktop-1.10.5.jar" }"""), jar = true)
        maven.publishStdlib()

        val jars = IosPreviewDependencies(root, maven).classpath(listOf(Coordinate("androidx.compose.material3", "material3", "1.4.0")))
        assertTrue(jars.any { "org/jetbrains/compose/runtime/runtime-desktop" in it }, "Compose Multiplatform's runtime: $jars")
        assertTrue(jars.none { "androidx/compose/runtime" in it }, "and not androidx's stub: $jars")
        assertTrue(jars.any { "material3-desktop" in it }, "with the declared library: $jars")
    }

    /** A match is kept on disk: the next open (a new instance over the same project) reads nothing. */
    @Test
    fun aMatchFoundOnceIsNotLookedForAgain() = runTest {
        val androidx = Coordinate("androidx.compose.ui", "ui", "1.10.5")
        val expected = Coordinate("org.jetbrains.compose.ui", "ui", "1.11.1")
        assertEquals(expected, IosPreviewDependencies(root, maven()).desktopEquivalent(androidx))

        val again = maven()
        assertEquals(expected, IosPreviewDependencies(root, again).desktopEquivalent(androidx))
        assertEquals(emptyList(), again.requested, "the second open answers from what the first found")
    }

    /**
     * Hundreds of releases, and the right one found by reading a handful of their modules rather than all of
     * them newest first, which is what made a preview's first resolve take a minute and a half.
     */
    @Test
    fun theReleaseIsFoundWithoutReadingEveryRelease() = runTest {
        val maven = FixtureMaven()
        val base = "${IosDependencies.REPOSITORIES.first().url}/org/jetbrains/compose/ui/ui"
        // CMP 1.0.0..1.0.199, each aliasing androidx 2.0.<same patch>; the first ten predate the alias.
        val releases = (0 until 200).map { "1.0.$it" }
        maven.put("$base/maven-metadata.xml",
            "<metadata><versioning><versions>${releases.joinToString("") { "<version>$it</version>" }}</versions></versioning></metadata>".encodeToByteArray())
        for ((i, v) in releases.withIndex()) {
            if (i >= 10) maven.put("$base/$v/ui-$v.module", moduleByDependency("2.0.$i"))
        }

        val deps = IosPreviewDependencies(root, maven)
        assertEquals(Coordinate("org.jetbrains.compose.ui", "ui", "1.0.37"), deps.desktopEquivalent(Coordinate("androidx.compose.ui", "ui", "2.0.37")))
        val modules = maven.requested.count { it.endsWith(".module") }
        assertTrue(modules <= 20, "read $modules modules for one lookup")

        // The same androidx version for a sibling artifact is tried at the release just found, first.
        maven.requested.clear()
        val geometry = "${IosDependencies.REPOSITORIES.first().url}/org/jetbrains/compose/ui/ui-geometry"
        maven.put("$geometry/1.0.37/ui-geometry-1.0.37.module",
            moduleByDependency("2.0.37").decodeToString().replace("\"module\": \"ui\",", "\"module\": \"ui-geometry\",").encodeToByteArray())
        assertEquals(
            Coordinate("org.jetbrains.compose.ui", "ui-geometry", "1.0.37"),
            deps.desktopEquivalent(Coordinate("androidx.compose.ui", "ui-geometry", "2.0.37")),
        )
        assertEquals(1, maven.requested.size, "one module read: ${maven.requested}")
    }

    @Test
    fun anythingElseIsLeftForTheVariantChoice() = runTest {
        val deps = IosPreviewDependencies(root, maven())
        assertNull(deps.desktopEquivalent(Coordinate("androidx.lifecycle", "lifecycle-runtime-compose", "2.9.4")))
        assertNull(deps.desktopEquivalent(Coordinate("androidx.compose.ui", "ui", "0.0.1")), "no release aliases it")
    }
}
