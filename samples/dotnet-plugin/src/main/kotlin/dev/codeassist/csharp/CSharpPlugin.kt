package dev.codeassist.csharp

import dev.codeassist.csharp.build.CsBuildPlugin
import dev.codeassist.csharp.build.tasks.CsRunTaskProvider
import dev.codeassist.csharp.build.tests.CsTestRegistration
import dev.codeassist.csharp.completion.CsSemanticCompletion
import dev.codeassist.csharp.completion.CsSymbols
import dev.codeassist.csharp.completion.csCompletionContribution
import dev.codeassist.csharp.completion.csprojCompletionContribution
import dev.codeassist.csharp.editor.CsContextProvider
import dev.codeassist.csharp.editor.CsLanguageBackend
import dev.codeassist.csharp.editor.CsNavigationRegistration
import dev.codeassist.csharp.editor.CsRunGutter
import dev.codeassist.csharp.editor.CsSignatureHelp
import dev.codeassist.csharp.editor.CsUsingCleanup
import dev.codeassist.csharp.editor.CsprojAnalyzer
import dev.codeassist.csharp.editor.diagnostics.CsDiagnosticProvider
import dev.codeassist.csharp.editor.quickfixes.CsUsingFixProvider
import dev.codeassist.csharp.project.CsModuleType
import dev.codeassist.csharp.project.nuget.CsPackageRestorer
import dev.codeassist.csharp.project.nuget.CsPackageWatcher
import dev.codeassist.csharp.project.templates.CsConsoleTemplate
import dev.codeassist.csharp.project.templates.CsLibraryTemplate
import dev.codeassist.csharp.sdk.CsServer
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.ui.CsFileIconProvider
import dev.codeassist.csharp.ui.CsFileIcons
import dev.ide.analysis.ANALYZER_EP
import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.analysis.QUICK_FIX_PROVIDER_EP
import dev.ide.build.BUILD_PLUGIN_EP
import dev.ide.build.RUN_TASK_PROVIDER_EP
import dev.ide.lang.COMPILATION_CONTEXT_PROVIDER_EP
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LANGUAGE_BACKEND_EP
import dev.ide.lang.LanguageId
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.model.FileIconExtensionPoint
import dev.ide.model.ModuleTypeExtensionPoint
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.platform.notify.USER_MESSAGES
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.editor.EDITOR_DECORATION_EP

class CSharpPlugin : Plugin {

    /** Keeps the message-bus subscription alive; see [CsPackageWatcher]. */
    private var packageWatcher: CsPackageWatcher? = null

    // Registration

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("CsPlugin")
        val messages = reg.appServices.getServiceOrNull(USER_MESSAGES)
        val sdk = DotnetSdk(reg, messages)

        reg.register(FILE_TYPE_EP, FileTypeMapping(CsFileIcons.SUFFIXES, LanguageId(LANGUAGE)))

        // Project files have no language backend. The analyzer and completion contributor below provide
        // diagnostics and completion, similar to the host's support for .aidl files.
        reg.register(
            FILE_TYPE_EP,
            FileTypeMapping(listOf(".csproj", ".props", ".targets"), LanguageId("csproj")),
        )

        reg.register(ANALYZER_EP, CsprojAnalyzer())
        reg.register(ModuleTypeExtensionPoint, CsModuleType)
        reg.register(ProjectTemplateExtensionPoint, CsConsoleTemplate())
        reg.register(ProjectTemplateExtensionPoint, CsLibraryTemplate())
        reg.register(FileIconExtensionPoint, CsFileIconProvider)

        // Shared symbol table for completion, editor checks, and quick fixes.
        val symbols = CsSymbols(sdk)
        val roslynLog = reg.logger("CsRoslyn")
        val restorer = CsPackageRestorer(sdk, roslynLog, messages)

        reg.register(RUN_TASK_PROVIDER_EP, CsRunTaskProvider(sdk, log, restorer))

        // Restore packages when a .csproj file is saved, rather than waiting for the next editor check.
        val watcher = CsPackageWatcher(reg.messageBus, restorer)
        packageWatcher = watcher
        reg.onDispose {
            watcher.close()
            restorer.close()
        }

        // The check records unused usings; the remove-all quick fix reads the same results.
        val unusedUsings = CsUsingCleanup.Unused()
        reg.register(
            DIAGNOSTIC_PROVIDER_EP,
            CsDiagnosticProvider(sdk, roslynLog, symbols, restorer, unusedUsings),
        )
        reg.register(QUICK_FIX_PROVIDER_EP, CsUsingFixProvider(symbols, unusedUsings))

        reg.register(EDITOR_DECORATION_EP, CsRunGutter())

        // Completion and signature help use the same Roslyn-backed semantic completion service.
        val semantic = CsSemanticCompletion(sdk)
        reg.register(LANGUAGE_BACKEND_EP, CsLanguageBackend(CsSignatureHelp(semantic), semantic))

        // Go to Declaration needs an extension point an older host does not have.
        runCatching { CsNavigationRegistration.register(reg, semantic) }
            .onFailure { log.info("this IDE has no go-to-declaration for plugins; C# navigation is off") }

        // Provides the workspace needed to request checks for open files.
        reg.register(COMPILATION_CONTEXT_PROVIDER_EP, CsContextProvider())

        reg.register(BUILD_PLUGIN_EP, CsBuildPlugin(sdk))

        // Test running needs an extension point an older host does not have.
        runCatching { CsTestRegistration.register(reg, sdk) }
            .onFailure { log.info("this IDE has no test runners for plugins; C# tests are off") }
        reg.register(COMPLETION_CONTRIBUTOR_EP, csCompletionContribution(symbols, semantic))
        reg.register(COMPLETION_CONTRIBUTOR_EP, csprojCompletionContribution())

        log.info("registered; .NET is prepared on first use")
    }

    /**
     * Shuts down the resident Roslyn process, which the host does not manage.
     * It is a singleton of this plugin's classloader, and [ResidentProgram.shutdown]
     * can safely be called more than once.
     */
    override fun dispose() {
        CsServer.shutdown()
    }

    companion object {
        /** Language ID shared by the file-type mapping and editor profile. */
        const val LANGUAGE = "csharp"
    }
}