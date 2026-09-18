package dev.ide.deps.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleModuleParserTest {

    @Test
    fun parsesVariantsAttributesDependenciesFilesAndAvailableAt() {
        val json = """
        {
          "formatVersion": "1.1",
          "component": { "group": "org.x", "module": "lib", "version": "1.0" },
          "variants": [
            {
              "name": "androidApiElements",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "java-api",
                "org.jetbrains.kotlin.platform.type": "androidJvm",
                "org.gradle.jvm.version": 17,
                "org.gradle.jvm.environment": "android"
              },
              "available-at": { "group": "org.x", "module": "lib-android", "version": "1.0", "url": "../../lib-android/1.0/lib-android-1.0.module" }
            },
            {
              "name": "jvmRuntimeElements",
              "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime", "org.jetbrains.kotlin.platform.type": "jvm" },
              "dependencies": [
                { "group": "org.y", "module": "dep", "version": { "requires": "2.0" }, "excludes": [ { "group": "org.z", "module": "ex" } ] }
              ],
              "files": [
                { "name": "lib-jvm-1.0.jar", "url": "lib-jvm-1.0.jar" },
                { "name": "lib-jvm-1.0-sources.jar", "url": "lib-jvm-1.0-sources.jar" }
              ]
            }
          ]
        }
        """.trimIndent()

        val module = GradleModuleParser.parse(json.toByteArray())
        assertNotNull(module)
        assertEquals("org.x", module.group)
        assertEquals("lib", module.name)
        assertEquals("1.0", module.version)
        assertEquals(2, module.variants.size)

        val android = module.variants[0]
        assertEquals("androidJvm", android.attributes["org.jetbrains.kotlin.platform.type"])
        assertEquals("17", android.attributes["org.gradle.jvm.version"], "numeric attribute coerced to string")
        assertNotNull(android.availableAt)
        assertEquals("lib-android", android.availableAt!!.name)
        assertEquals("1.0", android.availableAt!!.version)

        val jvm = module.variants[1]
        val dep = jvm.dependencies.single()
        assertEquals("org.y", dep.group)
        assertEquals("dep", dep.name)
        assertEquals("2.0", dep.version)
        assertEquals(setOf(GA("org.z", "ex")), dep.excludes)
        assertEquals(listOf("lib-jvm-1.0.jar", "lib-jvm-1.0-sources.jar"), jvm.files.map { it.name })
    }

    @Test
    fun coercesBooleanAttribute() {
        val json = """{"variants":[{"name":"v","attributes":{"org.gradle.usage":"java-api","some.flag":true}}]}"""
        val module = GradleModuleParser.parse(json.toByteArray())
        assertNotNull(module)
        assertEquals("true", module.variants.single().attributes["some.flag"])
    }

    @Test
    fun malformedBodyReturnsNull() {
        assertNull(GradleModuleParser.parse("not json at all".toByteArray()))
        assertNull(GradleModuleParser.parse("{ this is broken".toByteArray()))
    }

    @Test
    fun missingVariantsYieldsEmptyList() {
        val module = GradleModuleParser.parse("""{"formatVersion":"1.1","component":{"group":"g","module":"m","version":"1"}}""".toByteArray())
        assertNotNull(module)
        assertTrue(module.variants.isEmpty())
    }
}
