package dev.ide.core

import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.analysis.ANALYZER_EP
import dev.ide.android.support.AndroidBuildConfigProvider
import dev.ide.android.support.AndroidRClassProvider
import dev.ide.android.support.AndroidSupport
import dev.ide.android.support.AndroidViewBindingProvider
import dev.ide.android.support.index.AndroidResourceIndex
import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.block.BLOCK_MAPPING_EP
import dev.ide.block.impl.JavaBlockMapping
import dev.ide.core.actions.BuiltInActions
import dev.ide.core.analysis.PackageMismatchAnalyzer
import dev.ide.core.completion.BufferWordsContributor
import dev.ide.core.completion.CompletionStats
import dev.ide.core.completion.PostfixContributor
import dev.ide.core.completion.UserLiveTemplateContributor
import dev.ide.core.gradle.GradleBuildFileWriter
import dev.ide.core.gradle.GradleProjectImporter
import dev.ide.core.services.AndroidResourceService
import dev.ide.core.services.BlockService
import dev.ide.core.services.BuildService
import dev.ide.core.services.ComposePreviewService
import dev.ide.core.services.DependencyService
import dev.ide.core.services.KotlinEditorService
import dev.ide.core.services.LanguageFeatureService
import dev.ide.core.services.ModuleService
import dev.ide.core.services.RefactorService
import dev.ide.core.services.SearchService
import dev.ide.core.services.SigningService
import dev.ide.core.sync.ProjectSyncService
import dev.ide.core.templates.CalculatorSampleTemplate
import dev.ide.core.templates.JavaConsoleAppTemplate
import dev.ide.core.templates.JavaLibraryTemplate
import dev.ide.core.templates.SwingAppTemplate
import dev.ide.core.templates.SwingCanvasTemplate
import dev.ide.core.templates.KotlinConsoleAppTemplate
import dev.ide.core.templates.KotlinLibraryTemplate
import dev.ide.core.templates.NotesSampleTemplate
import dev.ide.core.templates.WeatherSampleTemplate
import dev.ide.index.INDEX_EP
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LANGUAGE_BACKEND_EP
import dev.ide.lang.LanguageId
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.lang.completion.COMPLETION_WEIGHER_EP
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.StatsWeigher
import dev.ide.lang.java.JavaLanguageBackend
import dev.ide.lang.jdt.analysis.JdtAnalysisSupport
import dev.ide.lang.java.index.JavaClassLocatorIndex
import dev.ide.lang.java.index.JavaClassNamesIndex
import dev.ide.lang.java.index.JavaMainIndex
import dev.ide.lang.java.index.JavaMembersByOwnerIndex
import dev.ide.lang.java.index.JavaMembersIndex
import dev.ide.lang.java.index.JavaPackageTypesIndex
import dev.ide.lang.java.index.JavaPackagesIndex
import dev.ide.lang.java.index.JavaSourceAnnotationIndex
import dev.ide.lang.java.index.JavaSourceDocIndex
import dev.ide.lang.java.index.JavaSourceSubtypeIndex
import dev.ide.lang.java.index.JavaSourceSymbolsIndex
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.analysis.KotlinAnalysisSupport
import dev.ide.lang.kotlin.compile.ComposeCompilerPlugin
import dev.ide.lang.kotlin.compile.KOTLIN_COMPILER_PLUGIN_EP
import dev.ide.lang.kotlin.compile.ParcelizeCompilerPlugin
import dev.ide.lang.kotlin.compile.SerializationCompilerPlugin
import dev.ide.lang.kotlin.completion.KotlinPostfixTemplates
import dev.ide.lang.kotlin.index.BinaryAnnotationIndex
import dev.ide.lang.kotlin.index.BinarySubtypeIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinClassNamesIndex
import dev.ide.lang.kotlin.index.KotlinMainIndex
import dev.ide.lang.kotlin.index.KotlinMembersIndex
import dev.ide.lang.kotlin.index.KotlinPackageDeclIndex
import dev.ide.lang.kotlin.index.KotlinPackageTypesIndex
import dev.ide.lang.kotlin.index.KotlinPackagesIndex
import dev.ide.lang.kotlin.index.KotlinSourceAnnotationIndex
import dev.ide.lang.kotlin.index.KotlinSourceCallableIndex
import dev.ide.lang.kotlin.index.KotlinSourceDocIndex
import dev.ide.lang.kotlin.index.KotlinSourceSubtypeIndex
import dev.ide.lang.kotlin.index.KotlinSourceSymbolsIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.synthetic.KotlinSyntheticClassProvider
import dev.ide.lang.postfix.POSTFIX_TEMPLATE_EP
import dev.ide.lang.synthetic.SYNTHETIC_CLASS_EP
import dev.ide.lang.xml.XmlLanguageBackend
import dev.ide.lang.xml.lint.XmlAnalysisSupport
import dev.ide.model.impl.DefaultFileIconProvider
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.FileIconRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectTemplateRegistry
import dev.ide.model.module
import dev.ide.model.sync.BUILD_FILE_WRITER_EP
import dev.ide.model.sync.PROJECT_IMPORTER_EP
import dev.ide.platform.ServiceScopeLevel
import dev.ide.plugin.Plugin
import dev.ide.build.SOURCE_GENERATOR_EP
import dev.ide.ksp.DefaultKspProcessorLoader
import dev.ide.ksp.KspProcessorCatalog
import dev.ide.ksp.KspProcessorLoader
import dev.ide.ksp.KspSourceGenerator
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import java.nio.file.Files
import java.nio.file.Paths
import dev.ide.plugin.impl.ActionManager
import dev.ide.agent.ui.AgentUiPlugin
import dev.ide.ui.ext.UiPlugin

/**
 * The IDE's own built-in plugins and the ordered set the [ApplicationEnvironment] loads.
 *
 * This is the whole host-wiring, expressed AS plugins: the imperative `registerStaticPlugins`/
 * `registerEngineServices`/`registerActiveEnginePlugins` block that used to live in the [IdeServices]
 * companion is now a set of built-in [Plugin]s driven by the [dev.ide.plugin.impl.PluginManager]. Each maps
 * 1:1 to the [dev.ide.platform.PluginId] it contributed under before, so the resolved registry is identical.
 *
 * What used to be implicit registration *sequencing* is now declared load-order:
 *  - `jdt-language` (the `.java` file type + ecj compiler) has no dependency and loads first; the language
 *    backends `dependsOn` it. `java-psi-language` loads next, so `JavaLanguageBackend` is index 0 on
 *    [LANGUAGE_BACKEND_EP] — the resolution fallback `backendFor` relies on. Both are essential.
 *
 * Contributions that must reach the currently-open project (synthetic-R, the acceptance-stats weigher, the XML
 * resource host, the app-compat action, the command actions) take [ApplicationEnvironment] and read
 * `env.activeEngine` lazily at callback time — never during `register`.
 */
/**
 * One built-in feature's UNIFIED registration: its engine [Plugin] (the identity — manifest/id/enabled state
 * live here) paired with an OPTIONAL Compose [UiPlugin] facet. The two facets can't be one object (platform-core
 * vs Compose worlds; a `@Composable` body can't live in the engine module), so a feature co-declares them here
 * in one entry. [ApplicationEnvironment] loads the engine facet into the plugin manager and exposes the UI facet
 * of ENABLED plugins to the shell — so disabling the plugin drops BOTH halves through the one decision.
 */
class BuiltInPlugin(val engine: Plugin, val ui: UiPlugin? = null)

object BuiltInPlugins {
    fun assemble(env: ApplicationEnvironment, codecs: FacetCodecRegistry): List<BuiltInPlugin> = listOf(
        BuiltInPlugin(PlatformPlugin()),
        BuiltInPlugin(JdtLanguagePlugin()),
        BuiltInPlugin(JavaPsiLanguagePlugin()),
        BuiltInPlugin(XmlLanguagePlugin()),
        BuiltInPlugin(KotlinLanguagePlugin()),
        BuiltInPlugin(JavaSupportPlugin()),
        BuiltInPlugin(KotlinSupportPlugin()),
        BuiltInPlugin(KspSupportPlugin(env)),
        BuiltInPlugin(GradleSupportPlugin()),
        BuiltInPlugin(BlocksPlugin()),
        BuiltInPlugin(AndroidSupportPlugin(env, codecs)),
        BuiltInPlugin(SamplesPlugin()),
        BuiltInPlugin(CompletionBuiltinsPlugin(env)),
        BuiltInPlugin(IndexingPlugin()),
        BuiltInPlugin(JdtAnalysisPlugin()),
        BuiltInPlugin(JavaPsiAnalysisPlugin()),
        BuiltInPlugin(KotlinAnalysisPlugin()),
        BuiltInPlugin(PackageMismatchPlugin()),
        BuiltInPlugin(XmlAnalysisPlugin(env)),
        BuiltInPlugin(AndroidXmlPlugin(env)),
        BuiltInPlugin(IdeCoreServicesPlugin()),
        BuiltInPlugin(IdeCoreActionsPlugin(env)),
        // The AI agent: engine facet (settings page + AgentBackend wiring) + its Compose chat UI, one entry.
        BuiltInPlugin(AgentPlugin(), ui = AgentUiPlugin),
    )
}

/** The platform baseline: the default file-icon classifier. */
private class PlatformPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "platform", name = "Platform", essential = true,
        description = "Core file-icon classifier and base file-type mappings.",
    )

    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, pid ->
            FileIconRegistry(ext).register(
                DefaultFileIconProvider,
                pid
            )
        }
        // Markdown has no language backend; the mapping keeps a .md file from being analysed as Java.
        reg.register(
            FILE_TYPE_EP,
            FileTypeMapping(listOf(".md", ".markdown"), LanguageId("markdown"))
        )
    }
}

/**
 * The `.java` file-type owner. JDT is no longer the `.java` EDITOR backend (that is [JavaPsiLanguagePlugin]);
 * ecj lives on as the build compiler ([dev.ide.lang.jdt.build] / `:jvm-build`) and as the `.java` compile-level
 * [dev.ide.analysis.DiagnosticProvider] + quick-fixes ([JdtAnalysisPlugin]). This plugin just registers the
 * `.java → java` file-type association (kept here as the historical/essential owner). It stays first so it
 * loads before the language backends that `dependsOn` it.
 */
private class JdtLanguagePlugin : Plugin {
    override val manifest = PluginManifest(
        id = "jdt-language", name = "Java (file type + compiler)", essential = true,
        description = "Owns the .java file type; ecj remains the Java build compiler and compile-level diagnostic provider.",
    )

    override fun register(reg: PluginRegistration) {
        reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".java"), LanguageId("java")))
    }
}

/**
 * The IntelliJ-PSI Java backend (`:lang-java`) — THE `.java` editor backend: IntelliJ's Java parser + native
 * resolution/inference for completion, navigation, folding/highlight/signature/inlay, rename, and
 * unresolved-symbol diagnostics. It is the only backend claiming `LanguageId("java")`, so `backendFor("java")`
 * resolves to it and the module's `ANALYZER_JAVA` builds a `JavaSourceAnalyzer`. `dependsOn` jdt-language so
 * the `.java` file type + the ecj diagnostic/quick-fix providers are registered first. `essential` because it
 * is the sole Java editor backend and the first registrant on [LANGUAGE_BACKEND_EP] — the resolution fallback
 * `backendFor` relies on — so it must not be disablable.
 *
 * Formatting is covered at the host level (`LanguageFeatureService` reuses JDT's `CodeFormatter` for `.java`).
 * Not-yet-covered vs the old JDT editor (see docs): compile-level diagnostics beyond unresolved references
 * (type mismatch, missing return, …) — those still come from the ecj `CompilerDiagnosticProvider` only when it
 * can reconcile via a JDT analyzer, so they are currently absent under this backend.
 */
private class JavaPsiLanguagePlugin : Plugin {
    override val manifest = PluginManifest(
        id = "java-psi-language",
        name = "Java Language (IntelliJ PSI)",
        essential = true,
        description = "Java editing via IntelliJ's Java PSI parser and native resolution/inference (the .java editor backend).",
        dependsOn = listOf("jdt-language"),
    )

    override fun register(reg: PluginRegistration) {
        reg.register(LANGUAGE_BACKEND_EP, JavaLanguageBackend())
    }
}

/** XML language backend (Android layouts/values/manifest). */
private class XmlLanguagePlugin : Plugin {
    override val manifest = PluginManifest(
        id = "xml-language",
        name = "XML Language",
        description = "XML editing for Android layouts, values, manifest, and drawables (tolerant parser + completion).",
        dependsOn = listOf("jdt-language"),
    )

    override fun register(reg: PluginRegistration) {
        reg.register(LANGUAGE_BACKEND_EP, XmlLanguageBackend())
        reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".xml"), XmlLanguageBackend.LANGUAGE_ID))
    }
}

/** Kotlin language backend (editor-only). */
private class KotlinLanguagePlugin : Plugin {
    override val manifest = PluginManifest(
        id = "kotlin-language",
        name = "Kotlin Language",
        description = "Kotlin editing: parsing, code completion, and navigation (editor-only).",
        dependsOn = listOf("jdt-language"),
    )

    override fun register(reg: PluginRegistration) {
        reg.register(LANGUAGE_BACKEND_EP, KotlinLanguageBackend())
        reg.register(
            FILE_TYPE_EP,
            FileTypeMapping(listOf(".kt", ".kts"), KotlinLanguageBackend.LANGUAGE_ID)
        )
    }
}

/** Java support: the java-library module type and the Java Create-Project templates. */
private class JavaSupportPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "java-support", name = "Java Support",
        description = "Java-library module type and Java Create-Project templates.",
    )
    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, pid ->
            ModuleTypeRegistry(ext).register(JavaLibModuleType, pid)
            val templates = ProjectTemplateRegistry(ext)
            templates.register(JavaConsoleAppTemplate, pid)
            templates.register(JavaLibraryTemplate, pid)
            templates.register(SwingAppTemplate, pid)
            templates.register(SwingCanvasTemplate, pid)
        }
    }
}

/**
 * Gradle compatibility: the [GradleProjectImporter] that reads a Gradle project's scripts into the project
 * model ([PROJECT_IMPORTER_EP]) and the [GradleBuildFileWriter] that writes dependency declarations back into
 * `build.gradle(.kts)` ([BUILD_FILE_WRITER_EP]). Disabling it leaves Gradle folders unrecognized: they open
 * as empty native workspaces instead of imported projects.
 */
private class GradleSupportPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "gradle-support", name = "Gradle Support",
        description = "Opens Gradle projects by reading their build scripts statically, and writes dependency declarations back to them.",
    )

    override fun register(reg: PluginRegistration) {
        reg.register(PROJECT_IMPORTER_EP, GradleProjectImporter())
        reg.register(BUILD_FILE_WRITER_EP, GradleBuildFileWriter())
    }
}

/**
 * The projectional (block) editor: contributes the Java block decomposition ([JavaBlockMapping]) onto
 * [BLOCK_MAPPING_EP], the one thing that makes the Code/Blocks toggle do anything. Non-essential — disabling
 * it drops the only block mapping, so the engine's [dev.ide.core.services.BlockService] reports no mappings and
 * the UI hides the Blocks view-mode segment (the whole feature turns off through this one decision). The
 * generic projection plumbing (the WORKSPACE-scoped `BlockService`) stays in [IdeCoreServicesPlugin]; with no
 * mapping registered it is simply inert.
 */
private class BlocksPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "blocks", name = "Block Editor",
        description = "The projectional block editor — a Scratch-style visual view of the same code, toggled per file.",
    )
    override fun register(reg: PluginRegistration) {
        reg.register(BLOCK_MAPPING_EP, JavaBlockMapping)
    }
}

/** Kotlin support: the Kotlin-interop synthetic classes, Kotlin Create-Project templates, and the built-in
 *  Compose Kotlin-compiler plugin (the build's compileKotlin tasks read it off the EP). */
private class KotlinSupportPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "kotlin-support",
        name = "Kotlin Support",
        description = "Kotlin-interop synthetic classes (for java), Kotlin Create-Project templates, and the built-in Compose Kotlin-compiler plugin."
    )
    override fun register(reg: PluginRegistration) {
        reg.register(SYNTHETIC_CLASS_EP, KotlinSyntheticClassProvider())
        reg.register(KOTLIN_COMPILER_PLUGIN_EP, ComposeCompilerPlugin)
        reg.register(KOTLIN_COMPILER_PLUGIN_EP, SerializationCompilerPlugin)
        reg.register(KOTLIN_COMPILER_PLUGIN_EP, ParcelizeCompilerPlugin)
        // Editor support for a compiler plugin's generated members (kotlinx.serialization's `Foo.serializer()`,
        // Parcelize's `Foo.CREATOR`): the parse-only symbol model can't see them, so a provider contributes them
        // to completion/resolution.
        reg.register(
            dev.ide.lang.kotlin.symbols.KOTLIN_SYNTHETIC_MEMBER_EP,
            dev.ide.lang.kotlin.symbols.SerializationSyntheticMembers,
        )
        reg.register(
            dev.ide.lang.kotlin.symbols.KOTLIN_SYNTHETIC_MEMBER_EP,
            dev.ide.lang.kotlin.symbols.ParcelizeSyntheticMembers,
        )
        reg.contributeVia { ext, pid ->
            val templates = ProjectTemplateRegistry(ext)
            templates.register(KotlinConsoleAppTemplate, pid)
            templates.register(KotlinLibraryTemplate, pid)
        }
    }
}

/**
 * KSP2 source generation: contributes [KspSourceGenerator] on [SOURCE_GENERATOR_EP], so the build's
 * `generateSources` tasks run the IDE's **bundled** KSP2 processors (Room, …) on the IDE's OWN compiler/AA
 * (the ~776 KB thin runner + the bundled processor jars — nothing 78 MB or downloaded, so it stays within
 * Play's dynamic-code-loading policy). Activation is marker + declared-dependency: a processor runs when its
 * runtime is a **directly-declared** dependency of the module (added via the Dependencies screen or the KSP
 * toggle) and its marker is on the compile classpath. Matching AGP's explicit `ksp(...)` opt-in, a runtime
 * that only arrives transitively (e.g. a Compose app that pulls `room-runtime` through another library but
 * never declares Room) does NOT activate the processor. The generated `.kt`/`.java` land in the module's
 * `ContentRole.GENERATED` root and compile + index like hand-written code.
 *
 * The processor classloader is the injected [KOTLIN_PLUGIN_LOADER] (a plain `URLClassLoader` on desktop, a
 * `DexClassLoader` over bundled dex on ART) — its parent is the app classloader, which carries our compiler/AA
 * + `symbol-processing-api`, so the thin runner + processors resolve those parent-first. `jdkHome` is a real
 * JDK on desktop and null on ART (where android.jar on the module's compile classpath supplies `java.*`).
 */
private class KspSupportPlugin(private val env: ApplicationEnvironment) : Plugin {
    override val manifest = PluginManifest(
        id = "ksp-support", name = "KSP Source Generation",
        description = "Runs bundled KSP2 processors (Room, …) at build time on the IDE's own compiler; generated sources compile + index like hand-written code.",
    )

    override fun register(reg: PluginRegistration) {
        val catalog = KspProcessorCatalog.bundled()
        // Reuse the injected Kotlin-plugin loader; read lazily (it may be registered after assemble()), falling
        // back to the desktop URLClassLoader when no host loader is wired.
        val loader = KspProcessorLoader { cp ->
            env.container.getServiceOrNull(KOTLIN_PLUGIN_LOADER)?.load(cp) ?: DefaultKspProcessorLoader.load(cp)
        }
        // A real modular JDK (desktop) exposes lib/jrt-fs.jar; ART's java.home does not — there KSP resolves
        // java.* from android.jar on the module's compile classpath instead, so jdkHome stays null.
        val jdkHome = System.getProperty("java.home")?.let { Paths.get(it) }
            ?.takeIf { Files.exists(it.resolve("lib/jrt-fs.jar")) }
        reg.register(
            SOURCE_GENERATOR_EP,
            KspSourceGenerator(
                processors = { req -> catalog.classpathFor(req.classpath, req.declaredDependencies) },
                // The IDE runs the processor version it BUNDLES, so a project pinning an older runtime gets
                // generated sources its own runtime can't compile. Report that up front instead of letting the
                // module fail on the symbols it produces. A mismatch the user accepted (the editor banner's
                // "build anyway") arrives on the request and becomes a per-build warning instead.
                preflight = { req ->
                    catalog.preflight(req.classpath, req.declaredDependencies, req.acceptedWarnings)
                },
                loader = loader,
                jdkHome = jdkHome,
            ),
        )
    }
}

/**
 * The android-support plugin: module types + the AndroidFacet codec + tree icons + templates + Compose sample
 * games (via the [AndroidSupport] facades, which attribute to `PluginId("android-support")`), the static
 * synthetic classes (BuildConfig, ViewBinding), and the light synthetic `R` resolved from the active engine's
 * shared resource repository.
 */
private class AndroidSupportPlugin(
    private val env: ApplicationEnvironment,
    private val codecs: FacetCodecRegistry,
) : Plugin {
    override val manifest = PluginManifest(
        id = "android-support", name = "Android Support",
        description = "Android module types, the AndroidFacet + its module.toml codec, variants, resource icons, templates, and synthetic R / BuildConfig / ViewBinding classes.",
    )
    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, _ ->
            AndroidSupport.register(ModuleTypeRegistry(ext), codecs)
            AndroidSupport.registerIcons(FileIconRegistry(ext))
            AndroidSupport.registerTemplates(ProjectTemplateRegistry(ext))
            AndroidSupport.registerComposeSamples(ProjectTemplateRegistry(ext))
        }
        reg.register(SYNTHETIC_CLASS_EP, AndroidBuildConfigProvider())
        reg.register(SYNTHETIC_CLASS_EP, AndroidViewBindingProvider())
        reg.register(
            SYNTHETIC_CLASS_EP,
            AndroidRClassProvider { m, _ -> env.activeEngine?.resourceRepo(m) })
        // ProGuard/R8 keep-rule files: routed off Java so JDT never flags them as broken Java.
        reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".pro"), LanguageId("proguard")))
    }
}

/** The bundled sample projects (Calculator, Notes, Weather) in the Create-Project gallery. */
private class SamplesPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "samples", name = "Sample Projects",
        description = "Bundled sample projects (Calculator, Notes, Weather) in the Create-Project gallery.",
    )
    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, pid ->
            val templates = ProjectTemplateRegistry(ext)
            templates.register(CalculatorSampleTemplate, pid)
            templates.register(NotesSampleTemplate, pid)
            templates.register(WeatherSampleTemplate, pid)
        }
    }
}

/** Cross-cutting completion: buffer-words + postfix contributors, Kotlin postfix templates, and the
 *  acceptance-frequency stats weigher (which counts through the active engine's per-project stats). */
private class CompletionBuiltinsPlugin(private val env: ApplicationEnvironment) : Plugin {
    override val manifest = PluginManifest(
        id = "completion-builtins", name = "Completion Built-ins",
        description = "Cross-language completion: buffer words, postfix templates, and acceptance-frequency ranking.",
    )

    override fun register(reg: PluginRegistration) {
        reg.register(
            COMPLETION_CONTRIBUTOR_EP,
            CompletionContribution(BufferWordsContributor, order = BufferWordsContributor.ORDER),
        )
        reg.contributeVia { ext, pid ->
            ext.register(
                COMPLETION_CONTRIBUTOR_EP,
                CompletionContribution(PostfixContributor(ext), order = PostfixContributor.ORDER),
                pid,
            )
            // User-defined live-template macros → completion, resolved through the open project's engine (its
            // effective set = built-ins ◂ global ◂ project). Language-agnostic registration; each macro's own
            // language scope is enforced inside the contributor.
            ext.register(
                COMPLETION_CONTRIBUTOR_EP,
                CompletionContribution(
                    UserLiveTemplateContributor { lang -> env.activeEngine?.userMacros(lang) ?: emptyList() },
                    order = UserLiveTemplateContributor.ORDER,
                ),
                pid,
            )
            KotlinPostfixTemplates.all().forEach { ext.register(POSTFIX_TEMPLATE_EP, it, pid) }
        }
        reg.register(
            COMPLETION_WEIGHER_EP,
            StatsWeigher { item ->
                env.activeEngine?.completionStats?.countFor(CompletionStats.keyOf(item.label)) ?: 0
            },
        )
    }
}

/** All built-in symbol/member/resource index extensions. */
private class IndexingPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "indexing", name = "Indexing",
        description = "Symbol, member, and Android-resource indexes powering completion, go-to, and search.",
    )
    override fun register(reg: PluginRegistration) {
        listOf(
            JavaClassNamesIndex,
            JavaPackagesIndex,
            JavaPackageTypesIndex,
            JavaClassLocatorIndex,
            JavaSourceSymbolsIndex,
            JavaMembersIndex,
            JavaMembersByOwnerIndex,
            KotlinClassNamesIndex,
            KotlinPackagesIndex,
            KotlinPackageTypesIndex,
            KotlinSourceSymbolsIndex,
            KotlinMembersIndex,
            KotlinTypeShapeIndex,
            KotlinBuiltinsIndex,
            KotlinCallableIndex,
            KotlinBuiltinCallableIndex,
            KotlinSourceCallableIndex,
            KotlinPackageDeclIndex,
            JavaSourceDocIndex,
            KotlinSourceDocIndex,
            JavaMainIndex,
            KotlinMainIndex,
            AndroidResourceIndex,
            BinarySubtypeIndex,
            BinaryAnnotationIndex,
            KotlinSourceSubtypeIndex,
            KotlinSourceAnnotationIndex,
            JavaSourceSubtypeIndex,
            JavaSourceAnnotationIndex,
        ).forEach { reg.register(INDEX_EP, it) }
    }
}

/** The Java (JDT) editor analysis surface (analyzers, compiler diagnostics, quick-fixes, intentions). */
private class JdtAnalysisPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "jdt-analysis",
        name = "Java Analysis",
        description = "Java diagnostics, compiler errors, quick-fixes, and editor intentions (Eclipse JDT).",
        dependsOn = listOf("jdt-language"),
    )

    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, pid -> JdtAnalysisSupport.register(ext, pid) }
    }
}

/**
 * The IntelliJ-PSI Java backend's native quick-fixes, keyed on its own diagnostic codes (type mismatch →
 * change variable type; unhandled exception → add to `throws` / surround with try-catch). `dependsOn`
 * java-psi-language (the backend that emits those codes). The JDT "Add import" fix (keyed on the neutral
 * `UNRESOLVED_REFERENCE`) still fires for this backend via jdt-analysis; this adds the ecj-free rest.
 */
private class JavaPsiAnalysisPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "java-psi-analysis",
        name = "Java Analysis (IntelliJ PSI)",
        description = "Native Java quick-fixes for the IntelliJ-PSI backend (type mismatch, unhandled exception).",
        dependsOn = listOf("java-psi-language"),
    )

    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, pid -> dev.ide.lang.java.analysis.JavaPsiAnalysisSupport.register(ext, pid) }
    }
}

/** The Kotlin editor analysis surface (diagnostics + import/implement-members code actions). */
private class KotlinAnalysisPlugin : Plugin {
    override val manifest =
        PluginManifest(
            id = "kotlin-analysis",
            name = "Kotlin Analysis",
            description = "Kotlin diagnostics plus import and implement-members code actions.",
            dependsOn = listOf("kotlin-language"),
        )

    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, pid -> KotlinAnalysisSupport.register(ext, pid) }
    }
}

/**
 * The cross-language "package does not match file location" inspection (Java + Kotlin). Host-level because it
 * needs the file's module source roots (not just the language tree) to derive the expected package, and its
 * fix reuses both languages' package-text rewriters. `dependsOn` both language backends so the file types are
 * registered before it runs.
 */
private class PackageMismatchPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "package-mismatch",
        name = "Package Mismatch Inspection",
        description = "Flags a Java/Kotlin file whose package does not match its directory, with a fix to correct it.",
        dependsOn = listOf("jdt-language", "kotlin-language"),
    )

    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, pid -> ext.register(ANALYZER_EP, PackageMismatchAnalyzer(), pid) }
    }
}

/**
 * The XML editor diagnostics, wired to the active engine's per-project resource host + Android attribute
 * schema (both resolve `env.activeEngine` lazily). Attributed to `PluginId("xml-analysis")` by the facade.
 */
private class XmlAnalysisPlugin(private val env: ApplicationEnvironment) : Plugin {
    override val manifest =
        PluginManifest(
            id = "xml-analysis",
            name = "XML Analysis",
            description = "XML/Android resource diagnostics and quick-fixes (unresolved references, hardcoded strings, missing attributes).",
            dependsOn = listOf("xml-language"),
        )

    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, _ ->
            XmlAnalysisSupport.register(
                ext,
                ActiveEngineXmlResourceHost(env),
                AndroidXmlChecker(layout = {
                    env.activeEngine?.sdkLayoutMetadata() ?: AndroidSdkMetadata.bundled()
                }),
            )
        }
    }
}

/** The Android app-compat XML intention (delegates the "uses appcompat?" check to the active engine). */
private class AndroidXmlPlugin(private val env: ApplicationEnvironment) : Plugin {
    override val manifest = PluginManifest(
        id = "android-xml", name = "Android XML",
        description = "Android XML intentions, such as the AppCompat migration action.",
    )
    override fun register(reg: PluginRegistration) {
        reg.register(
            ACTION_PROVIDER_EP,
            AndroidXmlActionProvider { target ->
                env.activeEngine?.moduleUsesAppCompat(target) ?: false
            },
        )
    }
}

/**
 * The engine's scoped services: the MODULE-scoped per-language analyzers and the WORKSPACE-scoped concern
 * services. Each factory resolves the per-project engine via [ENGINE_CONTEXT] (published on every engine's own
 * workspace container in `registerScopedServices`), so this single app-global registration serves every
 * opened project.
 */
private class IdeCoreServicesPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "ide-core-services", name = "IDE Core Services", essential = true,
        description = "The engine's scoped services (analyzers, build, module, search, dependencies, signing).",
    )

    override fun register(reg: PluginRegistration) {
        reg.service(ANALYZER_JAVA, ServiceScopeLevel.MODULE) {
            getService(ENGINE_CONTEXT).buildAnalyzer(module(), LanguageId("java"))
        }
        reg.service(ANALYZER_KOTLIN, ServiceScopeLevel.MODULE) {
            getService(ENGINE_CONTEXT).buildAnalyzer(module(), KotlinLanguageBackend.LANGUAGE_ID)
        }
        reg.service(ANALYZER_XML, ServiceScopeLevel.MODULE) {
            getService(ENGINE_CONTEXT).buildAnalyzer(module(), XmlLanguageBackend.LANGUAGE_ID)
        }
        reg.service(SIGNING_SERVICE, ServiceScopeLevel.WORKSPACE) {
            SigningService(
                getService(
                    ENGINE_CONTEXT
                )
            )
        }
        reg.service(SEARCH_SERVICE, ServiceScopeLevel.WORKSPACE) {
            SearchService(
                getService(
                    ENGINE_CONTEXT
                )
            )
        }
        reg.service(BLOCK_SERVICE, ServiceScopeLevel.WORKSPACE) {
            BlockService(
                getService(
                    ENGINE_CONTEXT
                )
            )
        }
        reg.service(ACTION_MANAGER, ServiceScopeLevel.WORKSPACE) {
            ActionManager(getService(ENGINE_CONTEXT).platform.extensions)
        }
        reg.service(DEPENDENCY_SERVICE, ServiceScopeLevel.WORKSPACE) {
            DependencyService(
                getService(
                    ENGINE_CONTEXT
                )
            )
        }
        reg.service(MODULE_SERVICE, ServiceScopeLevel.WORKSPACE) {
            ModuleService(
                getService(
                    ENGINE_CONTEXT
                )
            )
        }
        reg.service(BUILD_SERVICE, ServiceScopeLevel.WORKSPACE) {
            BuildService(
                getService(
                    ENGINE_CONTEXT
                )
            )
        }
        reg.service(PROJECT_SYNC_SERVICE, ServiceScopeLevel.WORKSPACE) {
            ProjectSyncService(getService(ENGINE_CONTEXT))
        }
        reg.service(LANGUAGE_FEATURE_SERVICE, ServiceScopeLevel.WORKSPACE) {
            LanguageFeatureService(getService(ENGINE_CONTEXT))
        }
        reg.service(ANDROID_RESOURCE_SERVICE, ServiceScopeLevel.WORKSPACE) {
            AndroidResourceService(getService(ENGINE_CONTEXT))
        }
        reg.service(REFACTOR_SERVICE, ServiceScopeLevel.WORKSPACE) {
            RefactorService(getService(ENGINE_CONTEXT))
        }
        reg.service(KOTLIN_EDITOR_SERVICE, ServiceScopeLevel.WORKSPACE) {
            KotlinEditorService(getService(ENGINE_CONTEXT))
        }
        reg.service(COMPOSE_PREVIEW_SERVICE, ServiceScopeLevel.WORKSPACE) {
            ComposePreviewService(getService(ENGINE_CONTEXT))
        }
    }
}

/** The built-in command-palette actions (Run / Stop build, Re-index) that act on the active engine. */
private class IdeCoreActionsPlugin(private val env: ApplicationEnvironment) : Plugin {
    override val manifest = PluginManifest(
        id = "ide-core-actions", name = "IDE Core Actions",
        description = "Built-in command-palette actions: Run, Stop build, and Re-index.",
    )
    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, _ -> BuiltInActions.register(ext, env) }
    }
}
