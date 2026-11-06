package dev.codeassist.dart

import dev.ide.build.RUN_TASK_PROVIDER_EP
import dev.ide.build.TEST_PROVIDER_EP
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
        val flutter = FlutterSdk(reg, sdk, reg.logger("Flutter"))
        val pub = Pub(sdk, flutter)
        tools = Tools(sdk, flutter, pub, reg.logger("FlutterPreview"), reg.messageBus)

        reg.register(FILE_TYPE_EP, FileTypeMapping(DartFileIcons.SUFFIXES, LanguageId(LANGUAGE)))
        reg.register(LANGUAGE_SERVER_EP, DartLanguageServer(sdk, pub, reg.logger("DartServer")))

        reg.register(ModuleTypeExtensionPoint, DartModuleType)
        reg.register(ProjectTemplateExtensionPoint, DartConsoleTemplate())
        reg.register(ProjectTemplateExtensionPoint, FlutterAppTemplate())
        reg.register(PROJECT_IMPORTER_EP, PubImporter)
        reg.register(FileIconExtensionPoint, DartFileIconProvider)

        reg.register(RUN_TASK_PROVIDER_EP, DartRunTaskProvider(sdk, pub, flutter))
        reg.register(TEST_PROVIDER_EP, DartTestProvider(sdk, pub))
        log.info("registered; the Dart SDK is prepared on first use")
    }

    /** What the UI facet's preview pane and the Flutter runs share: the SDKs the engine facet prepared, the bus. */
    class Tools(
        val sdk: DartSdk,
        val flutter: FlutterSdk,
        val pub: Pub,
        val log: dev.ide.platform.log.Logger,
        /** The app's bus, where editor saves are announced ([PackageWatcher]). */
        val bus: dev.ide.platform.MessageBus,
    )

    companion object {
        /**
         * Set by [register], read by the UI facet ([FlutterPreviewPane]). The two facets are loaded by one
         * classloader, so they share this.
         */
        @Volatile
        var tools: Tools? = null
            private set

        /** The language id, shared by the file-type mapping, the editor profile and the language server. */
        const val LANGUAGE = "dart"
    }
}
