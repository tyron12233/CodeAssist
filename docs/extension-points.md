# Extension points

All domain-specific behavior plugs into the framework through a minimal, IntelliJ-style extension-point
registry. The core knows what a *module type*, a *build system*, and a *language backend* are; it does
not know what "Android" is. Bundled features (Android support, the build systems, language backends)
are contributed through the same mechanism a third-party plugin would use.

```kotlin
class ExtensionPoint<T>(val id: String)              // e.g. "platform.buildSystem"

interface ExtensionRegistry {
    fun <T> register(ep: ExtensionPoint<T>, impl: T, plugin: PluginId)
    fun <T> extensions(ep: ExtensionPoint<T>): List<T>
}
```

## Registered extension points

| Extension point | Contributes | Example contributors |
|---|---|---|
| `platform.moduleType` | New module types (`ModuleType`). | `android-app`, `android-lib`, `java-lib`, `java-cli`. |
| `platform.buildSystem` | Build systems (`BuildSystem`). | The native Java/Android build system; a Gradle compatibility importer. |
| `platform.languageBackend` | Language backends (`LanguageBackend`). The host picks one per file by matching the file's `LanguageId`. | JDT (`.java`), XML (`.xml`), Kotlin (`.kt`/`.kts`). |
| `platform.index` | Index extensions (`IndexExtension`). | Class names, packages, source symbols, bytecode members, Android resources. |
| `platform.analyzer` | File/project analyzers (`Analyzer`). | Built-in Java analyzers. |
| `platform.quickFixProvider` | Quick-fix providers (`QuickFixProvider`), keyed by diagnostic code. | Java and XML fixes. |
| `platform.diagnosticProvider` | Diagnostic providers (`DiagnosticProvider`) — the compiler is unified as one. | The JDT compiler. |
| `platform.fileIcon` | Project-tree icon providers (`FileIconProvider`) → string icon ids. | `DefaultFileIconProvider`, `AndroidFileIconProvider`. |
| `platform.projectTemplate` | Create-Project templates (`ProjectTemplate`) with data-driven parameters. | `java-console`, `java-library`, `android-app`, `android-library`. |
| `platform.blockMapping` | Block mappings (`BlockMapping`) for the projectional editor. | The Java block mapping. |
| `platform.kotlinCompilerPlugin` | Kotlin compiler plugins (`KotlinCompilerPlugin`) the build's `compileKotlin` tasks apply per module. | Compose (`ComposeCompilerPlugin`). |

## Language backend SPI

The core depends on `LanguageBackend` and a backend-neutral DOM, not on a concrete parser. The project
model supplies the compilation context (roots + classpath + language level); the backend produces
ASTs, diagnostics, and (for build) class output.

```kotlin
interface LanguageBackend {
    val id: String                               // "jdt" | "xml" | "kotlin" | …
    val languages: Set<LanguageId>
    val capabilities: Set<BackendCapability>     // ERROR_RECOVERY, INCREMENTAL, BINDINGS, COMPILE, …

    fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer   // editor-time: parse + resolve
    fun createCompiler(ctx: CompilationContext): SourceCompiler?  // build-time: emit .class
}
```

Three contract points make backends swappable:

- **Backend-neutral DOM.** IDE features (navigation, completion, refactoring) target `DomNode`/`Symbol`,
  not a backend's native tree. Each backend adapts its tree to this interface.
- **Capabilities, not assumptions.** A backend advertises whether it supports error recovery,
  incremental reparse, binding resolution, and compilation. The editor uses an error-recovering,
  incremental backend; the build uses a compile-capable one. A module may use one backend for editor
  analysis and another for the final compile.
- **Context from the model.** `CompilationContext.classpath` is the `ClasspathSnapshot` from the
  project model, so `api`/`implementation` correctness and cache invalidation on classpath change are
  inherited rather than re-derived.

Adding a language is therefore a registration against `platform.languageBackend`, not an edit to the
host.

## File-icon SPI

Project-tree icons are extensible across the UI/backend boundary through an opaque icon id — a string
both sides agree on. A `FileIconProvider` (contributed to `platform.fileIcon`) classifies a
backend-neutral `IconTarget` (file, source root, package, directory, module) into an icon id; the UI's
icon registry resolves that id to a concrete glyph. A plugin can give its own file types and
source-set kinds a distinct look with no UI dependency.

## Kotlin compiler plugins

Kotlin compiler plugins (Compose, kotlinx-serialization, Parcelize, all-open/no-arg) plug in through
`platform.kotlinCompilerPlugin`. A `KotlinCompilerPlugin` decides whether it `appliesTo` a module (Compose
probes the classpath for `androidx.compose.runtime.Composable`) and supplies its `-Xplugin` `classpath` plus
`-P` `options`; the build's `compileKotlin` tasks feed the union of the applicable plugins to kotlinc's
generic `compilerPlugins`/`pluginOptions` seam. Compose is the built-in contributor. Adding another plugin
is a registration, not a host edit.

A plugin is a bytecode transformer that emits no new source. Source generators (Room, KSP) are a separate
layer built on top of this seam; see `docs/kotlin-compiler-plugins-and-codegen.md`.

## Project templates

A `ProjectTemplate` (contributed to `platform.projectTemplate`) sits one level above module types. It
declares its inputs as a list of typed parameters (text / choice / toggle) so the Create-Project UI is
data-driven, and authors a whole project against a scaffold (the workspace transaction surface plus a
file-write helper, with the host injecting the SDK and language level).
