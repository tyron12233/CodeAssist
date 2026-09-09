package dev.ide.core

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.core.settings.BuiltInSettingsPages
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.model.ContentRole
import java.nio.file.Path

/*
 * The engine's data model: what IdeServices hands back, and what it takes in.
 *
 * Every one of these was declared inside IdeServices.kt, next to the member that returns it. That is a
 * large part of how that file reached 4,851 lines, and it meant reading the shape of a preview or a rename
 * result required opening the engine. The package is unchanged -- they are still `dev.ide.core` -- so
 * nothing that names them moved.
 */
class AndroidDeviceTools(
    val androidJar: Path,
    val nativeLibDir: Path,
    val debugKeystore: Path,
    /** The running device's API level (`Build.VERSION.SDK_INT`): the min-api the Android APK build/dex
     *  targets, so D8 desugars only what this device needs. */
    val apiLevel: Int = 21,
    /** Java 9+ desugar stubs (`core-lambda-stubs.jar`: `StringConcatFactory`/`LambdaMetafactory`), part of the
     *  compile platform so a Java 9+ build's string concatenation/lambdas resolve at compile time (D8 desugars
     *  the resulting invokedynamic). `android.jar` omits them; on ART they ship as a bundled asset. */
    val desugarStubs: List<Path> = emptyList(),
    /** On-device R8 shrinker that runs R8 in a forked command-line VM (`dalvikvm64 -Xmx…`) so its whole-program
     *  pass gets a heap larger than the app's `largeHeap` cap — the fix for the release/minify OOM. Null falls
     *  back to the in-process R8 ([dev.ide.android.support.tools.R8InProcessShrinker]). Supplied by :ide-android,
     *  which bundles R8's dexes + discovers the launcher; the forked shrinker self-falls-back if forking is
     *  unavailable on the device. */
    val r8Shrinker: dev.ide.android.support.tools.Shrinker? = null,
    /** On-device D8 dexer for the dex MERGE step (the debug-path memory peak), run in a forked VM so it gets a
     *  heap above the app cap. Null → the in-process merge. Supplied by :ide-android; self-falls-back. */
    val r8MergeDexer: dev.ide.android.support.tools.Dexer? = null,
    /** Max class-dex merged in one batch on a large app (the "Dex merge batch size" setting). Read per build so
     *  a change applies on the next build; defaults to [BuiltInSettingsPages.DEX_MERGE_BATCH_DEFAULT]. */
    val mergeChunkProvider: () -> Int = { BuiltInSettingsPages.DEX_MERGE_BATCH_DEFAULT },
    /** The bundled `:applog-runtime` jar (extracted from assets), woven into DEBUG builds so the running app
     *  forwards its logs to the IDE's Logcat tab. Null → no instrumentation. */
    val appLogRuntimeJar: Path? = null,
    /** Whether app-log forwarding is enabled (the "Forward app logs" setting; read per build so a toggle
     *  applies on the next build). Default on. Only consulted when [appLogRuntimeJar] is present. */
    val appLogEnabled: () -> Boolean = { true },
)

/**
 * Installs (and then launches) a freshly built APK: the on-device "Run" for an android-app. Supplied by
 * :ide-android (it needs Android's `PackageInstaller` + the OS install-confirmation UI); null on the
 * desktop, where the android task stops at producing the signed artifact. [installAndLaunch] returns once
 * the installation is initiated and streams progress + the eventual launch to [log].
 */
interface ApkInstaller {
    suspend fun installAndLaunch(apk: Path, packageName: String, log: (String) -> Unit): Boolean
}

/** The status of running a Compose `@Preview` through the interpreter: [ok] = interpretable/rendered. */
data class PreviewRunResult(val ok: Boolean, val message: String)

/** How a library class's [LibraryContent] text was obtained. */
enum class LibraryContentKind { SOURCE, DECOMPILED_JAVA, DECOMPILED_KOTLIN }

/** The read-only display of a compiled library class [fqn]: its attached SOURCE if present, else a DECOMPILED
 *  view (full-body Java via Vineflower, or a declaration Kotlin stub). [name] is the tab/file name (`Foo.kt` /
 *  `Foo.java`); [kind] drives the read-only banner + syntax language. */
data class LibraryContent(val fqn: String, val name: String, val text: String, val kind: LibraryContentKind)

/** A lowered `@Preview` ready to render: the preview function + the file's program for its source calls, plus
 *  the file's source classes/objects/enums (which the interpreter materializes — they aren't compiled). When
 *  the preview takes a `@PreviewParameter`, [parameter] describes the provider the renderer feeds it from. */
data class LoweredComposePreview(
    val entry: ResolvedFunction,
    val program: Map<String, ResolvedFunction>,
    val classes: List<ResolvedClass> = emptyList(),
    val parameter: LoweredPreviewParameter? = null,
)

/** A `@PreviewParameter` provider resolved for rendering. [providerClass] is the lowered source class when the
 *  provider is project source (the interpreter instantiates it); otherwise [providerFqn] names a library class
 *  the renderer loads reflectively. [limit] caps how many of the provider's sample values are rendered. */
data class LoweredPreviewParameter(
    val providerSimpleName: String,
    val providerFqn: String?,
    val providerClass: ResolvedClass?,
    val limit: Int,
)

/**
 * The library inputs an on-device Compose preview needs to dispatch against the project's real libraries:
 * the module compile-classpath [jars] (transitive), the bundled [androidJar] (boot classpath for desugaring,
 * null if none), the module's [minApi], and a content-stable [fingerprint] (sorted jar paths + sizes) the
 * launcher keys its dex/classloader cache on. See `IdeServices.composePreviewLibs`.
 */
data class ComposePreviewLibs(
    val jars: List<Path>,
    val fingerprint: String,
    val androidJar: Path?,
    val minApi: Int,
    /** Base dir for the launcher's dex/oat cache (per-[fingerprint] subdir lives under here). */
    val cacheDir: Path,
)

/**
 * The previewed module's merged Android resources ([repo]) + R package ([namespace]) for the Compose preview's
 * interpreter-mediated resource resolution. The launcher builds a `dev.ide.interp.PreviewResourceResolver` from
 * these (`R.string.x` ids via `RIdAssignment(repo)`, values via the resource engine) — kept out of ide-core so
 * ide-core needs no interp/Compose dependency. See `IdeServices.composePreviewResources`.
 */
data class ComposePreviewResources(
    val repo: ResourceRepository,
    val namespace: String,
)

/**
 * The previewed module's resource-directory paths + R [namespace], so an OUT-OF-PROCESS (`:preview`) renderer can
 * REBUILD the [ResourceRepository] itself — the in-memory repo of [ComposePreviewResources] can't cross a process
 * boundary, but the res dirs are on disk and `:preview` (same uid) can re-parse them. See
 * `IdeServices.composePreviewResourceRoots`.
 */
data class ComposePreviewResourceRoots(
    val resDirs: List<Path>,
    val namespace: String,
)

/**
 * Renders a lowered `@Preview` composable into the real Compose runtime (the interpreter half lives in
 * :interp-core / :ide-android). Supplied by :ide-android (it needs the real `androidx.compose.runtime` + a
 * composition surface); null on the desktop / until wired, where preview "runs" report only interpretability.
 */
interface ComposePreviewRunner {
    suspend fun render(
        entry: ResolvedFunction,
        program: Map<String, ResolvedFunction>,
    ): PreviewRunResult
}

/** A surfaced content root with the metadata the tree needs: where it is, its source-set, its roles. */
data class TreeRootInfo(val path: Path, val sourceSetName: String, val roles: Set<ContentRole>)

/** Whether the Android platform sources (parameter names + javadoc for `android.*`) are installed/obtainable. */
data class AndroidSourcesInfo(
    val platform: String, val installed: Boolean, val downloadable: Boolean
)

/** The renameable symbol under the caret: its current [oldName] and a human [kind] label (e.g. "method"). */
data class RenameInfo(val oldName: String, val kind: String)

/**
 * Outcome of a project-wide rename: [occurrences] identifiers across [filesChanged] files were rewritten;
 * [newPath] is set when the backing `.java` file was itself renamed (so the editor can reopen it).
 */
data class RenameOutcome(
    val success: Boolean,
    val message: String,
    val occurrences: Int = 0,
    val filesChanged: Int = 0,
    val newPath: String? = null,
)
