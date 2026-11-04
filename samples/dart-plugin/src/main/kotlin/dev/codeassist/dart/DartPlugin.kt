package dev.codeassist.dart

import dev.ide.build.RUN_TASK_PROVIDER_EP
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lsp.LANGUAGE_SERVER_EP
import dev.ide.model.FileIconExtensionPoint
import dev.ide.model.ModuleTypeExtensionPoint
import dev.ide.model.sync.PROJECT_IMPORTER_EP
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.platform.notify.USER_MESSAGES
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginRegistration

/**
 * The engine facet: Dart as a language (the analysis server), as a kind of project (a pub package), and as
 * something to run.
 *
 * `register` runs at startup and does not touch the SDK: unpacking it is for the first thing that needs
 * Dart (opening a `.dart` file, or Run), not for the way to the IDE's first frame.
 */
class DartPlugin : Plugin {

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("DartPlugin")
        val sdk = DartSdk(reg, log, reg.appServices.getServiceOrNull(USER_MESSAGES))
        val pub = Pub(sdk)

        reg.register(FILE_TYPE_EP, FileTypeMapping(DartFileIcons.SUFFIXES, LanguageId(LANGUAGE)))
        reg.register(LANGUAGE_SERVER_EP, DartLanguageServer(sdk, pub, reg.logger("DartServer")))

        reg.register(ModuleTypeExtensionPoint, DartModuleType)
        reg.register(ProjectTemplateExtensionPoint, DartConsoleTemplate())
        reg.register(PROJECT_IMPORTER_EP, PubImporter)
        reg.register(FileIconExtensionPoint, DartFileIconProvider)

        reg.register(RUN_TASK_PROVIDER_EP, DartRunTaskProvider(sdk, pub))
        log.info("registered; the Dart SDK is prepared on first use")
    }

    companion object {
        /** The language id, shared by the file-type mapping, the editor profile and the language server. */
        const val LANGUAGE = "dart"
    }
}
