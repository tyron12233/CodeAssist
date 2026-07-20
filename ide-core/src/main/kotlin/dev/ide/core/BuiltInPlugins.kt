package dev.ide.core

import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.android.support.AndroidBuildConfigProvider
import dev.ide.android.support.AndroidRClassProvider
import dev.ide.android.support.AndroidSupport
import dev.ide.android.support.AndroidViewBindingProvider
import dev.ide.android.support.index.AndroidResourceIndex
import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.block.BLOCK_MAPPING_EP
import dev.ide.block.impl.JavaBlockMapping
import dev.ide.core.actions.BuiltInActions
import dev.ide.core.completion.BufferWordsContributor
import dev.ide.core.completion.CompletionStats
import dev.ide.core.completion.PostfixContributor
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
import dev.ide.core.templates.CalculatorSampleTemplate
import dev.ide.core.templates.JavaConsoleAppTemplate
import dev.ide.core.templates.JavaLibraryTemplate
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
import dev.ide.lang.kotlin.compile.SerializationCompilerPlugin
import dev.ide.lang.kotlin.completion.KotlinPostfixTemplates
import dev.ide.lang.kotlin.index.BinaryAnnotationIndex
import dev.ide.lang.kotlin.index.BinarySubtypeIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinMainIndex
import dev.ide.lang.kotlin.index.KotlinPackageDeclIndex
import dev.ide.lang.kotlin.index.KotlinSourceAnnotationIndex
import dev.ide.lang.kotlin.index.KotlinSourceCallableIndex
import dev.ide.lang.kotlin.index.KotlinSourceDocIndex
import dev.ide.lang.kotlin.index.KotlinSourceSubtypeIndex
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
import dev.ide.platform.ServiceScopeLevel
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.impl.ActionManager

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
object BuiltInPlugins {
    fun assemble(env: ApplicationEnvironment, codecs: FacetCodecRegistry): List<Plugin> = listOf(
        PlatformPlugin(),
        JdtLanguagePlugin(),
        JavaPsiLanguagePlugin(),
        XmlLanguagePlugin(),
        KotlinLanguagePlugin(),
        JavaSupportPlugin(),
        KotlinSupportPlugin(),
        AndroidSupportPlugin(env, codecs),
        SamplesPlugin(),
        CompletionBuiltinsPlugin(env),
        IndexingPlugin(),
        JdtAnalysisPlugin(),
        JavaPsiAnalysisPlugin(),
        KotlinAnalysisPlugin(),
        XmlAnalysisPlugin(env),
        AndroidXmlPlugin(env),
        IdeCoreServicesPlugin(),
        IdeCoreActionsPlugin(env),
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

/** Java support: the java-library module type, Java Create-Project templates, and the block decomposition. */
private class JavaSupportPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "java-support", name = "Java Support",
        description = "Java-library module type, Java Create-Project templates, and block-editor decomposition.",
    )
    override fun register(reg: PluginRegistration) {
        reg.contributeVia { ext, pid ->
            ModuleTypeRegistry(ext).register(JavaLibModuleType, pid)
            val templates = ProjectTemplateRegistry(ext)
            templates.register(JavaConsoleAppTemplate, pid)
            templates.register(JavaLibraryTemplate, pid)
        }
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
        reg.contributeVia { ext, pid ->
            val templates = ProjectTemplateRegistry(ext)
            templates.register(KotlinConsoleAppTemplate, pid)
            templates.register(KotlinLibraryTemplate, pid)
        }
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
