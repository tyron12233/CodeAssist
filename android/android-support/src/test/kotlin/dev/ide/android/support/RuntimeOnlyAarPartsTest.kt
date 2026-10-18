package dev.ide.android.support

import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.testkit.testEnv
import dev.ide.vfs.local.fileFor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An AAR a library MODULE declares as `implementation` is on the app's runtime classpath but not its compile
 * classpath. Its manifest, resources and assets still belong to the app, as AGP merges them from the runtime
 * classpath. They used to be read from the compile classpath only, so Compose resources'
 * `AndroidContextProvider` never reached the merged manifest of a KMP wizard app (its `components-resources`
 * comes through `shared`), and the app crashed with "Android context is not initialized".
 */
class RuntimeOnlyAarPartsTest {

    @Test
    fun aLibraryModulesAarShipsItsManifestAndAssetsButStaysOffTheCompileClasspath() {
        testEnv("runtime-aar") { env ->
            val dir = env.dir
            val exploded = dir.resolve("caches/components-resources-android-1.12.1-exploded")
            val classes = write(exploded.resolve("classes.jar"), "")
            val manifest = write(exploded.resolve("AndroidManifest.xml"), """
                <manifest package="org.jetbrains.compose.components.resources"><application>
                  <provider android:name="org.jetbrains.compose.resources.AndroidContextProvider"/>
                </application></manifest>
            """.trimIndent())
            write(exploded.resolve("assets/marker.txt"), "x")

            val store = ProjectModel.open(dir, env.platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(env.platform.extensions)
            types.register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            types.register(AndroidLibModuleType, AndroidSupport.PLUGIN)
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            val coord = "org.jetbrains.compose.components:components-resources:1.12.1"
            store.workspace.libraryTable.create(coord).apply {
                kind = LibraryKind.AAR; addClassesRoot(store.vfs.fileFor(classes)); commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("shared", types.resolve("android-lib")).apply {
                    putFacet(AndroidFacet(namespace = "com.example.shared", compileSdk = 34, minSdk = 24, targetSdk = 34, isApplication = false))
                    addDependency(LibraryDependency(LibraryRef(coord), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            val sharedId = store.workspace.projects.single().modules.single().id
            store.workspace.projects.single().beginModification().apply {
                addModule("app", types.resolve("android-app")).apply {
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(ModuleDependency(sharedId, DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            val app = store.workspace.projects.single().modules.first { it.name == "app" }
            val libs = AndroidLibraries.resolve(app, dir.resolve("exploded"))

            assertTrue(manifest in libs.aarManifests, "the AAR's manifest is merged: ${libs.aarManifests}")
            assertTrue(exploded.resolve("assets") in libs.assetsDirs, "its assets are packaged: ${libs.assetsDirs}")
            assertTrue(classes in libs.dexJars, "its code is dexed: ${libs.dexJars}")
            assertFalse(classes in libs.compileJars, "but not compiled against, as `implementation` intends")
        }
    }

    private fun write(p: Path, text: String): Path {
        Files.createDirectories(p.parent); Files.writeString(p, text); return p
    }
}
