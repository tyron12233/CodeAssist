# Localization (UI strings)

The IDE UI (`:ide-ui`, Compose Multiplatform `commonMain`) is localized through the Compose
resources system. All user-facing text lives in string resources so it can be translated per
locale; **no user-facing string is hardcoded in a composable**.

## Where strings live

```
ide-ui/src/commonMain/composeResources/
  values/strings.xml         # default (English) — the source of truth for keys
  values-zh/strings.xml      # Chinese translation
  values-<lang>/strings.xml  # add a locale by adding a directory
```

A locale file only needs the keys it translates. Any key missing from `values-<lang>/` falls
back to the same key in `values/`, so a partial translation is safe and English shows through
until a translator fills the gap. New keys are added to `values/strings.xml` only; translators
add the matching entries to each locale later.

The generated accessor class is `dev.ide.ui.generated.resources.Res` (configured in
`ide-ui/build.gradle.kts` under `compose.resources { ... }`, `publicResClass = false` so it stays
an internal `dev.ide.ui` detail).

## Using a string in code

Each key is a generated top-level property that must be imported individually:

```kotlin
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.save
import org.jetbrains.compose.resources.stringResource

Text(stringResource(Res.string.save))
```

`stringResource` and `pluralStringResource` are `@Composable`. Call them inside a composable (or a
composable lambda). For a Kotlin keyword used as a key, backtick the import and use:
`` import dev.ide.ui.generated.resources.`continue` `` then `` stringResource(Res.string.`continue`) ``.

### Format arguments

Use positional `%1$s` / `%1$d` placeholders, not string interpolation:

```xml
<string name="run_building_module">Building %1$s…</string>
```
```kotlin
Text(stringResource(Res.string.run_building_module, moduleName))
```

### Counts (plurals)

```xml
<plurals name="modules">
    <item quantity="one">%1$d module</item>
    <item quantity="other">%1$d modules</item>
</plurals>
```
```kotlin
import org.jetbrains.compose.resources.pluralStringResource
Text(pluralStringResource(Res.plurals.modules, count, count))
```

### Non-composable call sites

When a string is needed outside composition (a suspend function, a coroutine, a data layer), use
the suspending accessors instead of `stringResource`:

```kotlin
import org.jetbrains.compose.resources.getString
val message = getString(Res.string.some_error)          // suspend
// getPluralString(Res.plurals.some_count, n, n) for plurals
```

Prefer hoisting: resolve the string in the composable with `stringResource` and pass the `String`
down, rather than threading a suspend call through non-UI code.

## Key naming

- `snake_case`, lowercase, derived from meaning, not from location (`dep_no_results`, not
  `screen3_text1`).
- Generic single-word labels shared across the app use a bare key: `save`, `close`, `cancel`,
  `delete`, `add`, `remove`, `rename`, `edit`, `apply`, `retry`, `copy`, `search`, `back`,
  `error`, and so on. Reuse an existing bare key rather than minting a synonym.
- Feature-specific strings are prefixed with a short feature tag so keys stay grouped and never
  collide: `dep_` (dependencies), `modcfg_` (module config), `buildc_` (build console),
  `filetree_` (file navigator), `settings_`, `keystore_`, `sdk_`, `run_`, `preview_`, and so on.

## What is NOT a localized string

Do not route these through `strings.xml`:

- Identifiers used as map/lookup keys, route/command/task IDs, setting keys
  (`settings.<page>.<key>`), enum names, node/block/valueKind kinds.
- Icon vector data, syntax token names, language keywords.
- Developer log lines, diagnostic codes, class/package names, file paths and extensions.
- Content that comes from the backend as data (build log output, program stdout/stderr,
  completion item text, resolved coordinates).
- `contentDescription = null`.

## Rule for new UI code

Any new user-facing text (a label, title, message, placeholder, tooltip, empty state) gets a key
in `values/strings.xml` and is read with `stringResource` / `pluralStringResource`. Do not commit
a hardcoded user-facing literal in a composable.
