package dev.ide.core

import dev.ide.android.support.tools.KeystoreRegistry
import dev.ide.core.analysis.ModuleAnalyzers
import dev.ide.core.sdk.SdkManagerService
import dev.ide.core.services.AndroidResourceService
import dev.ide.core.services.BlockService
import dev.ide.core.services.BuildService
import dev.ide.core.services.ComposePreviewService
import dev.ide.core.services.DependencyService
import dev.ide.core.services.IconManagerService
import dev.ide.core.services.KotlinEditorService
import dev.ide.core.services.KotlinProgramLowering
import dev.ide.core.services.LanguageFeatureService
import dev.ide.core.services.ModuleService
import dev.ide.core.services.RefactorService
import dev.ide.core.services.SearchService
import dev.ide.core.services.SigningService
import dev.ide.core.sync.ProjectSyncService
import dev.ide.lang.kotlin.compile.KotlinCompilerBackend
import dev.ide.platform.ServiceKey
import dev.ide.plugin.impl.ActionManager
import dev.ide.plugin.impl.EditorDecorationCollector

/*
 * The keys the engine registers its scoped services against.
 *
 * `ServiceKey` is how a launcher, a backend or a plugin resolves a service out of a scope (see
 * docs/scoped-services-di.md). The declarations were interleaved with the model types and the class that
 * resolves them; collected here, the engine's service surface reads as the list it is.
 */
/** WORKSPACE-scoped: this engine's [EngineContext] (the shared-infrastructure surface). Registered on the
 *  engine's own workspace container so app-global service factories can resolve the per-project engine through
 *  the scope (MODULE → WORKSPACE) rather than closure-capturing it. */
internal val ENGINE_CONTEXT = ServiceKey<EngineContext>("ide.engineContext")

/** APPLICATION-scoped: the warm K2 compiler shared across every opened project. */
// `internal`, not file-private: it moved out of IdeServices.kt, which still resolves it.
internal val KOTLIN_JVM_COMPILER = ServiceKey<KotlinCompilerBackend>("ide.kotlin.jvmCompiler")

/** MODULE-scoped: this module's source analyzers, keyed by language and built on first use. ONE key rather
 *  than one per language, so the set of analyzable languages is whatever the registered
 *  [dev.ide.lang.LanguageBackend]s claim — see [ModuleAnalyzers]. */
internal val MODULE_ANALYZERS = ServiceKey<ModuleAnalyzers>("ide.analyzers")

/** WORKSPACE-scoped: this engine's decomposed concern services, resolved from the workspace container. */
internal val SIGNING_SERVICE = ServiceKey<SigningService>("ide.service.signing")
internal val SEARCH_SERVICE = ServiceKey<SearchService>("ide.service.search")
internal val BLOCK_SERVICE = ServiceKey<BlockService>("ide.service.blocks")
internal val ACTION_MANAGER = ServiceKey<ActionManager>("ide.service.actions")
internal val EDITOR_DECORATIONS =
    ServiceKey<EditorDecorationCollector>("ide.service.editorDecorations")
internal val DEPENDENCY_SERVICE = ServiceKey<DependencyService>("ide.service.dependencies")
internal val MODULE_SERVICE = ServiceKey<ModuleService>("ide.service.modules")
internal val BUILD_SERVICE = ServiceKey<BuildService>("ide.service.build")
internal val PROJECT_SYNC_SERVICE = ServiceKey<ProjectSyncService>("ide.service.projectSync")
internal val LANGUAGE_FEATURE_SERVICE =
    ServiceKey<LanguageFeatureService>("ide.service.languageFeatures")
internal val ANDROID_RESOURCE_SERVICE =
    ServiceKey<AndroidResourceService>("ide.service.androidResources")
internal val REFACTOR_SERVICE = ServiceKey<RefactorService>("ide.service.refactor")
internal val KOTLIN_EDITOR_SERVICE = ServiceKey<KotlinEditorService>("ide.service.kotlinEditor")
internal val COMPOSE_PREVIEW_SERVICE = ServiceKey<ComposePreviewService>("ide.service.composePreview")
internal val ICON_MANAGER_SERVICE = ServiceKey<IconManagerService>("ide.service.icons")

/** WORKSPACE-scoped: lowers a Kotlin declaration for the plugin-facing interpreter (`:interp-api`). Separate
 *  from [COMPOSE_PREVIEW_SERVICE] because what a plugin names as an entry is not a `@Preview` composable; the
 *  lowering work itself is shared (`loweredModelFor`). */
internal val INTERPRETER_LOWERING = ServiceKey<KotlinProgramLowering>("ide.service.interpreterLowering")

/**
 * APPLICATION-scoped shared toolchain services — reachable with no project open (the picker's Settings &
 * Tools hub). Registered on the shared application container, so there is ONE SDK download queue and ONE
 * keystore registry across every project (their on-disk artifacts already live under the shared home dir).
 * [ProjectManager] registers them eagerly so the picker can resolve them before any engine exists; an
 * engine [registerScopedServices] registers them idempotently too, for the manager-less (test) path.
 * `internal` (not file-private) so [ProjectManager] in the same package can register/resolve them.
 */
internal val APP_SDK_MANAGER = ServiceKey<SdkManagerService>("ide.app.sdkManager")
internal val APP_KEYSTORE_REGISTRY = ServiceKey<KeystoreRegistry>("ide.app.keystoreRegistry")
