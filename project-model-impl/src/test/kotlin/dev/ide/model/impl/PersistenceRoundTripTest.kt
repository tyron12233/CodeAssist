package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.Exclusion
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
                    addDependency(LibraryDependency(
                        LibraryRef("com.squareup.okhttp3:okhttp:4.12.0"), DependencyScope.IMPLEMENTATION,
                        exclusions = listOf(Exclusion("com.squareup.okio", "okio"), Exclusion("org.jetbrains.kotlin", "*")),
                    ))
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
                assertEquals(
                    listOf(Exclusion("com.squareup.okio", "okio"), Exclusion("org.jetbrains.kotlin", "*")),
                    core.dependencies.filterIsInstance<LibraryDependency>()
                        .single { it.library.name == "com.squareup.okhttp3:okhttp:4.12.0" }.exclusions,
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

    /** addContentRoot appends typed roots (creating a set when missing); removeContentRoot drops one; both round-trip. */
    @Test
    fun addAndRemoveContentRootRoundTrips() {
        val dir = Files.createTempDirectory("codeassist-roots")
        val platform = PlatformCore()
        platform.registerTestTypes()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(JavaFacetCodec))
            val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit()
            }
            val project = store.workspace.projects.single()
            project.beginModification().apply {
                addModule("lib", javaLib).apply {
                    addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE))))
                    addContentRoot("main", "src/main/resources", setOf(ContentRole.RESOURCE)) // new typed root
                    addContentRoot("main", "src/main/kotlin", setOf(ContentRole.SOURCE))       // kotlin sources
                    addContentRoot("custom", "src/custom/proto", setOf(ContentRole.RESOURCE))   // creates the set
                }
                commit()
            }

            val moduleRoot = dir.resolve("lib").toString()
            fun rel(p: String) = p.substringAfter(moduleRoot).trimStart('/', '\\').replace('\\', '/')
            fun rootsOf(set: String) = store.workspace.projects.single().modules.first { it.name == "lib" }
                .sourceSets.first { it.name == set }.contentRoots

            assertEquals(
                setOf("src/main/java", "src/main/resources", "src/main/kotlin"),
                rootsOf("main").map { rel(it.dir.path) }.toSet(),
            )
            assertEquals(setOf(ContentRole.RESOURCE), rootsOf("custom").single().roles)

            store.save()
            val platform2 = PlatformCore().also { it.registerTestTypes() }
            try {
                val reloaded = ProjectModel.open(dir, platform2, FacetCodecRegistry().register(JavaFacetCodec))
                val lib = reloaded.workspace.projects.single().modules.first { it.name == "lib" }
                val mainRoles = lib.sourceSets.first { it.name == "main" }.contentRoots.associate { rel(it.dir.path) to it.roles }
                assertEquals(setOf(ContentRole.RESOURCE), mainRoles["src/main/resources"])
                assertEquals(setOf(ContentRole.SOURCE), mainRoles["src/main/kotlin"])
                assertEquals("src/custom/proto", rel(lib.sourceSets.first { it.name == "custom" }.contentRoots.single().dir.path))
            } finally {
                platform2.dispose()
            }

            // removeContentRoot drops just that root.
            store.workspace.projects.single().beginModification().apply {
                module(ModuleId("lib")).removeContentRoot("main", "src/main/resources"); commit()
            }
            assertEquals(
                setOf("src/main/java", "src/main/kotlin"),
                rootsOf("main").map { rel(it.dir.path) }.toSet(),
            )
        } finally {
            platform.dispose()
            dir.toFile().deleteRecursively()
        }
    }
}
