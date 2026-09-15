package dev.codeassist.ndk

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Facet
import dev.ide.model.FacetData
import dev.ide.model.IconTarget
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryTable
import dev.ide.model.ModifiableModule
import dev.ide.model.Module
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.OrderEntry
import dev.ide.model.Project
import dev.ide.model.ProjectId
import dev.ide.model.ProjectModelTransaction
import dev.ide.model.ProjectSettings
import dev.ide.model.SdkRef
import dev.ide.model.SdkTable
import dev.ide.model.SourceSetTemplate
import dev.ide.model.Variant
import dev.ide.model.Workspace
import dev.ide.model.WorkspaceTransaction
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.platform.ContentHash
import dev.ide.platform.ServiceKey
import dev.ide.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two templates, which are two because the projects are: a C++ library has no Android in it, and a
 * native activity is an Android app with no Java in it.
 *
 * Both halves of what a template does are checked -- the files it writes AND the model it builds -- because
 * the model half is the one that fails silently. A template that writes perfect sources into a module it
 * never declared produces a project whose files belong to nothing: no analysis, no completion, and nothing
 * in the navigator, which renders the declared roots rather than the file system.
 */
class NdkTemplatesTest {

    // ---- the plain C/C++ library -------------------------------------------------------------------

    @Test
    fun `the library template writes a header, a source and a readme, and nothing Android`() {
        val s = generate(NativeCppTemplate())
        assertEquals(
            setOf("app/src/main/cpp/native-lib.h", "app/src/main/cpp/native-lib.cpp", "README.md"),
            s.files.keys,
        )
        assertTrue(s.files.keys.none { it.endsWith("AndroidManifest.xml") }, "a plain library has no manifest")
    }

    @Test
    fun `the library module is a plain java-lib carrying only the ndk facet`() {
        val module = generate(NativeCppTemplate()).module()
        assertEquals("app", module.name)
        assertEquals("java-lib", module.typeId)
        assertEquals(setOf("ndk"), module.facets.keys, "a plain library declares no [android] table")
        assertEquals(false, module.facets.getValue("ndk")["nativeActivity"])
        assertEquals(emptyList<String>(), module.facets.getValue("ndk")["linkLibraries"], "nothing Android is linked")
    }

    /**
     * The declared root, which is what makes the generated files part of a module at all. Naming the
     * directory in `[ndk]` is not the same statement: that tells the build what to compile, while this is
     * what the IDE resolves a file to its module through.
     */
    @Test
    fun `the library module declares src main cpp as a source root`() {
        assertEquals(
            listOf(Triple("main", "src/main/cpp", setOf(ContentRole.SOURCE))),
            generate(NativeCppTemplate()).module().contentRoots,
        )
    }

    @Test
    fun `the header and the source agree on the symbols`() {
        val s = generate(NativeCppTemplate(), "libraryName" to "engine")
        val header = s.files.getValue("app/src/main/cpp/engine.h")
        val source = s.files.getValue("app/src/main/cpp/engine.cpp")
        assertTrue("int engine_add(int a, int b);" in header, header)
        assertTrue("int engine_add(int a, int b) {" in source, source)
        assertTrue("""#include "engine.h"""" in source, "the source includes its own header: $source")
        // Without this the C++ symbols are mangled and nothing can find them by name.
        assertTrue("""extern "C" {""" in header, header)
    }

    @Test
    fun `choosing C generates C, and the standard lands in the C key rather than the C++ one`() {
        val s = generate(NativeCppTemplate(), "language" to "c", "standard" to "c11")
        assertTrue("app/src/main/cpp/native-lib.c" in s.files.keys, s.files.keys.toString())
        assertTrue("app/src/main/cpp/native-lib.cpp" !in s.files.keys, "the C project has no C++ file")
        val ndk = s.module().facets.getValue("ndk")
        assertEquals("c11", ndk["cStandard"])
        assertEquals(NdkFacet().cppStandard, ndk["cppStandard"], "the other language keeps its default")
        assertTrue("#include <string>" !in s.files.getValue("app/src/main/cpp/native-lib.c"), "no C++ headers in C")
    }

    @Test
    fun `choosing a C++ standard lands in the C++ key`() {
        val ndk = generate(NativeCppTemplate(), "standard" to "c++20").module().facets.getValue("ndk")
        assertEquals("c++20", ndk["cppStandard"])
        assertEquals(NdkFacet().cStandard, ndk["cStandard"])
    }

    // ---- the native activity ------------------------------------------------------------------------

    @Test
    fun `the activity template writes a manifest, a string and the native entry point, and no java`() {
        val s = generate(NativeActivityTemplate(), "libraryName" to "game")
        assertEquals(
            setOf(
                "app/src/main/cpp/game.cpp",
                "app/src/main/AndroidManifest.xml",
                "app/src/main/res/values/strings.xml",
                "README.md",
            ),
            s.files.keys,
        )
        val cpp = s.files.getValue("app/src/main/cpp/game.cpp")
        assertTrue("void android_main(android_app* app)" in cpp, cpp)
        assertTrue("#include <android_native_app_glue.h>" in cpp)
    }

    @Test
    fun `the activity module is an android-app carrying both facets`() {
        val module = generate(NativeActivityTemplate()).module()
        assertEquals("android-app", module.typeId)
        assertEquals(setOf("ndk", "android"), module.facets.keys)
        assertEquals(true, module.facets.getValue("ndk")["nativeActivity"])
        assertEquals(listOf("log", "android"), module.facets.getValue("ndk")["linkLibraries"])
        // The [android] table is another plugin's, written by name; these are the keys its codec reads.
        val android = module.facets.getValue("android")
        assertEquals("com.example.demo", android["namespace"])
        assertEquals(26, android["minSdk"])
        assertEquals(NdkTemplateSupport.COMPILE_SDK, android["compileSdk"])
    }

    @Test
    fun `the activity module declares its cpp root beside the android source sets`() {
        assertEquals(
            listOf(Triple("main", "src/main/cpp", setOf(ContentRole.SOURCE))),
            generate(NativeActivityTemplate()).module().contentRoots,
        )
    }

    /**
     * One value in two files. A mismatch produces an app that installs and dies on launch with "Unable to
     * load native library", which no compiler error would have caught.
     */
    @Test
    fun `the manifest lib_name and the facet library name are the same setting`() {
        val s = generate(NativeActivityTemplate(), "libraryName" to "engine")
        val manifest = s.files.getValue("app/src/main/AndroidManifest.xml")
        assertTrue("""android:name="android.app.lib_name" android:value="engine"""" in manifest, manifest)
        assertTrue("""android:hasCode="false"""" in manifest, manifest)
        assertTrue("android.app.NativeActivity" in manifest)
        assertEquals("engine", s.module().facets.getValue("ndk")["libraryName"])
    }

    @Test
    fun `the chosen minSdk reaches both facets`() {
        val module = generate(NativeActivityTemplate(), "minSdk" to "29").module()
        assertEquals(29, module.facets.getValue("android")["minSdk"])
        assertEquals(29, module.facets.getValue("ndk")["minSdk"], "the native code is compiled against it too")
    }

    // ---- both ---------------------------------------------------------------------------------------

    @Test
    fun `the two templates are two entries in the gallery, in different categories`() {
        val library = NativeCppTemplate()
        val activity = NativeActivityTemplate()
        assertNotEquals(library.id, activity.id)
        assertEquals(TemplateCategory.OTHER, library.category)
        assertEquals(TemplateCategory.ANDROID, activity.category, "a native activity IS an Android app")
        // Neither offers a choice that turns it into the other one.
        assertTrue(library.parameters().none { it.key == "kind" })
        assertTrue(activity.parameters().none { it.key == "kind" })
    }

    @Test
    fun `a blank or unusable library name falls back rather than producing a file called dot cpp`() {
        assertTrue("app/src/main/cpp/native-lib.cpp" in generate(NativeCppTemplate(), "libraryName" to "   ").files.keys)
        assertTrue("app/src/main/cpp/native-lib.cpp" in generate(NativeCppTemplate(), "libraryName" to "///").files.keys)
    }

    @Test
    fun `a library name that is not a C identifier still produces compilable symbols`() {
        assertEquals("native_lib", NdkTemplateSupport.symbolPrefix("native-lib"))
        assertEquals("my_game_2", NdkTemplateSupport.symbolPrefix("my game.2"))
        assertEquals("lib_2d", NdkTemplateSupport.symbolPrefix("2d"), "a symbol may not start with a digit")
    }

    /** A path separator in the name would write outside the module; the name is reduced before it is used. */
    @Test
    fun `a library name cannot carry a path`() {
        val files = generate(NativeCppTemplate(), "libraryName" to "../../etc/passwd").files
        assertTrue(files.keys.none { it.contains("..") }, files.keys.toString())
    }

    private fun generate(
        template: dev.ide.model.template.ProjectTemplate,
        vararg args: Pair<String, String>,
    ): RecordingScaffold {
        val scaffold = RecordingScaffold()
        template.generate(
            scaffold,
            TemplateArgs(
                mapOf(TemplateArgs.NAME to "Demo", TemplateArgs.PACKAGE to "com.example.demo") + args,
            ),
        )
        return scaffold
    }
}

/** The icon mapping, which the tree (through the engine) and the tabs (in the UI layer) both read. */
class NdkFileIconsTest {

    @Test
    fun `each family claims its own suffixes`() {
        assertEquals(NdkFileIcons.CPP, NdkFileIcons.idFor("main.cpp"))
        assertEquals(NdkFileIcons.CPP, NdkFileIcons.idFor("main.cc"))
        assertEquals(NdkFileIcons.C, NdkFileIcons.idFor("glue.c"))
        assertEquals(NdkFileIcons.HEADER, NdkFileIcons.idFor("engine.h"))
        assertEquals(NdkFileIcons.HEADER, NdkFileIcons.idFor("engine.hpp"))
    }

    /** `.cc` ends with `c` but not with `.c`, which is the whole reason the sets can be checked in any order. */
    @Test
    fun `a C++ suffix is not read as C, and a C++ header is not read as a C header`() {
        assertNotEquals(NdkFileIcons.C, NdkFileIcons.idFor("main.cc"))
        assertNotEquals(NdkFileIcons.HEADER, NdkFileIcons.idFor("main.hh").let { NdkFileIcons.C })
        assertEquals(NdkFileIcons.HEADER, NdkFileIcons.idFor("main.hh"))
    }

    @Test
    fun `a file this plugin knows nothing about is left alone`() {
        assertNull(NdkFileIcons.idFor("Main.java"))
        assertNull(NdkFileIcons.idFor("module.toml"))
        assertNull(NdkFileIcons.idFor("cpp"))
    }

    @Test
    fun `the suffix match ignores case, since a file name's case is the user's`() {
        assertEquals(NdkFileIcons.CPP, NdkFileIcons.idFor("MAIN.CPP"))
    }

    @Test
    fun `the provider answers for files and defers on everything else`() {
        assertEquals(NdkFileIcons.CPP, NdkFileIconProvider.iconFor(IconTarget.File("main.cpp", null)))
        assertNull(NdkFileIconProvider.iconFor(IconTarget.File("AndroidManifest.xml", null)))
        assertNull(NdkFileIconProvider.iconFor(IconTarget.PackageDir("com.example")))
        assertNull(NdkFileIconProvider.iconFor(IconTarget.Directory("cpp", emptySet())))
    }

    /** Above the built-in fallback, below android-support: nothing about a C file outranks the manifest. */
    @Test
    fun `the provider sits between the default and the android rules`() {
        assertTrue(NdkFileIconProvider.priority in 1..99, "was ${NdkFileIconProvider.priority}")
    }
}

// ---------------------------------------------------------------------------------------------------
// The scaffold the templates build against, recording instead of touching a disk or a real model.
// ---------------------------------------------------------------------------------------------------

internal class RecordingScaffold : ProjectScaffold {
    val files = LinkedHashMap<String, String>()
    val modules = ArrayList<RecordedModule>()
    var projectName: String? = null
    var buildSystem: BuildSystemId? = null

    fun module(): RecordedModule = modules.single()

    override val workspace: Workspace = RecordingWorkspace(this)
    override val rootDir: VirtualFile = FakeDir("/workspace")
    override val languageLevel = LanguageLevel.JAVA_17
    override fun moduleType(id: String): ModuleType = FakeModuleType(id)
    override fun writeText(relPath: String, content: String) {
        // The real scaffold trims the indent and appends a newline; the templates do both themselves, so
        // recording the text verbatim is what they actually produce.
        files[relPath] = content
    }
    override fun writeBytes(relPath: String, bytes: ByteArray) = throw UnsupportedOperationException()
}

internal class RecordedModule(val name: String, val typeId: String) : ModifiableModule {
    val contentRoots = ArrayList<Triple<String, String, Set<ContentRole>>>()
    val sourceSets = ArrayList<String>()
    val facets = LinkedHashMap<String, Map<String, Any?>>()

    override var languageLevel: LanguageLevel = LanguageLevel.JAVA_17
    override var dirRelPath: String = name
    override var sdk: SdkRef? = null
    override var outputRelPath: String = "build/classes"

    override fun addDependency(entry: OrderEntry) = Unit
    override fun removeDependency(entry: OrderEntry) = Unit
    override fun addSourceSet(template: SourceSetTemplate) { sourceSets.add(template.name) }
    override fun addContentRoot(sourceSetName: String, dirRelPath: String, roles: Set<ContentRole>) {
        contentRoots.add(Triple(sourceSetName, dirRelPath, roles))
    }
    override fun removeContentRoot(sourceSetName: String, dirRelPath: String) = Unit
    override fun <T : Facet> putFacet(facet: T) = throw UnsupportedOperationException(
        "a template scaffolds another plugin's module type, so it writes tables, not facet objects",
    )
    override fun putFacetData(data: FacetData) { facets[data.tomlTable] = data.values }
}

private class RecordingWorkspace(private val out: RecordingScaffold) : Workspace {
    private val created = ArrayList<Project>()
    override val projects: List<Project> get() = created
    override val libraryTable: LibraryTable get() = throw UnsupportedOperationException()
    override val sdkTable: SdkTable get() = throw UnsupportedOperationException()
    override fun <T : Any> service(key: ServiceKey<T>): T = throw UnsupportedOperationException()

    override fun beginModification(): WorkspaceTransaction = object : WorkspaceTransaction {
        override fun addProject(name: String, buildSystem: BuildSystemId, rootDir: VirtualFile): Project {
            out.projectName = name
            out.buildSystem = buildSystem
            return RecordingProject(name, out).also { created.add(it) }
        }
        override fun removeProject(id: ProjectId) = Unit
        override fun setBuildSystem(id: ProjectId, buildSystem: BuildSystemId) = Unit
        override fun commit() = Unit
        override fun dispose() = Unit
    }
}

private class RecordingProject(override val name: String, private val out: RecordingScaffold) : Project {
    override val id = ProjectId(name)
    override val rootDir: VirtualFile = FakeDir("/workspace")
    override val buildSystemId = BuildSystemId.NATIVE
    override val modules: List<Module> get() = emptyList()
    override val variants: List<Variant> get() = emptyList()
    override val settings: ProjectSettings get() = throw UnsupportedOperationException()
    override val libraryTable: LibraryTable get() = throw UnsupportedOperationException()

    override fun beginModification(): ProjectModelTransaction = object : ProjectModelTransaction {
        override fun addModule(name: String, type: ModuleType): ModifiableModule =
            RecordedModule(name, type.id).also { out.modules.add(it) }
        override fun removeModule(id: ModuleId) = Unit
        override fun module(id: ModuleId): ModifiableModule = out.modules.first { it.name == id.value }
        override fun commit() = Unit
        override fun dispose() = Unit
    }
}

private class FakeModuleType(override val id: String) : ModuleType {
    override val displayName = id
    override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
    override fun defaultFacets() = emptyList<dev.ide.model.FacetTemplate>()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
}

private class FakeDir(override val path: String) : VirtualFile {
    override val name: String get() = path.substringAfterLast('/')
    override val isDirectory get() = true
    override val exists get() = true
    override val length get() = 0L
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash(): ContentHash = throw UnsupportedOperationException()
    override fun readBytes(): ByteArray = ByteArray(0)
    override fun readText(): CharSequence = ""
}
