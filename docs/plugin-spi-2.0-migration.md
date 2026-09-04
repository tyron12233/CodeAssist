# Migrating a plugin to SPI 2.0.0

SPI `2.0.0` is the first change that can stop an existing plugin from compiling, and
`PLUGIN_API_VERSION` moved from `2` to `3`, so **every plugin built against `1.x` is refused at the
gate** with a version mismatch rather than allowed to fail later as a linkage error.

**The coordinate to ask for is `2.1.0`.** `2.0.0` itself was never published, so `2.1.0` is the first `2.x`
artifact and it carries everything described here plus the interpreter SPI
([plugin-interpreter.md](plugin-interpreter.md)).

Recompiling is usually the whole migration. Most plugins need one dependency bump and nothing else.

## 1. Bump the coordinate and the manifest

```kotlin
dependencies {
    compileOnly(platform("io.github.tyron12233:plugin-bom:2.1.0"))   // was 1.3.0
    compileOnly("io.github.tyron12233:plugin-api")
    compileOnly("io.github.tyron12233:platform-core")
}
```

```toml
# res/raw/codeassist_plugin.toml
apiVersion = 3   # was 2
```

Compile against the Kotlin version the IDE was built with, as before.

## 2. What actually changed

### The project model's vocabularies are open

`ContentRole`, `PlatformKind`, `LibraryKind`, `LanguageLevel`, `DependencyScope` and
`ClasspathEntryKind` are no longer enums. They are value types (`DependencyScope` a plain class) whose
built-in constants live on the companion, so a plugin can name a value of its own:

```kotlin
val HEADERS = ContentRole("cxx-headers")
val PYTHON = PlatformKind("PYTHON")
```

**The constants, `values()`, `entries` and `valueOf` all still work.** `ContentRole.SOURCE`,
`DependencyScope.API`, `LanguageLevel.values()`, `LibraryKind.valueOf("JAR")` compile unchanged.

The one thing that breaks is an **exhaustive `when`**, which now needs an `else`:

```kotlin
// before: exhaustive over the enum
when (role) {
    ContentRole.SOURCE -> "src"
    ContentRole.RESOURCE -> "res"
    // ... every constant
}

// after: a role you do not recognize is some other plugin's
when (role) {
    ContentRole.SOURCE -> "src"
    ContentRole.RESOURCE -> "res"
    else -> null
}
```

Two behavioural notes:

- `valueOf` is now **total**. An unrecognized name is a value some plugin owns, not an exception, so
  code relying on `valueOf` to *validate* input should check against `entries` instead.
- `DependencyScope` carries classpath semantics (`onCompile`/`onRuntime`/`onTest`) that a name cannot
  recover, so a scope of your own should be registered:
  `DependencyScope.register(DependencyScope("LINK_ONLY", "linkOnly", onCompile = false, ...))`.
  Without that it still round-trips by name, but is re-derived permissively when a project that used it
  is loaded.

Persisted spellings did not change. `ContentRole.SOURCE` is still written as `java` in `module.toml`, a
source set's scope still as `IMPLEMENTATION`, a language level still as `JAVA_17`. No project needs
migrating.

### The model registries moved into the published SPI

`FacetCodecRegistry`, `ModuleTypeRegistry`, `UnknownModuleType`, `ProjectTemplateRegistry`,
`FileIconRegistry` and `FacetData` moved from `dev.ide.model.impl` (in the unpublished
`:project-model-impl`) to `dev.ide.model` in `project-model-api`. A plugin could not reach them before,
so this only adds surface. Update the import if you had a copy of them:

```kotlin
import dev.ide.model.FacetCodecRegistry     // was dev.ide.model.impl.FacetCodecRegistry
```

### `Module` no longer asserts every module is a JVM module

| Member | Change | What to do |
| --- | --- | --- |
| `Module.dir` | **new** | Use it for "where does this module live". |
| `Module.outputDir` | now `VirtualFile?` | Handle null, or require it explicitly if your build needs one. |
| `Module.classpath(...)` | now defaulted | No change; it answers `ClasspathSnapshot.EMPTY` for a module whose toolchain has no classpath. |

If you derived the module directory from the output directory, stop:

```kotlin
// before, and wrong for any module whose output path was not two levels deep
val moduleDir = Paths.get(module.outputDir.path).parent.parent

// after
val moduleDir = Paths.get(module.dir.path)
```

`BuildContext.buildDir(module)` is unchanged and still returns `<moduleDir>/build`.

### `CompilationContext` gained defaults and an attribute bag

Only `sourceRoots` is required now. `classpath` and `bootClasspath` default to `ClasspathSnapshot.EMPTY`,
`languageLevel` to `LanguageLevel.DEFAULT`, `outputDir` to null, `processors` to empty. An existing
context that overrides all of them keeps compiling; `outputDir` becoming nullable **narrows** rather than
breaks, because Kotlin lets an override tighten a `val`'s type.

New alongside it: `CompilationContextProvider` on `COMPILATION_CONTEXT_PROVIDER_EP`, so a plugin supplies
its own language's analysis inputs instead of receiving the model's JVM reading of a module. See
[Support a language the IDE has never heard of](writing-plugins.md#support-a-language-the-ide-has-never-heard-of).

### New capabilities

`lang.backend`, `model.moduleType` and `model.facet`. Declare them if your plugin teaches the editor a
language, contributes a module type, or attaches its own module configuration. All three need an
`entryPoints` class, like the other engine-facet capabilities.

## 3. Checklist

- [ ] `plugin-bom` bumped to `2.1.0`, manifest `apiVersion = 3`.
- [ ] Any exhaustive `when` over the six vocabularies has an `else`.
- [ ] Any `valueOf` used for validation checks `entries` instead.
- [ ] A dependency scope of your own is passed to `DependencyScope.register`.
- [ ] `outputDir.parent.parent` replaced with `Module.dir`.
- [ ] `Module.outputDir` reads handle null.
- [ ] Capabilities list updated if you contribute a language, module type or facet.
