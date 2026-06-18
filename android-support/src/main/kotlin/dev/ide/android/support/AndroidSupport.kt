package dev.ide.android.support

import dev.ide.android.support.templates.AndroidAppTemplate
import dev.ide.android.support.templates.AndroidLibraryTemplate
import dev.ide.android.support.templates.JetpackComposeAppTemplate
import dev.ide.android.support.templates.MaterialYouAppTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.FileIconRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectTemplateRegistry
import dev.ide.platform.PluginId

/**
 * The android-support plugin entry point: contributes the Android module types and the [AndroidFacet]
 * codec to a host's registries. A host (`:ide-core`, `:ide-android`) calls this once at startup so
 * `module.toml` files of type `android-app`/`android-lib` load with a resolvable type and a decodable
 * facet, and the project-structure UI can create new Android modules.
 */
object AndroidSupport {
    val PLUGIN = PluginId("android-support")

    fun register(moduleTypes: ModuleTypeRegistry, codecs: FacetCodecRegistry) {
        moduleTypes.register(AndroidAppModuleType, PLUGIN)
        moduleTypes.register(AndroidLibModuleType, PLUGIN)
        codecs.register(AndroidFacetCodec)
    }

    /** Contribute the Android tree icons (res/assets/manifest/android-module) to a host's icon registry. */
    fun registerIcons(icons: FileIconRegistry) {
        icons.register(AndroidFileIconProvider, PLUGIN)
    }

    /** Contribute the Android project templates (app, Material You app, library) to a host's Create-Project gallery. */
    fun registerTemplates(templates: ProjectTemplateRegistry) {
        templates.register(AndroidAppTemplate, PLUGIN)
        templates.register(MaterialYouAppTemplate, PLUGIN)
        templates.register(JetpackComposeAppTemplate, PLUGIN)
        templates.register(AndroidLibraryTemplate, PLUGIN)
    }
}
