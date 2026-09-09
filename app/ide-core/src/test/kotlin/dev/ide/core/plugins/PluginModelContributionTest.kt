package dev.ide.core.plugins

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FACET_CODEC_EP
import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetKey
import dev.ide.model.FacetTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeExtensionPoint
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.PlatformKind
import dev.ide.model.ProjectTemplateRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginCapabilities
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.impl.PluginManager
import dev.ide.platform.impl.ExtensionRegistryImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The documented path for a plugin that teaches the IDE a language whose projects are not laid out like a
 * JVM module: one `Plugin`, three registrations, no host privileges and no `:project-model-impl` on its
 * classpath. Compiled here so the snippet in `docs/custom-language-support.md` cannot rot.
 */
class PluginModelContributionTest {

    /** What the plugin declares once, at the top level, the way the docs show it. */
    private companion object {
        val MYLANG_FACET = FacetKey<MyLangFacet>("mylang")
        val PACKAGE_ROOT = ContentRole("mylang-package")
        val MYLANG = PlatformKind("MYLANG")
    }

    private data class MyLangFacet(val dialect: String) : Facet {
        override val key: FacetKey<*> get() = MYLANG_FACET
    }

    private object MyLangFacetCodec : FacetCodec<MyLangFacet> {
        override val key: FacetKey<MyLangFacet> = MYLANG_FACET
        override val tomlTable: String = "mylang"
        override fun encode(facet: MyLangFacet): Map<String, Any?> = mapOf("dialect" to facet.dialect)
        override fun decode(values: Map<String, Any?>): MyLangFacet =
            MyLangFacet(values["dialect"] as? String ?: "strict")
    }

    private object MyLangModuleType : ModuleType {
        override val id: String = "mylang-lib"
        override val displayName: String = "MyLang Library"
        override val platform: PlatformKind get() = MYLANG
        override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
            SourceSetTemplate(
                "main", DependencyScope.IMPLEMENTATION,
                mapOf("src/main/mylang" to setOf(PACKAGE_ROOT)),
            ),
        )
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private object MyLangAppTemplate : ProjectTemplate {
        override val id: TemplateId = TemplateId("mylang-app")
        override val displayName: String = "MyLang Application"
        override val description: String = "A MyLang project with a runnable entry point."
        override val category: TemplateCategory = TemplateCategory.OTHER
        override val iconId: String = "code"
        override fun parameters(): List<TemplateParameter> = emptyList()
        override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) = Unit
    }

    private class MyLangPlugin : Plugin {
        override val manifest = PluginManifest(
            id = "mylang-support", name = "MyLang",
            entryPoints = listOf("com.example.mylang.MyLangPlugin"),
            capabilities = listOf(
                PluginCapabilities.MODEL_MODULE_TYPE,
                PluginCapabilities.MODEL_FACET,
                PluginCapabilities.LANG_BACKEND,
            ),
        )

        override fun register(reg: PluginRegistration) {
            reg.register(ModuleTypeExtensionPoint, MyLangModuleType)
            reg.register(FACET_CODEC_EP, MyLangFacetCodec)
            reg.register(ProjectTemplateExtensionPoint, MyLangAppTemplate)
        }
    }

    @Test
    fun aPluginContributesAModuleTypeFacetCodecAndTemplateThroughTheSpiAlone() {
        val registry = ExtensionRegistryImpl()
        PluginManager(registry).loadAll(listOf(MyLangPlugin()))

        val type = assertNotNull(ModuleTypeRegistry(registry).byId("mylang-lib"))
        assertEquals(MYLANG, type.platform, "a module type keeps a platform kind the core does not own")
        assertEquals(
            setOf(PACKAGE_ROOT), type.defaultSourceSets().single().roots.values.single(),
            "and a content role the core does not own",
        )

        val codecs = FacetCodecRegistry(registry)
        val encoded = assertNotNull(codecs.encode(MyLangFacet("loose")))
        assertEquals("mylang", encoded.tomlTable)
        assertEquals(MyLangFacet("loose"), codecs.decode(encoded))

        assertNotNull(ProjectTemplateRegistry(registry).byId(TemplateId("mylang-app")))
    }

    @Test
    fun theCapabilitiesSuchAPluginDeclaresAreKnownAndNeedAnEngineFacet() {
        for (c in listOf(
            PluginCapabilities.MODEL_MODULE_TYPE,
            PluginCapabilities.MODEL_FACET,
            PluginCapabilities.LANG_BACKEND,
        )) {
            assertTrue(c in PluginCapabilities.KNOWN, "$c is offered and accepted in a manifest")
            assertTrue(c in PluginCapabilities.NEEDS_ENGINE_FACET, "$c is delivered by an entry point")
        }
    }

    @Test
    fun aModuleTypeThatNoLoadedPluginProvidesResolvesToAPlaceholder() {
        val registry = ExtensionRegistryImpl()
        val resolved = ModuleTypeRegistry(registry).resolve("mylang-lib")
        assertEquals("mylang-lib", resolved.id)
        assertTrue(resolved.defaultSourceSets().isEmpty(), "the placeholder contributes no defaults")
        assertEquals(LanguageLevel.DEFAULT, LanguageLevel.valueOf("JAVA_17"))
    }
}
