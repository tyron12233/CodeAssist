package dev.codeassist.ndk

import dev.codeassist.ndk.jni.HostSupport
import dev.ide.model.ContentRole
import dev.ide.model.FacetData
import dev.ide.model.MODULE_SOURCES
import dev.ide.model.Module
import dev.ide.model.WORKSPACE_SERVICE
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.platform.log.Logger
import java.io.File

/** File-tree actions: switching C++ on for a module, and (on an older IDE) starting C/C++ files. */
internal object NdkModuleActions {

    /**
     * "Add C++ to Module" on a module that has no `[ndk]` table yet: declares `src/main/cpp` as a source root,
     * writes the table with its defaults, and starts a C++ file there. Needs SPI 3.1.0 (the action's
     * workspace and a facet that persists), so on an older IDE it is simply not shown.
     */
    fun addCpp(log: Logger) = SimpleAction(
        id = "dev.codeassist.ndk.addCpp",
        text = "Add C++ to Module",
        places = setOf(ActionPlaces.FILE_CONTEXT),
        iconId = "code",
        visible = { ctx -> moduleAt(ctx)?.let { it.facets.get(NdkFacet.KEY) == null } == true },
    ) { ctx ->
        val module = moduleAt(ctx) ?: return@SimpleAction ActionResult.message("Not a module")
        val sources = ctx.workspaceServices.getServiceOrNull(MODULE_SOURCES)
            ?: return@SimpleAction ActionResult.message("This IDE cannot change the module's configuration")
        val library = "native-lib"
        val dir = sources.addSourceRoot(module.name, "main", "cpp", setOf(ContentRole.SOURCE))
            ?: return@SimpleAction ActionResult.message("Could not add src/main/cpp to ${module.name}")
        val facet = NdkFacet(libraryName = library)
        if (!sources.setFacetData(module.name, FacetData("ndk", NdkFacetCodec.encode(facet)))) {
            return@SimpleAction ActionResult.message("Could not save the [ndk] table for ${module.name}")
        }
        val file = dir.resolve("$library.cpp").toFile()
        if (!file.exists()) file.writeText(STARTER)
        log.info("added C++ to ${module.name}")
        ActionResult(
            message = "C++ added to ${module.name}. Load it with System.loadLibrary(\"$library\").",
            effects = listOf(ActionEffect.OpenFile(file.path)),
        )
    }

    /** The module whose own directory the tree row is, through the action's workspace (SPI 3.1.0). */
    private fun moduleAt(ctx: ActionContext): Module? {
        val path = ctx.contextPath?.trimEnd('/') ?: return null
        val workspace = try {
            ctx.workspaceServices.getServiceOrNull(WORKSPACE_SERVICE)
        } catch (e: LinkageError) {
            null
        } ?: return null
        return workspace.projects.flatMap { it.modules }.firstOrNull { it.dir.path.trimEnd('/') == path }
    }

    /**
     * New C++ class / New C/C++ file as tree actions, for an IDE that has no New-menu templates. There is no
     * name prompt on that path, so the files get placeholder names, picked to be free, for the user to rename.
     */
    fun legacyNewFileActions(): List<SimpleAction> {
        if (HostSupport.spi31) return emptyList()
        return listOf(
            SimpleAction(
                id = "dev.codeassist.ndk.legacyNewClass",
                text = "New C++ Class",
                places = setOf(ActionPlaces.FILE_CONTEXT),
                iconId = "code",
                visible = { ctx -> dirOf(ctx)?.let(CppNewFiles::isNativeDirectory) == true },
            ) { ctx ->
                val dir = dirOf(ctx) ?: return@SimpleAction ActionResult.NONE
                val name = freeName(dir, "NewClass") { listOf("$it.h", "$it.cpp") }
                val files = CppNewFiles.cppClass(name)
                ActionResult(effects = files.mapIndexed { i, (rel, text) -> ActionEffect.CreateFile("$dir/$rel", text, open = i == 1) })
            },
            SimpleAction(
                id = "dev.codeassist.ndk.legacyNewSource",
                text = "New C/C++ Source File",
                places = setOf(ActionPlaces.FILE_CONTEXT),
                iconId = "code",
                visible = { ctx -> dirOf(ctx)?.let(CppNewFiles::isNativeDirectory) == true },
            ) { ctx ->
                val dir = dirOf(ctx) ?: return@SimpleAction ActionResult.NONE
                val name = freeName(dir, "untitled") { listOf("$it.cpp") }
                val (rel, text) = CppNewFiles.source(name)
                ActionResult.effect(ActionEffect.CreateFile("$dir/$rel", text, open = true))
            },
        )
    }

    private fun dirOf(ctx: ActionContext): String? {
        val path = ctx.contextPath ?: return null
        val f = File(path)
        return (if (f.isDirectory) f else f.parentFile)?.path
    }

    private fun freeName(dir: String, base: String, files: (String) -> List<String>): String {
        var n = 1
        while (true) {
            val name = if (n == 1) base else "$base$n"
            if (files(name).none { File(dir, it).exists() }) return name
            n++
        }
    }

    private val STARTER = """
        #include <jni.h>

        // Load this library from Java or Kotlin with System.loadLibrary("native-lib"), then declare a
        // `native` method (Java) or an `external fun` (Kotlin). The editor flags each one that has no
        // function here, and its quick fix writes the function with the name the JVM looks for.
    """.trimIndent() + "\n"
}
