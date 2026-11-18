package dev.codeassist.csharp.editor


import android.util.Log
import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.util.TAG
import dev.ide.analysis.REANALYSIS
import dev.ide.analysis.Reanalysis
import dev.ide.build.RUN_SETTINGS
import dev.ide.lang.CompilationContext
import dev.ide.lang.CompilationContextProvider
import dev.ide.lang.LanguageId
import dev.ide.model.Module
import dev.ide.model.Workspace

/**
 * Provides workspace-level reanalysis for the C# plugin.
 *
 * Reanalysis is useful after a NuGet restore finishes. The source files
 * have not changed, but previously unresolved package types may now exist.
 *
 * Reanalysis is workspace-scoped, and the plugin does not receive a
 * workspace during registration. Instead, [CsContextProvider] obtains
 * it through contextFor() and passes it here.
 *
 * The provider is called while the editor prepares the analysis context
 * for a C# module, before checking its files.
 *
 * Until a workspace service is available, the plugin retains its previous
 * behavior and waits for a restore to finish before checking again.
 */
internal object CsWorkspace {

    @Volatile
    private var reanalysis: Reanalysis? = null

    /**
     * Reads the entry point chosen on a module's Run card, once a workspace has been seen on a host that
     * offers it (SPI 3.7.0). Null until then, and always on an older host.
     */
    @Volatile
    var entryPoints: ((Module) -> String?)? = null
        private set

    /** Whether a workspace has been found and reanalysis is available. */
    val available: Boolean
        get() = reanalysis != null

    /**
     * Finds and remembers the workspace's reanalysis service.
     *
     * Hosts older than SPI 3.5.0 may not provide this service. In that case,
     * the plugin continues without workspace-level reanalysis.
     */
    fun remember(workspace: Workspace) {
        if (entryPoints == null) {
            entryPoints = runCatching { CsRunSettings.entryPointsOf(workspace) }.getOrNull()
        }

        if (reanalysis != null) return

        reanalysis = runCatching {
            workspace.service(REANALYSIS)
        }.getOrNull()

        Log.d(
            TAG,
            "workspace: reanalysis ${if (available) "available" else "not available"}",
        )
    }

    /** Requests another analysis pass for all open files. */
    fun reanalyzeOpenFiles() {
        val service = reanalysis ?: return

        Log.d(TAG, "workspace: asking for the open files to be checked again")

        runCatching {
            service.reanalyzeOpenFiles()
        }
    }
}

/**
 * Registers workspace and module information during compilation context creation.
 *
 * The host retains responsibility for providing the actual compilation context.
 */
internal class CsContextProvider : CompilationContextProvider {

    override val languages = setOf(
        LanguageId(CSharpPlugin.LANGUAGE),
    )

    override fun contextFor(
        workspace: Workspace,
        module: Module,
        language: LanguageId,
        variant: Set<String>?,
    ): CompilationContext? {
        CsWorkspace.remember(workspace)
        CsModules.remember(module)

        return null
    }
}

/**
 * The one place that names `RunSettings`. A host older than SPI 3.7.0 has no such class, so the failure to
 * link stays inside the call that [CsWorkspace.remember] guards.
 */
private object CsRunSettings {
    fun entryPointsOf(workspace: Workspace): (Module) -> String? {
        val settings = workspace.service(RUN_SETTINGS)
        return { module -> settings.entryPoint(module) }
    }
}
