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
| `platform.buildSystem` | Build systems (`BuildSystem`). Selected by `Project.buildSystemId` first, then by `supports(moduleType)`. | The native Java/Android build system. |
| `platform.buildPlugin` | Build logic (`BuildPlugin`): tasks contributed into a graph another build system assembles. | (none built in; the seam a plugin registers its own tasks through) |
| `platform.runTaskProvider` | Run-picker rows (`RunTaskProvider`) and the `RunAction` that executes them. | (none built in) |
| `platform.projectImporter` | Foreign project models (`ProjectImporter`): build files read into an `ExternalProjectModel`. | The Gradle importer. |
| `platform.buildFileWriter` | Declaration writes back into build files (`BuildFileWriter`). | The Gradle `build.gradle(.kts)` dependency writer. |
| `platform.languageBackend` | Language backends (`LanguageBackend`). The host picks one per file by matching the file's `LanguageId`. | JDT (`.java`), XML (`.xml`), Kotlin (`.kt`/`.kts`). |
| `platform.index` | Index extensions (`IndexExtension`). | Class names, packages, source symbols, bytecode members, Android resources. |
| `platform.analyzer` | File/project analyzers (`Analyzer`). | Built-in Java analyzers. |
| `platform.quickFixProvider` | Quick-fix providers (`QuickFixProvider`), keyed by diagnostic code. | Java and XML fixes. |
| `platform.diagnosticProvider` | Diagnostic providers (`DiagnosticProvider`) — the compiler is unified as one. | The JDT compiler. |
| `platform.fileIcon` | Project-tree icon providers (`FileIconProvider`) → string icon ids. | `DefaultFileIconProvider`, `AndroidFileIconProvider`. |
| `platform.projectTemplate` | Create-Project templates (`ProjectTemplate`) with data-driven parameters. | `java-console`, `java-library`, `android-app`, `android-library`. |
| `platform.blockMapping` | Block mappings (`BlockMapping`) for the projectional editor. | The Java block mapping. |
| `platform.kotlinCompilerPlugin` | Kotlin compiler plugins (`KotlinCompilerPlugin`) the build's `compileKotlin` tasks apply per module. | Compose (`ComposeCompilerPlugin`). |
| `platform.iconRepository` | Icon libraries (`IconRepository`) the Icon Manager browses: search, list, and fetch an icon's geometry. | Bundled Material Symbols, remote Material Symbols. |

## Icon repository SPI

`platform.iconRepository` lets a plugin contribute an icon library to the Icon Manager. An `IconRepository`
answers three things: the icons it offers (`entries`), how to fetch one icon's geometry in a given style and
fill (`artwork`), and whether listing them needs the network (`requiresNetwork`, which gates downloading
behind an explicit user action). Search ranking is not the repository's job: `IconSearch` ranks every
repository's entries identically, so a contributed library behaves exactly like the built-in ones.

Geometry is returned as the same `VectorSpec` the drawable parser produces, so a contributed icon previews,
imports and rasterises through the paths already in place. See [icon-manager.md](icon-manager.md).

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

## Extending the build

Four points cover the two things a build extension does: add work to a build, and bring in a project model
from a build system the IDE doesn't own. See `docs/build-system.md` for the task-authoring contract and the
lifecycle task names a contributed task anchors to.

- **`platform.buildPlugin`** contributes tasks to whatever graph is being assembled. A `BuildPlugin` is the
  same `Plugin` interface the built-in Java and Android pipelines are written against, so a plugin registers
  lazily and wires by name (`dependsOn`, `mustRunAfter`) to tasks it does not own. It receives a
  `BuildConfiguration` carrying the project, the request, the task container, the id of the build system
  assembling the graph, and a `BuildEnv` for host paths and the module's platform classpath.
- **`platform.runTaskProvider`** puts rows in the Run picker and executes them: `tasksFor(module)` enumerates,
  `actionFor(...)` returns a `RunAction` (a graph, a console header, an optional post-build step). An id that
  reuses a built-in prefix (`build:`, `run:`, `assemble:`) runs through the host's own pipeline instead. A
  `BuildSystem` bound to a project offers the same pair through `runTasks`/`actionFor`.
- **`platform.projectImporter`** reads a foreign build system's files into a declarative
  `ExternalProjectModel` (modules, source sets, dependencies, facets as table + values). The importer returns
  data and never mutates the model; the host applies the snapshot in one transaction, binds the project to the
  importer's `BuildSystemId`, merges the repositories it declared, and stamps the files it read so a later
  change surfaces as "sync needed". `ModelOwnership.EXTERNAL` marks the build files as the source of truth,
  which makes a sync re-derive the model and drop what they no longer declare.
- **`platform.buildFileWriter`** writes declarations back, so a dependency added in the IDE survives the next
  sync of an externally-owned project. Without one the host still applies the change to the model and says in
  its result that the build files have to be edited too.
