package dev.ide.android.support.resources

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidSupport
import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AAR resource merge: the dependency resolver explodes an `.aar`'s `res/` beside its `classes.jar`, so a
 * `res/` sibling of any library jar on the module's compile classpath is folded into the module's resources
 * — making `R.color.brand_primary` from a library resolve, and `@color/brand_primary` validate.
 */
class AarResourceMergeTest {

    @Test
    fun mergesAarResourcesFromALibraryJarSibling() {
        val dir = createTempDirectory("aar-merge")
        // a fake exploded AAR (as the resolver lays it out): classes.jar with a res/ sibling.
        val exploded = dir.resolve("aarcache/material-1.0-exploded")
        Files.createDirectories(exploded.resolve("res/values"))
        Files.writeString(exploded.resolve("classes.jar"), "")
        Files.writeString(exploded.resolve("res/values/colors.xml"),
            """<resources><color name="brand_primary">#FF0000</color></resources>""")

        val platform = PlatformCore()
        val moduleTypes = ModuleTypeRegistry(platform.extensions)
        val codecs = FacetCodecRegistry()
        AndroidSupport.register(moduleTypes, codecs)
        val store = ProjectModel.open(dir, platform, codecs)
        try {
            store.workspace.libraryTable.create("material").apply {
                kind = LibraryKind.AAR
                addClassesRoot(store.vfs.fileFor(exploded.resolve("classes.jar")))
                commit()
            }
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", moduleTypes.resolve("android-app")).apply {
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("material"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            val app = store.workspace.projects.single().modules.first { it.name == "app" }
            val repo = AndroidResources.repository(app, store.workspace)
            assertTrue(repo.has(ResourceType.COLOR, "brand_primary"), "AAR color should merge in: ${repo.names(ResourceType.COLOR)}")
        } finally {
            platform.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun explodesAndMergesALocalAarFile() {
        val dir = createTempDirectory("aar-local")
        // a real .aar (zip) added directly as a library (the classpath entry IS the .aar, not an exploded dir).
        val aar = dir.resolve("libs/widget.aar")
        Files.createDirectories(aar.parent)
        ZipOutputStream(Files.newOutputStream(aar)).use { zos ->
            zos.putNextEntry(ZipEntry("classes.jar")); zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/values/strings.xml"))
            zos.write("""<resources><string name="widget_label">W</string></resources>""".toByteArray())
            zos.closeEntry()
        }

        val platform = PlatformCore()
        val moduleTypes = ModuleTypeRegistry(platform.extensions)
        val codecs = FacetCodecRegistry()
        AndroidSupport.register(moduleTypes, codecs)
        val store = ProjectModel.open(dir, platform, codecs)
        try {
            store.workspace.libraryTable.create("widget").apply {
                kind = LibraryKind.AAR
                addClassesRoot(store.vfs.fileFor(aar))
                commit()
            }
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", moduleTypes.resolve("android-app")).apply {
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("widget"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            val app = store.workspace.projects.single().modules.first { it.name == "app" }
            val repo = AndroidResources.repository(app, store.workspace)
            assertTrue(repo.has(ResourceType.STRING, "widget_label"), "local .aar should explode + merge: ${repo.names(ResourceType.STRING)}")
        } finally {
            platform.dispose()
            dir.toFile().deleteRecursively()
        }
    }
}
