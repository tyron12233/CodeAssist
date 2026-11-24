package dev.codeassist.swift

import dev.ide.build.RUN_TASK_PROVIDER_EP
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.model.FileIconExtensionPoint
import dev.ide.model.ModuleTypeExtensionPoint
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.platform.notify.USER_MESSAGES
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginRegistration

/**
 * The engine facet: Swift as a kind of project (a Swift package) and as something to run.
 *
 * `register` runs at startup and does not touch the toolchain: unpacking it is for the first Run, not for the way to
 * the IDE's first frame.
 */
class SwiftPlugin : Plugin {

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("SwiftPlugin")
        val toolchain = SwiftToolchain(reg, reg.logger("SwiftToolchain"), reg.appServices.getServiceOrNull(USER_MESSAGES))

        reg.register(FILE_TYPE_EP, FileTypeMapping(SwiftFileIcons.SUFFIXES, LanguageId(LANGUAGE)))
        reg.register(ModuleTypeExtensionPoint, SwiftModuleType)
        reg.register(ProjectTemplateExtensionPoint, SwiftConsoleTemplate())
        reg.register(FileIconExtensionPoint, SwiftFileIconProvider)
        reg.register(RUN_TASK_PROVIDER_EP, SwiftRunTaskProvider(toolchain))
        log.info("registered; the Swift toolchain is prepared on first use")
    }

    companion object {
        /** The language id, shared by the file-type mapping and the editor profile. */
        const val LANGUAGE = "swift"
    }
}
