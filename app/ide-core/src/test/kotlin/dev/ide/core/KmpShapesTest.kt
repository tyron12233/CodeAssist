package dev.ide.core

import dev.ide.core.kmp.KmpShapes
import dev.ide.lang.kotlin.build.KotlinFacetCodec
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalSourceSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** One multiplatform module, recorded for both its targets and switched between them with nothing lost. */
class KmpShapesTest {

    private val src = setOf(ContentRole.SOURCE)

    private fun shape(android: Boolean) = ExternalModule(
        name = "shared",
        dirRelPath = "shared",
        typeId = if (android) "android-lib" else "java-lib",
        sourceSets = listOf(
            ExternalSourceSet("main", DependencyScope.IMPLEMENTATION, linkedMapOf<String, Set<ContentRole>>().apply {
                put("src/commonMain/kotlin", src)
                put("src/jvmShared/kotlin", src)
                if (android) {
                    put("src/androidMain/kotlin", src)
                    put("src/androidMain/res", setOf(ContentRole.ANDROID_RES))
                } else put("src/desktopMain/kotlin", src)
            }),
            ExternalSourceSet("test", DependencyScope.TEST_IMPLEMENTATION, linkedMapOf("src/commonTest/kotlin" to src)),
        ),
        dependencies = listOfNotNull(
            ExternalLibrary("org.jetbrains.compose.runtime:runtime:1.11.1"),
            ExternalModuleRef("core"),
            if (android) ExternalLibrary("androidx.activity:activity-compose:1.13.0") else ExternalLibrary("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:1.11.1"),
        ),
        facets = listOfNotNull(
            if (android) ExternalFacet("android", mapOf("namespace" to "org.example.shared", "compileSdk" to 36L)) else null,
            ExternalFacet("composeResources", mapOf("package" to "p.generated.resources", "roots" to listOf("src/commonMain/composeResources"))),
            ExternalFacet(
                KotlinFacetCodec.tomlTable,
                mapOf("commonSourceRoots" to listOf("src/commonMain/kotlin", "src/jvmShared/kotlin", "build/compose-resources/kotlin")),
            ),
        ),
    )

    private fun ExternalModule.kotlin() = KotlinFacetCodec.decode(facets.single { it.table == KotlinFacetCodec.tomlTable }.values)
    private fun ExternalModule.mainRoots() = sourceSets.single { it.name == "main" }.roots.keys

    @Test
    fun recordsBothTargetsAndSwitchesLosslessly() {
        val described = KmpShapes.describe(
            listOf(
                KmpShapes.Shaped("android", KmpShapes.ANDROID, shape(android = true)),
                KmpShapes.Shaped("jvm", KmpShapes.JVM, shape(android = false)),
            ),
            active = "android",
        )
        val facet = described.kotlin()
        assertEquals("android", facet.activeTarget)
        assertEquals(listOf("src/commonMain/kotlin", "src/jvmShared/kotlin"), facet.shared.roots)
        assertEquals(listOf("lib:androidx.activity:activity-compose:1.13.0"), facet.targets.single { it.name == "android" }.dependencies.map { it.substringAfter(' ') })
        assertEquals(setOf("android"), facet.targets.single { it.name == "android" }.facets.keys)

        val jvm = assertNotNull(KmpShapes.materialize(described, "jvm"))
        assertEquals("java-lib", jvm.typeId)
        assertEquals(setOf("src/commonMain/kotlin", "src/jvmShared/kotlin", "src/desktopMain/kotlin"), jvm.mainRoots())
        assertNull(jvm.facets.firstOrNull { it.table == "android" }, "the Android config is put away")
        assertEquals(
            listOf("org.jetbrains.compose.runtime:runtime:1.11.1", "org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:1.11.1"),
            jvm.dependencies.filterIsInstance<ExternalLibrary>().map { it.coordinate },
        )
        assertEquals(listOf("core"), jvm.dependencies.filterIsInstance<ExternalModuleRef>().map { it.moduleName })
        assertEquals(
            listOf("src/commonMain/kotlin", "src/jvmShared/kotlin", "build/compose-resources/kotlin"),
            jvm.kotlin().commonSourceRoots,
        )

        // And back: the Android config the module had comes back with it.
        val back = assertNotNull(KmpShapes.materialize(jvm, "android"))
        assertEquals("android-lib", back.typeId)
        assertEquals(shape(android = true).mainRoots(), back.mainRoots())
        assertEquals(mapOf("namespace" to "org.example.shared", "compileSdk" to 36L), back.facets.single { it.table == "android" }.values)
        assertEquals(
            shape(android = true).dependencies.map(KmpShapes::encodeDependency).toSet(),
            back.dependencies.map(KmpShapes::encodeDependency).toSet(),
        )
    }

    @Test
    fun aSingleTargetModuleRecordsNothing() {
        val only = shape(android = true)
        assertEquals(only, KmpShapes.describe(listOf(KmpShapes.Shaped("android", KmpShapes.ANDROID, only)), "android"))
    }
}
