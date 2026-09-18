package dev.ide.model

/**
 * The single source of truth for "which platform [Sdk] does this [Module] compile and analyze against".
 * Both the build (boot classpath handed to javac/ecj/kotlinc) and the editor ([CompilationContext]
 * boot classpath for completion/diagnostics) MUST resolve through here so the two never disagree — a
 * mismatch would show `android.*` in completion but reject it at build time (or vice-versa).
 *
 * This is the mechanism that keeps a plain Java/Kotlin console app free of `android.*`: it resolves a
 * JVM (core-Java) SDK for a `java-*`/`kotlin-*` module and an Android SDK for an `android-*` module,
 * instead of handing every module the one workspace-global `android.jar`.
 *
 * Precedence (first match wins):
 *  1. [Module.sdk] — an explicit `sdk = "<name>"` override in `module.toml` (e.g. to pin an API level).
 *  2. An [SdkDependency] order entry naming an SDK in the table (legacy/explicit form).
 *  3. The module type's [ModuleType.platform] default — the first table SDK whose [Sdk.kind] matches.
 *  4. Any SDK in the table (last-resort back-compat, so an unconfigured workspace still resolves something).
 */
object SdkResolution {

    fun sdkFor(workspace: Workspace, module: Module): Sdk? {
        val table = workspace.sdkTable

        module.sdk?.let { table.byName(it.name) }?.let { return it }

        module.dependencies.asSequence()
            .filterIsInstance<SdkDependency>()
            .firstNotNullOfOrNull { table.byName(it.sdk.name) }
            ?.let { return it }

        val kind = module.type.platform
        return table.sdks.firstOrNull { it.kind == kind } ?: table.sdks.firstOrNull()
    }

    /** The resolved boot classpath (the [Sdk.bootClasspath] of [sdkFor]), or empty when nothing resolves. */
    fun bootClasspathFor(workspace: Workspace, module: Module): List<dev.ide.vfs.VirtualFile> =
        sdkFor(workspace, module)?.bootClasspath ?: emptyList()
}
