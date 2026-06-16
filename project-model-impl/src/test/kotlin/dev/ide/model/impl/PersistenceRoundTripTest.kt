package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.PlatformDependency
import dev.ide.model.SdkDependency
import dev.ide.model.SdkRef
import dev.ide.model.SourceSetTemplate
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Builds a 3-module project in code, saves, reloads, and verifies an identical snapshot, plus spot
 * checks that the reloaded views resolve module types, facets, libraries and SDKs.
 */
class PersistenceRoundTripTest {

    @Test
    fun threeModuleProjectSavesAndReloadsToIdenticalSnapshot() {
        val dir = Files.createTempDirectory("codeassist-roundtrip")
        val platform = PlatformCore()
        platform.registerTestTypes()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(JavaFacetCodec))
            val types = ModuleTypeRegistry(platform.extensions)
            val javaLib = types.resolve("java-lib")
            val javaCli = types.resolve("java-cli")

            // one native project rooted at the workspace root
            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }

            // shared <- core (api) ; core also pulls okhttp + has a facet ; app -> core (impl) + jdk sdk
            store.workspace.projects.single().beginModification().apply {
                addModule("shared", javaLib).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    addSourceSet(
                        SourceSetTemplate(
                            "main", DependencyScope.IMPLEMENTATION,
                            mapOf(
                                "src/main/java" to setOf(ContentRole.SOURCE),
                                "src/main/resources" to setOf(ContentRole.RESOURCE),
                            ),
                        ),
                    )
                }
                addModule("core", javaLib).apply {
                    addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE))))
                    addDependency(ModuleDependency(ModuleId("shared"), DependencyScope.API, exported = true))
                    addDependency(LibraryDependency(LibraryRef("com.squareup.okhttp3:okhttp:4.12.0"), DependencyScope.IMPLEMENTATION))
                    addDependency(PlatformDependency(Coordinate("androidx.compose", "compose-bom", "2024.09.00")))
                    putFacet(JavaFacet(listOf("dagger.internal.codegen.ComponentProcessor"), preview = false))
                }
                addModule("app", javaCli).apply {
                    addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE))))
                    addSourceSet(SourceSetTemplate("test", DependencyScope.TEST_IMPLEMENTATION, mapOf("src/test/java" to setOf(ContentRole.SOURCE))))
                    addDependency(ModuleDependency(ModuleId("core"), DependencyScope.IMPLEMENTATION))
                    addDependency(SdkDependency(SdkRef("jdk-17"), DependencyScope.COMPILE_ONLY))
                }
                commit()
            }

            // a workspace-scoped library and an SDK
            store.workspace.libraryTable.create("com.squareup.okhttp3:okhttp:4.12.0").apply {
                kind = LibraryKind.JAR
                addClassesRoot(store.vfs.fileFor(dir.resolve("libs/okhttp-4.12.0.jar")))
                commit()
            }
            store.replaceSdks(listOf(SdkData("jdk-17", listOf("/opt/jdk-17/lib/jrt-fs.jar"), buildToolsPath = null)))

            val before = store.data
            store.save()

            // reload from disk in a completely fresh platform/store
            val platform2 = PlatformCore()
            platform2.registerTestTypes()
            try {
                val store2 = ProjectModel.open(dir, platform2, FacetCodecRegistry().register(JavaFacetCodec))

                assertEquals(before, store2.data) // identical snapshot

                val reloaded = store2.workspace.projects.single()
                assertEquals(listOf("app", "core", "shared"), reloaded.modules.map { it.name })

                val core = reloaded.modules.first { it.name == "core" }
                assertEquals("java-lib", core.type.id)
                assertEquals(
                    listOf("dagger.internal.codegen.ComponentProcessor"),
                    core.facets.get(JavaFacet.KEY)?.annotationProcessors,
                )
                assertEquals(
                    Coordinate("androidx.compose", "compose-bom", "2024.09.00"),
                    core.dependencies.filterIsInstance<PlatformDependency>().single().bom,
                )
                assertEquals(LibraryKind.JAR, store2.workspace.libraryTable.byName("com.squareup.okhttp3:okhttp:4.12.0")?.kind)
                assertEquals("jdk-17", store2.workspace.sdkTable.byName("jdk-17")?.name)
            } finally {
                platform2.dispose()
            }
        } finally {
            platform.dispose()
            dir.toFile().deleteRecursively()
        }
    }
}
