# Migrating a plugin to SPI 3.0.0

SPI `3.0.0` changes what an already-compiled plugin **links** against. Nothing here stops a plugin
compiling, and nothing here was designed away: the SPI's platform-free core moved into a new published
artifact and the index layer became common code, which rewrote 42 members of the surface published at
`2.10.0` without touching a line of anyone's source.

**Recompiling is the whole migration.** Bump the coordinate, bump `apiVersion`, rebuild. Most plugins change
nothing else; the sections below are for the few that name one of the moved members directly.

`PLUGIN_API_VERSION` moves from `3` to `4` in the same release, so **a plugin built against `2.10.0` is
refused at the gate** with the mismatch on its row, rather than loading and throwing `NoSuchMethodError` the
first time a user reaches the feature. That refusal is the point of the bump: a binary break that is only
discovered by a user is the worst version of this problem.

## 1. Bump the coordinate and the manifest

```kotlin
dependencies {
    compileOnly(platform("io.github.tyron12233:plugin-bom:3.0.0"))   // was 2.10.0
    compileOnly("io.github.tyron12233:plugin-api")
    compileOnly("io.github.tyron12233:platform-core")
}
```

```toml
# res/raw/codeassist_plugin.toml
apiVersion = 4   # was 3
```

Compile against the Kotlin version the IDE was built with, as before.

### Five artifacts are now published as multiplatform components

`model-api`, `platform-core`, `vfs-api`, `language-api` and `project-model-api` build for iOS as well as the
JVM, and `3.0.0` is the first release that publishes them that way: a root coordinate carrying Gradle module
metadata, plus one component per target (`-jvm`, `-iosarm64`, `-iossimulatorarm64`).

A plugin built with Gradle needs no change for this. Gradle reads that metadata and resolves the JVM variant
from the plain `io.github.tyron12233:platform-core` coordinate exactly as it did at `2.10.0`, and so does the
IDE's own on-device resolver. A build that resolves POMs alone (Maven, or any tool that ignores Gradle module
metadata) sees the root coordinate as the shared metadata rather than as JVM classes, and has to name the
`-jvm` artifact instead.

## 2. Why you did not see this coming

Every change below is source-compatible and binary-breaking, which is the shape Kotlin makes easy to ship by
accident:

- a top-level function that moves to another file in the same package changes its facade class, so
  `ProjectModelKt.module` becomes `JvmProjectModelKt.module` and every compiled caller stops resolving;
- a constructor replaced by a same-named factory function keeps callers compiling, and changes their
  compiled form from `NEW` + `<init>` to a static call;
- a parameter type that widens to an interface (`java.io.DataOutput` to `DataWriter`) keeps every
  implementation compiling and changes the descriptor of every override.

The build passes, the plugin loads, and the failure arrives under the user's hands. `:spi-compat` exists
because of this release: it reads the compiled surface of every published module and holds it against a
baseline checked in beside the module, so the next one of these is a failed test in the same review as the
change that caused it. See [plugin-system.md](plugin-system.md#what-the-published-surface-promises-spi-compat).

## 3. What actually changed

### `model-api` is new, and is where the platform-free core lives

The symbol model, the DOM, `VirtualFile`, `ContentHash`, `Coordinate`, the portable filesystem and the index
contracts moved out of `platform-core` into a new published artifact, `model-api`, so that they can be used
from code that does not run on a JVM.

**Your imports do not change.** `platform-core` exposes `model-api` as an `api` dependency, so anything that
resolved before still resolves, and the BOM carries the new coordinate. Name it explicitly only if you want
the core without the rest:

```kotlin
compileOnly("io.github.tyron12233:model-api")
```

### The index's persistence and query surface

This is where most of the 42 members are. An index extension that only declares an `IndexId`, an
`InputFilter` and a value type needs nothing but a recompile; the members below are the ones whose
signatures changed.

| Member | `2.10.0` | `3.0.0` |
| --- | --- | --- |
| `Externalizer.write` | `write(out: java.io.DataOutput, value: V)` | `write(out: DataWriter, value: V)` |
| `Externalizer.read` | `read(inp: java.io.DataInput): V` | `read(inp: DataReader): V` |
| `IndexInput.sourcePath` | `java.nio.file.Path?` | `String?` |
| `IndexQueries.exactAll` / `prefixAll` / `fuzzyAll` | extensions on `IndexService` | extensions on `IndexQueries`, in `model-api` |

`DataWriter`/`DataReader` (`dev.ide.platform`) are the same six operations `DataOutput`/`DataInput` gave you
under those names, and **the bytes an externalizer writes are unchanged**: a persisted index written by the
old shape is read by the new one. An implementation usually needs only its parameter types swapped:

```kotlin
// before
override fun write(out: java.io.DataOutput, value: Symbol) {
    out.writeUTF(value.name); out.writeInt(value.offset)
}

// after
override fun write(out: DataWriter, value: Symbol) {
    out.writeUTF(value.name); out.writeInt(value.offset)
}
```

`sourcePath` became a `String` because a path is a name, not a filesystem handle, on a host that has no
`java.nio.file`. Use `dev.ide.platform`'s path helpers (`parentPath`, `fileName`, `resolvePath`) where you
used `Path`, or build a `Path` from it on the JVM if that is what you need.

The bulk query helpers now hang off `IndexQueries` rather than `IndexService`. `IndexService` still IS an
`IndexQueries`, so the call site is unchanged; only the facade class it compiled into moved.

### Top-level functions that moved file

| Call | Now in |
| --- | --- |
| `module(...)`, `workspace(...)`, `contentRootsFor(...)` | `JvmProjectModelKt` (was `ProjectModelKt`) |
| `SYNTHETIC_CLASS_EP` | its file moved, so its facade class did |

Source is unchanged: the imports and the call sites are the same. Only a recompile is needed.

### `Topic(name, listenerType)` is a function, not a constructor

A topic now carries its own fan-out so it can deliver without `java.lang.reflect.Proxy`:

```kotlin
val VFS_CHANGES = Topic<VfsListener>("vfs.changes") { listeners ->
    VfsListener { e -> deliverToAll(listeners) { it.onEvents(e) } }
}
```

The old two-argument spelling still compiles on the JVM, as a function with the same name in
`dev.ide.platform`:

```kotlin
val VFS_CHANGES = Topic("vfs.changes", VfsListener::class.java)   // still fine on the JVM
```

but the compiled call changed from `NEW Topic` + `<init>` to a static call, so this one needs the recompile
like the rest. Prefer the fan-out form in any code that might run off the JVM.

### `EditorLanguage` gained a parameter

`3.0.0` also **adds** to the SPI, and one addition lands inside a class published at `2.10.0`:
`dev.ide.plugin.ui.EditorLanguage` gained a `tokenColorKeys` parameter. Source-compatible (it has a
default), binary-incompatible (the constructor descriptor changed), which is the same shape as everything
above and is covered by the same bump.

```kotlin
ui.colorAttribute(
    ColorAttribute(
        key = "glsl.qualifier", title = "Storage qualifier", group = "GLSL",
        parent = ColorAttributeKeys.KEYWORD,
        darkColor = 0xFF4EC9B0, lightColor = 0xFF267F6E, bold = true,
    )
)
ui.editorLanguage(
    EditorLanguage(
        id = "glsl", suffixes = listOf(".glsl"),
        tokenColorKeys = mapOf("ANNOTATION" to "glsl.qualifier"),   // new in 3.0.0
    )
)
```

The editor's colors became a user-editable scheme in this release, and these two calls are how a
contributed language takes part in it: the attribute puts the construct in Settings → Editor Colors with a
color and a font style the user can set, and the mapping points the scanner's output at it instead of at a
built-in's "annotation". Declare `ui.colorAttribute` in the manifest's capabilities. Nothing is required —
a language that registers neither is still colored by its `SyntaxStyle` family exactly as before.
See `docs/editor-color-schemes.md`.

## 4. What happens if you do not migrate

`ExternalPluginLoader` compares the manifest's `apiVersion` with the host's on strict equality, so a plugin
declaring `3` is not loaded at all by an IDE that loads `4`:

```
built for plugin API 3, this version of the IDE loads API 4
```

That text appears on the plugin's row in Settings. Nothing is instantiated, nothing is registered, and the
user is told why by the IDE rather than by a stack trace. Declaring `apiVersion = 4` without recompiling
against `3.0.0` defeats that: the gate passes and the linkage error comes back.

## 5. Checklist

- [ ] `plugin-bom` bumped to `3.0.0`, manifest `apiVersion = 4`.
- [ ] Rebuilt against the new artifacts (not just re-declared).
- [ ] `Externalizer` implementations take `DataWriter`/`DataReader`.
- [ ] Anything that read `IndexInput.sourcePath` as a `Path` handles a `String`.
- [ ] Any `EditorLanguage(...)` call recompiled (its constructor descriptor changed).
- [ ] Installed on a build of the IDE that loads API `4`, and the plugin's row shows it enabled rather than
      refused.
