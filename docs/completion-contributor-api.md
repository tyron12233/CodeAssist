# Completion Contributor API

An IntelliJ-style, cross-language completion pipeline. Code completion is no longer a single
per-language engine: it is a list of **contributors** that add, filter, decorate, or re-rank items over
one shared result set, ranked by composable **weighers**. A plugin extends completion for any language by
registering a contributor — without forking a language backend.

This is the editor-side counterpart to the analysis/quick-fix SPI (`docs/analysis-api.md`): one neutral
pipeline, many pluggable producers.

## The pipeline

`IdeServices.complete(file, text, offset)` builds a `CompletionParams` and hands it to the
`CompletionEngine` (`ide-core`, `dev.ide.core.completion`). The engine:

1. Collects every `CompletionContribution` from `COMPLETION_CONTRIBUTOR_EP`, plus the per-call
   contributions the file's language backend publishes via `SourceAnalyzer.completionContributions()`
   (its own completion is just one such contributor — there is no separate `CompletionService`).
2. Keeps the ones that **apply**: `languages` empty or contains the file's `LanguageId`, **and** the
   contribution's DOM `pattern` accepts `params.position` (when the file couldn't be parsed,
   `position == null`, patterns can't be evaluated so language-matching contributors all run).
3. Runs them in ascending `order` over one shared `CompletionResultSet`. A contributor may `stopHere()`
   to skip the rest.
4. Ranks the merged set with the `CompletionWeigher`s (built-in + `COMPLETION_WEIGHER_EP`), de-dupes by
   `(kind, label, insertText)`, and caps the list.

There is **no privileged backend** — the JDT/Kotlin/XML backend is just the contributor that runs first
(`CompletionEngine.BACKEND_ORDER = 0`), so cross-cutting contributors can filter or decorate its output.

## Writing a contributor

```kotlin
import dev.ide.lang.completion.*
import dev.ide.lang.patterns.DomPatterns

object MyContributor : CompletionContributor {
    override val id = "myplugin.greetings"

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        if (!params.prefixMatches("greet")) return
        result.addElement(
            CompletionItem(label = "greet", insertText = "greet()", kind = CompletionItemKind.METHOD),
        )
    }
}

// Register it (in your plugin's setup, against the host ExtensionRegistry):
registry.register(
    COMPLETION_CONTRIBUTOR_EP,
    CompletionContribution(
        contributor = MyContributor,
        languages = setOf(LanguageId("kotlin")),          // empty = every language
        pattern = DomPatterns.nameRef(),                  // only at a bare name reference
        order = 100,                                      // after the language backend (0)
    ),
    PluginId("myplugin"),
)
```

### `CompletionParams`

What every contributor receives for one completion:

| field | meaning |
|-------|---------|
| `document`, `offset`, `prefix` | the live buffer, caret, and identifier prefix under it |
| `language` | the file's `LanguageId` |
| `trigger` | `Explicit` (Ctrl-Space) or `TypedChar('.')` |
| `replacementRange` | the span an accepted item replaces (the prefix); the backend can override it |
| `position` | the deepest neutral `DomNode` at the caret — the pattern subject; null if unparsed |
| `parsedFile` | the tolerant `ParsedFile` (for `nodeAt`/`nodesIn`) |
| `scope`, `expectedType` | best-effort resolution (may be null) |
| `typeResolver` | resolve a node's produced type (`analyzer.resolveType`) — for type-gated logic |

`params.prefixMatches(name)` is the common case-insensitive prefix gate.

### `CompletionResultSet` — the four capabilities

The same mutable set is passed to each contributor in turn, so a later contributor sees earlier items:

- **add** — `addElement(item)` / `addAllElements(items)`
- **filter** — `removeIf { it.detail == "deprecated" }`
- **decorate** — `replaceAll { it.copy(insertText = it.insertText + "()") }` (rewrite insert text, docs, caret…)
- **stop** — `stopHere()` (skip the remaining contributors)

Plus `markIncomplete()` (the engine sets `CompletionResult.isIncomplete`, so the editor re-queries as the
prefix narrows) and `setReplacementRange(range)` (the authoritative language backend sets the language's
word boundaries — e.g. XML's `:@?+/`).

## DOM patterns

Target a position with an `ElementPattern<DomNode>` over the neutral DOM (`dev.ide.lang.patterns`), so one
pattern works across JDT / Kotlin / XML:

```kotlin
DomPatterns.nameRef()                                  // a bare name reference
DomPatterns.node(NodeKind.LITERAL)                     // a literal
DomPatterns.nameRef().inside(NodeKind.METHOD_CALL)     // a name anywhere inside a call
DomPatterns.call().withChild(DomPatterns.nameRef().withText("Text"))  // call whose callee is `Text`
StandardPatterns.or(DomPatterns.nameRef(), DomPatterns.memberAccess())
```

`DomNodePattern` conditions: `withKind`, `withText(String|Regex)`, `withTextContaining`, `withParent`,
`inside` (any ancestor), `withChild`, `where { … }`. Combine with `StandardPatterns.or/and/not`.

## Weighers (ranking)

Register a `CompletionWeigher` instead of hardcoding `sortPriority`. The engine sorts by every weigher in
ascending `order`, **higher weight first**:

```kotlin
registry.register(COMPLETION_WEIGHER_EP, object : CompletionWeigher {
    override val id = "myplugin.boostSuspend"
    override val order = 50
    override fun weigh(item: CompletionItem, params: CompletionParams) =
        if (item.detail?.contains("suspend") == true) 1.0 else 0.0
}, PluginId("myplugin"))
```

The one built-in weigher, `SortPriorityWeigher` (order 1000, last tiebreaker), maps the legacy
`CompletionItem.sortPriority` (lower = earlier) to a weight, so existing backend ranking is preserved.
The editor additionally re-tiers the returned list by match quality (exact > prefix > fuzzy) with a stable
sort, so a weigher decides order *within* a match tier.

## Postfix templates

`expr.key` rewrites — `value.var`, `cond.if`, `list.for` — are contributed through
`POSTFIX_TEMPLATE_EP` and driven by the generic `PostfixContributor`, which reconstructs the receiver,
resolves its type via `params.typeResolver`, and emits a `SNIPPET` item that deletes `receiver.` and
expands the rewrite (with tab stops). Scope a template to a language with `PostfixTemplate.languages`
(empty = every language) so a Kotlin `?.let { }` template isn't offered in a Java file.

```kotlin
registry.register(POSTFIX_TEMPLATE_EP, object : PostfixTemplate {
    override val key = "sout"
    override val languages = setOf(LanguageId("kotlin"))
    override val example = "expr.sout → println(expr)"
    override val description = "Print the expression"
    override fun isApplicable(ctx: PostfixContext) = true
    override fun expand(ctx: PostfixContext) = PostfixExpansion(
        edits = emptyList(),                              // extra edits (imports, …)
        snippet = /* the rewrite text, e.g. */ buildSnippet("println(${ctx.expressionText})"),
    )
}, PluginId("myplugin"))
```

The driver convention: `PostfixExpansion.snippet` is the rewrite (it becomes the item's insert text,
driven by `CaretAction.ExpandSnippet`); the driver itself adds the edit that deletes `receiver.`;
`PostfixExpansion.edits` are any extra edits.

## Analyzer-bound contributors

A contributor that needs the language analyzer's resolver/symbol model (not just the neutral DOM) is
published by the backend through `SourceAnalyzer.completionContributions()`. The engine runs these
per-call contributions alongside the EP ones, so they share the analyzer's state instead of re-resolving.

## Built-in contributors and ordering

| contributor | order | role |
|-------------|-------|------|
| `JdtCompletion` / `KotlinCompletion` / `XmlCompletion` | 0 | the language backend's own completion (members, types, scope, its keywords/postfix), published via `completionContributions()` |
| `PostfixContributor` | 5 000 | drives `POSTFIX_TEMPLATE_EP` |
| `BufferWordsContributor` | 10 000 | hippie / word completion from the live buffer, deduped against everything above |

The old per-language `CompletionService` interface is **gone**: each backend's completion is a plain
`CompletionContributor` (`JdtCompletion`, `KotlinCompletion`, `XmlCompletion`). `CompletionRequest` survives
only as the input to the engine-free `complete(...)` runner extensions in `CompletionRunner.kt` (tests + simple drivers).

## Notes

- **Threading** — completion runs on the engine's serialized background lane; `fillCompletionVariants` is
  `suspend`, so a slow contributor can do I/O without blocking, but should honour cancellation.
- **Performance** — completion is a hot path. Prefix-gate early (`params.prefixMatches`), keep patterns
  cheap, and prefer `params.typeResolver`/`parsedFile` over re-parsing.
- **Replacement range** — the engine uses the prefix-derived range unless the authoritative backend calls
  `setReplacementRange`; a plugin contributor normally leaves it alone.

See `dev.ide.lang.completion.Contributor`, `dev.ide.lang.patterns.Patterns`,
`dev.ide.lang.postfix.Postfix`, and `dev.ide.core.completion.CompletionEngine`.
