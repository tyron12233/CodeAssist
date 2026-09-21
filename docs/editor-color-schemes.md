# Editor color schemes

The editor's colors are a user-editable scheme, not a fixed palette in the theme source. This describes the
model, the file format, where a scheme is stored, and how a language adds colors of its own.

## What a scheme is

A scheme is a sparse map from an **attribute key** to a **style**, held in two variants — one for dark mode,
one for light. The app's theme mode picks which variant is active.

```
EditorColorScheme(id, name, builtIn, dark: Map<String, AttributeStyle>, light: …, basedOn)
AttributeStyle(foreground, background, bold, italic, underline, strikethrough, inheritParent)
```

Every field of a style is nullable, and an absent key is not "no color" but "no opinion". That is what keeps
a scheme small and what keeps it working as the IDE learns to distinguish constructs it had never heard of:
a palette that says nothing about suspend functions still colors one.

## Attributes and the fallback chain

An attribute (`dev.ide.ui.theme.colors.ColorAttribute`) is a key, a display name, a group, an optional
**parent**, and its own default for each variant. `ColorAttributes` is the process-wide registry; the shipped
set is in `BuiltInColorAttributes`.

Resolving a key walks, most specific first:

1. the scheme's override for the key,
2. the live theme's value for it (chrome attributes only — see below),
3. the attribute's own default,
4. the same three for its parent, and its parent's parent, …,
5. the root `text` foreground, as a floor for anything whose chain reaches it.

Each level fills only the fields the levels below left unset, so a style can inherit a color and still add
italic. `inheritParent` on an override skips step 3 for that key, which is the only way to say "color an
extension function like any other function" — without it, clearing the override just falls back to the
distinct color the attribute ships with.

Resolution is eager: `ResolvedColorScheme` flattens every registered attribute once, when the theme is built,
so the editor's per-line styling is a map lookup rather than a walk.

Groups, in the order the editor shows them: **Code** (language-neutral), **Modifiers**, **Kotlin**, **XML**,
**Markdown**, **Editor**, **Gutter**, **Diagnostics**.

### A finer entry starts out invisible

Most entries are a refinement of a coarser one: `keyword.control` under `keyword`, `type.interface` under
`type`, `punctuation.operator` under `punctuation`. Those ship with **no color of their own**, so they
render exactly as their parent until a scheme separates them. Adding one therefore changes nothing about
how anyone's editor looks and everything about what they can change, which is the only way to keep adding
them without repainting people's code on every update. `EditorColorSchemeTest` asserts it.

The bundled presets do separate several, because the palettes they port genuinely distinguish them:
Solarized colors control flow apart from other keywords and operators apart from brackets, One and GitHub
color operators, and all four give doc comments their own tone. Picking a preset is how you see the
granularity without setting it up yourself.

### Chrome attributes are theme-derived until pinned

The caret, selection, current line, gutter and diagnostic colors track the active Material scheme — including
Material You wallpaper color. Making them scheme attributes with fixed defaults would have severed that, so
they are attributes with *no* default: `CodeAssistTheme` supplies the live theme's value through
`SchemeDefaults`, and a scheme only overrides it if it wants to. An untouched install therefore renders
exactly as it did before schemes existed, while Solarized brings its own paper.

## How the two highlighting layers map onto attributes

Both are written against the same `ColorKeys`, so they cannot disagree about what a construct is.

**Lexical.** `tokenColorKey(profile, tokenType)` (beside the scanner, in `dev.ide.ui.editor.core`) maps a
scanner token to an attribute *per `SyntaxFamily`* — a `TYPE` is a class name in a brace language and a tag
name in XML. That is what gives XML and Markdown their own editable colors. A profile can override the
mapping per token type through `EditorLanguageProfile.tokenColorKeys`.

`TokenType` is a set of lexical shapes rather than language concepts, because one set serves every scanner.
Beyond the original nine it distinguishes:

| Token type | Was folded into | Gives |
| --- | --- | --- |
| `KEYWORD_CONTROL`, `KEYWORD_MODIFIER` | `KEYWORD` | control flow and modifiers apart from the rest |
| `DOC_COMMENT` | `COMMENT` | KDoc and Javadoc apart from ordinary comments |
| `CHAR`, `RAW_STRING` | `STRING` | char literals and triple-quoted strings apart from strings |
| `OPERATOR`, `BRACKET`, `SEPARATOR` | `PUNCT` | the symbols that compute, nest and separate |
| `TAG_DELIMITER`, `NAMESPACE` | the tag/attribute name | `<`, `>`, `/>`, `:` and the `android` of `android:id` |
| `ENTITY`, `PROLOG`, `CDATA` | nothing at all | `&amp;`, the XML prolog, `<!DOCTYPE`, CDATA sections |
| `EMPHASIS` | nothing at all | Markdown bold and italic |

The control/modifier split comes from one shared table (`CONTROL_WORDS`, `MODIFIER_WORDS`) applied to any
word a profile already calls a keyword, so a contributed brace language gets it for free; a language that
disagrees points `KEYWORD_CONTROL` back at `keyword` through `tokenColorKeys`.

**Semantic.** `semanticColorKey(kind)` (in `SchemeKeyMapping`) maps an open highlight-SPI kind to a base
attribute. The analyzer already tells `class` from `interface` from `enum` from `object`, a member function
from a top-level one from a constructor, and a field from a property; the mapping keeps those apart instead
of collapsing them, each inheriting the coarse attribute it used to be.

`modifierColorKeys` lists the attributes layered over the base for a token's modifiers, in the
editor's long-standing precedence order (suspend beats composable beats extension; `static` and
`deprecated` only add a font style). A kind the shell does not ship is looked up in the registry under its
own name, so a language can colour a construct the scanners cannot see by registering an attribute and
emitting a kind with the same key. Only when neither half knows the kind does it resolve to null, and the
lexer's coloring shows through — always a defensible fallback, because the lexer already ran.

## The file format

A scheme exports as one small JSON document (`ColorSchemeJson`). The reader is hand-written rather than a
dependency: this module also builds for iOS, and the schema is one fixed, flat shape.

```json
{
  "schema": 1,
  "id": "midnight",
  "name": "Midnight",
  "basedOn": "one",
  "dark": {
    "keyword": { "fg": "#C678DD", "bold": true },
    "kotlin.function.extension": { "inherit": true, "italic": true },
    "editor.selection": { "bg": "#553E4451" }
  },
  "light": { "keyword": "#A626A4" }
}
```

Colors are `#RGB`, `#RRGGBB` or `#AARRGGBB`, with or without the `#`. A bare string is shorthand for a
foreground. Only overrides are written, never resolved values — writing the resolved values would freeze
every fallback on the day the file was exported. Keys are sorted, so a scheme kept in version control diffs
only when it changes.

## Where schemes live

`ColorSchemeStore` keeps them on the flat app preference store (`preference` / `setPreference`), which is the
one storage seam every host implements — `prefs.properties` on desktop and Android, `NSUserDefaults` on iOS —
so schemes behave identically on all three with no per-host code.

```
colorScheme.active       the id being rendered
colorScheme.ids          comma-separated user scheme ids
colorScheme.def.<id>     the scheme's JSON
```

The presets (`CodeAssist`, `Darcula`, `One`, `Solarized`, `GitHub`) are code, not storage. They are read-only;
editing one duplicates it first, which is what keeps "Reset" meaningful. Deleting blanks the stored document
as well as unindexing it, because a preference store can only be written to, not deleted from.

## The editor screen

Settings → **Editor Colors** (`ColorSchemeScreen`). The attribute list is generated from the registry rather
than written out, so an attribute registered anywhere in the app appears grouped and editable with no change
to the screen.

Edits apply and persist immediately. A color is judged by looking at it, so the preview has to be live; once
it is live, a Save button only adds a way to lose the work. The presets being read-only and Reset being one
tap away covers the risk.

The preview is a staged mock editor, not the real `CodeEditor`: a current line, a caret, a selection, an
error squiggle, the gutter and its border only appear in a real editor when a real project, caret and
analysis pass put them there, and the mock shows all of them at once. Its samples are pre-tagged
(`[[key|text]]`, with `+` layering modifier attributes on one run) rather than live-highlighted, because
the semantic half needs a backend — and because tagging makes the preview navigable: tapping a token opens
the attribute that drew it.

Import and export go through the clipboard on every host, and additionally through the platform file picker
where the host has one (`FileActions.pickFile` in, `SettingsService.writeSharedFile` + `exportFile`/`share`
out). An import that collides with an existing scheme is renamed, never merged over.

## Adding attributes for a language

A language's own constructs deserve their own entries. Without them it has to borrow: the shared scanners
name their token types for a brace language, so a directive, a pragma or a shader qualifier arrives as
somebody else's "annotation", indistinguishable from a real one and impossible to recolor on its own.

### From a plugin

`plugin-ui-api` carries the published form (SPI `3.0.0`). Register the attribute, then point the language's
token types at it:

```kotlin
class GlslUiPlugin : UiPlugin {
    override val id = "com.example.glsl"

    override fun contribute(ui: UiRegistration) {
        ui.colorAttribute(
            ColorAttribute(
                key = "glsl.qualifier",            // namespaced: it is what a scheme records against
                title = "Storage qualifier",
                group = "GLSL",                    // its own section in Settings
                parent = ColorAttributeKeys.KEYWORD,
                darkColor = 0xFF4EC9B0, lightColor = 0xFF267F6E, bold = true,
            )
        )
        ui.editorLanguage(
            EditorLanguage(
                id = "glsl",
                suffixes = listOf(".glsl", ".frag", ".vert"),
                syntax = SyntaxStyle.C_FAMILY,
                keywords = GLSL_KEYWORDS,
                tokenColorKeys = mapOf("ANNOTATION" to "glsl.qualifier"),
            )
        )
    }
}
```

```toml
# res/raw/codeassist_plugin.toml
capabilities = ["ui.editorLanguage", "ui.colorAttribute"]
```

Both calls are optional and independent. A language that registers neither is still colored by its
`SyntaxStyle` family exactly as before.

The token-type names are the scanner's: `KEYWORD`, `STRING`, `COMMENT`, `NUMBER`, `ANNOTATION`, `FUNC`,
`TYPE`, `PUNCT`, `PROPERTY`. Anything else in the map is dropped at the bridge rather than carried as a
mapping the host would never consult, and a key nothing registered falls back to the family's own mapping —
so a typo costs the custom color, never the coloring.

**A token mapping can only separate what the scanner already separates.** The scanners distinguish a good
deal (see the table above), but a C preprocessor directive still arrives as a `KEYWORD` like every other
keyword, so no mapping can pull those two apart. That construct is the semantic layer's to colour: a
`LanguageBackend` emits a highlight kind named after the attribute, and an unrecognized kind is looked up
in the registry under its own name before being dropped.

```kotlin
ui.colorAttribute(ColorAttribute(key = "cpp.directive", title = "Preprocessor directive", group = "C/C++"))
// …and from the language backend's semantic highlighter:
SemanticToken(range, HighlightKind("cpp.directive"), emptySet())
```

A group id nothing declared gets a section of its own, sorted after the shell's, so naming `group = "GLSL"`
is all it takes. Registering under a key the IDE already uses **replaces** that entry's defaults, which is
why this is a declared capability; disposing the registration puts the built-in back.

### From inside the app

Built-in languages and anything else compiled into the IDE use the registry directly, with the same shape:

```kotlin
val registration = ColorAttributes.registerAll(
    listOf(
        ColorAttribute(
            key = "mylang.directive",
            title = "Directive",
            group = ColorGroups.CODE,
            parent = ColorKeys.KEYWORD,
            defaultDark = AttributeStyle(foreground = Color(0xFFCC7832), bold = true),
            defaultLight = AttributeStyle(foreground = Color(0xFF0033B3), bold = true),
        ),
    ),
)

EditorLanguageProfile(
    id = "mylang",
    suffixes = listOf(".mylang"),
    syntax = SyntaxFamily.C_FAMILY,
    tokenColorKeys = mapOf("ANNOTATION" to "mylang.directive"),
)
```

Either way, because the attribute declares its own defaults it looks right in every scheme — including a
user's hand-built one, which could not have named it — and because it declares a parent, a scheme that
recolors `keyword` moves it along until the user says otherwise.

### Where the model lives, and why

The scheme model is in `ide-ui-api`, not in `ide-ui-core` with the theme it feeds. `ide-ui-api` is where the
plugin contribution model and the bridge from `plugin-ui-api` are, and both sit below the UI; putting the
registry anywhere above them would have meant an installed plugin could be colored but could not say how.
The two pieces that genuinely belong to the layers above stayed there: `ResolvedColorScheme.toSyntaxColors()`
is with the theme tokens, and `tokenColorKey(profile, TokenType)` is beside the scanner whose token types it
reads.

## Tests

- `EditorColorSchemeTest` (`:ide-ui-core:desktopTest`) — resolution, inheritance, `inheritParent`, modifier
  layering, chrome staying unset, and the default scheme reproducing the shipped palette value for value.
- `ColorSchemeStoreTest` — the JSON round trip (including alpha and export stability), hex parsing, and the
  store's create/duplicate/import/delete behaviour on a preference API that cannot delete.
- `ColorSchemeSamplesTest` (`:ide-ui-screens:desktopTest`) — every tagged key is registered, the samples
  between them show every non-chrome attribute, and the staged caret/selection/squiggle land on real lines.
- `ColorSchemeSnapshot` (`:ide-ui-screens:desktopTest`) — every preset rendered in both variants, plus the
  generated attribute list, to `$TMPDIR/codeassist-snapshots`.
- `ExternalUiPluginTest.colorAttributeAndItsTokenMappingAreCarriedOver` (`:ide-ui:desktopTest`) — the
  published SPI's attribute and token mapping crossing the plugin bridge intact, the `0xAARRGGBB` colors
  included.
