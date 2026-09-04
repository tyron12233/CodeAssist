# Adding a language to CodeAssist

Every editing feature in CodeAssist reaches source code through one of a handful of SPIs, and none of them
names a specific parser. Java, Kotlin, and XML are three implementations of the same contracts, registered the
same way a language you add would be. This guide covers the whole surface: claiming a file type, parsing into
the neutral DOM, resolution, code completion, indexing, diagnostics and quick-fixes, synthetic classes, the
build pipeline, module types and project templates, and how to ship all of it as one plugin.

It assumes you have read [writing-plugins.md](writing-plugins.md), because everything here is contributed
through the plugin model described there. [language-support.md](language-support.md) is the shorter conceptual
overview of the same subsystem.

Adding a language is a set of registrations. Nothing on this page requires an edit to the host: the analyzer
a module resolves, the editor's coloring and comment handling, and every extension point below are all keyed
by language and resolved from what you register.

**Contents**

1. [Before you begin](#1-before-you-begin)
2. [How a file becomes a language](#2-how-a-file-becomes-a-language)
3. [Step 1: claim the file type](#3-step-1-claim-the-file-type)
4. [Step 2: choose your tier](#4-step-2-choose-your-tier)
5. [Tier 1: a language with no backend](#5-tier-1-a-language-with-no-backend)
6. [Tier 2: the language backend](#6-tier-2-the-language-backend)
7. [Code completion](#7-code-completion)
8. [Editor services](#8-editor-services)
9. [Indexing](#9-indexing)
10. [Diagnostics and quick-fixes](#10-diagnostics-and-quick-fixes)
11. [Synthetic classes](#11-synthetic-classes)
12. [Build integration](#12-build-integration)
13. [Module types, facets, and project templates](#13-module-types-facets-and-project-templates)
14. [Package it as a plugin](#14-package-it-as-a-plugin)
15. [Test it](#15-test-it)
16. [Checklist](#16-checklist)
17. [Appendix A: extension points for language work](#appendix-a-extension-points-for-language-work)
18. [Appendix B: class index](#appendix-b-class-index)

---

## 1. Before you begin

### What "language support" means here

"Support" is not one thing. It is a stack of independent capabilities, and you can stop at any level:

| Capability | What the user gets | What you contribute |
| --- | --- | --- |
| File type | The file opens as its own language instead of being analysed as Java | `FileTypeMapping` |
| Lexical coloring, comments, indent | Keywords colored; Toggle Comment; brace-aware Enter | An `EditorLanguageProfile` |
| Diagnostics | Errors and warnings as you type | An `Analyzer` |
| Parsing and resolution | A DOM, go-to-definition, structure view | A `LanguageBackend` |
| Completion | The completion popup | `CompletionContributor`s |
| Search and auto-import | Go-to-symbol, unimported-name completion | `IndexExtension`s |
| Generated types | Resolution of code that does not exist yet | A `SyntheticClassProvider` |
| Compilation | The language builds | A `BuildPlugin` or a `BuildSystem` |
| New projects | The language appears in Create Project | A `ModuleType` and a `ProjectTemplate` |

The order matters. Claiming the file type is worth doing on its own and takes one line. A full backend is a
large piece of work, so read [Step 2: choose your tier](#4-step-2-choose-your-tier) before starting one.

### Prerequisites

| Requirement | Why |
| --- | --- |
| The CodeAssist repository, buildable locally | A language is a set of Gradle modules in the same build |
| [writing-plugins.md](writing-plugins.md) | Every contribution here is registered through the plugin SPI |
| An error-tolerant parser for your language | Non-negotiable for a backend. See [The error-tolerance contract](#63-the-error-tolerance-contract) |

### The modules involved

| Module | Holds |
| --- | --- |
| [`:language-api`](../language-api) | The DOM, `LanguageBackend`, resolution, completion, and every editor-service SPI |
| [`:index-api`](../index-api) | `IndexExtension` and the query surface |
| [`:analysis-api`](../analysis-api) | Analyzers, diagnostics, quick-fixes |
| [`:build-api`](../build-api) | `BuildSystem`, `BuildPlugin`, `SourceGenerator`, the task engine |
| [`:project-model-api`](../project-model-api) | `ModuleType`, facets, `ProjectTemplate`, `ProjectImporter` |
| [`:lang-xml`](../lang-xml), [`:lang-kotlin`](../lang-kotlin), [`:lang-java`](../lang-java) | Reference implementations |

---

## 2. How a file becomes a language

```
  file on disk
       │
       │  FILE_TYPE_EP          FileTypeMapping(suffixes, LanguageId, order)
       ▼
   LanguageId ─────────────────────────────────────────────────────────────┐
       │                                                                   │
       │  LANGUAGE_BACKEND_EP   backendFor(id) = first backend whose        │  ANALYZER_EP
       │                        `languages` contains id                    │  (analyzers may claim
       ▼                                                                   │   a backend-less language)
  LanguageBackend                                                          │
       │                                                                   │
       │  createAnalyzer(CompilationContext)                               │
       ▼                                                                   ▼
  SourceAnalyzer ──▶ ParsedFile (neutral DOM) ──▶ diagnostics, completion, navigation,
       │                                          folding, formatting, hints, highlighting
       ▼
  IndexExtension entries ──▶ IndexService ──▶ go-to-symbol, auto-import, unresolved checks
```

Three facts about this pipeline shape everything else you write.

**The `LanguageId` is the routing key, not the file suffix.** `IdeServices.languageFor(path)` consults the
`FILE_TYPE_EP` mappings in `order` and returns the first match, falling back to `LanguageId("text")`. Every
later stage dispatches on that id.

**A file with no backend is inert, not mis-parsed.** When no registered backend claims the id, the host hands
the file [`PlainTextAnalyzer`](../ide-core/src/main/kotlin/dev/ide/core/PlainTextAnalyzer.kt), which parses the
whole buffer to one text node and offers nothing. That fallback exists because the previous behaviour was to
route unknown files to the Java analyzer, which reported every line of a `res/raw/notes.txt` as a Java syntax
error.

**Backend selection falls back to the first registered backend.** `backendFor` is
`languageBackends.firstOrNull { language in it.languages } ?: languageBackends.first()`, so registration order
decides the fallback. That is why the Java backends load first and every other language plugin declares
`dependsOn = listOf("jdt-language")`. Your plugin must do the same.

---

## 3. Step 1: claim the file type

This is the smallest useful contribution and it is one registration:

```kotlin
override fun register(reg: PluginRegistration) {
    reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".mylang"), LanguageId("mylang")))
}
```

[`FileTypeMapping`](../language-api/src/main/kotlin/dev/ide/lang/FileType.kt) carries three fields:

| Field | Purpose |
| --- | --- |
| `suffixes` | Matched with `endsWith`, so `.mylang`, `-rules.pro`, and `build.gradle.kts` all work |
| `language` | The `LanguageId` this file routes to |
| `order` | Lowest wins when several mappings match. Built-ins use the default 1000 |

**Why do this even with no backend.** Without a mapping, an unknown suffix resolves to `LanguageId("text")`,
which is usually fine. But a file that lives under a source or resource root and is *not* claimed can end up
analysed by whatever backend owns that root. Both `.pro` (ProGuard keep rules) and `.aidl` exist purely as
mappings for this reason: they keep the Java backend from reporting a valid keep-rule file as broken Java.

**Choosing a `LanguageId`.** It is a `@JvmInline value class` over a string. Use a short lowercase name
(`"aidl"`, `"proguard"`, `"mylang"`). Publish it as a constant on your backend's companion, the way
`XmlLanguageBackend.LANGUAGE_ID` and `KotlinLanguageBackend.LANGUAGE_ID` do, so analyzers and contributors
reference one symbol rather than repeating a literal.

### Coloring, comments, and indentation

The text-level behaviour of a language is one contributed object, an
[`EditorLanguageProfile`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/EditorLanguages.kt):

```kotlin
scope.editorLanguage(
    EditorLanguageProfile(
        id = "mylang",                       // matches the LanguageId the engine routes by
        suffixes = listOf(".mylang"),
        syntax = SyntaxFamily.C_FAMILY,
        keywords = setOf("rule", "given", "then"),
        lineComment = "//",
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
    ),
)
```

That single registration gives the language keyword and string coloring, a working Toggle Comment, and
brace-aware Enter. The profile is contributed from a `UiPlugin` (see
[writing-plugins.md](writing-plugins.md)), because these are editor-side concerns.

| Field | Drives |
| --- | --- |
| `suffixes` | Which files resolve to this profile. Matched with `endsWith` |
| `syntax` | Which shared scanner colors it, and which typing behaviour it gets |
| `keywords` | The words colored as keywords. Only read by `C_FAMILY`; capitalized words are already colored as types, so type names do not belong here |
| `lineComment`, `blockCommentOpen`, `blockCommentClose` | Toggle Comment. Null means the language has no comment of that shape |
| `order` | Lowest wins when two profiles claim a suffix, so a plugin can also override a built-in language |

`SyntaxFamily` picks the shared scanner. It covers coloring, auto-closing, and the Enter handler together,
because those follow from the same lexical shape: a brace language wants smart indent and `{}` auto-close and
`//` comments.

| Family | Shape | Built-in users |
| --- | --- | --- |
| `C_FAMILY` | Braces, `//` and `/* */`, quoted strings, numbers, `@annotations` | Java, Kotlin, AIDL |
| `XML` | Tags, attributes, entities, `<!-- -->`, tag auto-close | XML |
| `HASH_COMMENT` | Whole-line `#` comments, no strings | ProGuard keep rules |
| `MARKDOWN` | Headings, fences, lists, inline code | Markdown |
| `PLAIN` | No coloring, no typing assistance | Anything unclaimed |

AIDL is the proof that `C_FAMILY` is genuinely reusable: it differs from Java only in its keyword set. Note
its set deliberately omits type names such as `String` and `IBinder`, because the shared scanner already
colors capitalized words as types.

This is the cheap, synchronous layer that runs per line while typing. It is not a parser, and it is
independent of whether any `LanguageBackend` handles the language: a tier-1 language gets coloring and
comments from a profile alone. A language that also registers a backend can layer type-aware coloring on top
through [semantic highlighting](#82-semantic-highlighting), which the UI lets win on overlap.

## 4. Step 2: choose your tier

| | Tier 1: no backend | Tier 2: full backend |
| --- | --- | --- |
| You write | An `Analyzer`, optionally a build task and synthetic classes | A parser, DOM adapter, resolver, and completion |
| The user gets | Coloring, error checking, compilation | All of that plus navigation, completion, structure, folding, formatting, hints |
| Effort | Days | Weeks |
| Example | AIDL | XML, Kotlin |

Pick tier 1 when the file is a **declaration whose product is what a developer actually navigates**. That is
the AIDL case: nobody wants go-to-definition inside a `.aidl` file, they want the generated Java interface to
resolve. Tier 1 plus a `SyntheticClassProvider` delivers that at a fraction of the cost.

Pick tier 2 when developers **read and write the language directly** and expect editor intelligence in it.

You can also start at tier 1 and add a backend later. The file type mapping, the analyzer, the indexes, and the
build task all stay as they are; only `LANGUAGE_BACKEND_EP` is new.

---

## 5. Tier 1: a language with no backend

AIDL is the worked example, and it is worth reading end to end because it exercises four different SPIs
without a parser ever touching the neutral DOM.

### 5.1 What AIDL registers

From [`BuiltInPlugins.kt`](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt), inside the
`android-support` plugin:

```kotlin
reg.register(SYNTHETIC_CLASS_EP, AndroidAidlProvider())
// Editor diagnostics for `.aidl`, through the same parser and generator the build runs.
reg.contributeVia { ext, pid -> ext.register(ANALYZER_EP, AidlAnalyzer(), pid) }
// AIDL: its own language id, which keeps JDT from analysing an interface definition as broken Java.
reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".aidl"), LanguageId("aidl")))
```

Three registrations, plus a compile task in the Android build pipeline
([`CompileAidlTask`](../android-support/src/main/kotlin/dev/ide/android/support/tasks/CompileAidlTask.kt)) and
a `CodeLanguage.Aidl` entry for coloring.

### 5.2 Analyzers can claim a language with no backend

This is the mechanism that makes tier 1 possible. `IdeServices` keeps a separate set:

```kotlin
/** Languages some registered Analyzer claims. A language can have inspections without having a backend. */
private val analyzedLanguages: Set<LanguageId> by lazy {
    platform.extensions.extensions(ANALYZER_EP).flatMapTo(HashSet()) { it.languages }
}
```

So a file whose language has an analyzer stays analysable, while an unclaimed `.txt` stays inert. Your analyzer
declares the claim through its `languages` set:

```kotlin
class MyLangAnalyzer : FileAnalyzer {
    override val id = AnalyzerId("mylang")
    override val displayName = "MyLang problems"
    override val languages = setOf(LanguageId("mylang"))
    override val defaultSeverity = Severity.ERROR
    override val tier = AnalyzerTier.SEMANTIC
    override val interestedIn: Set<NodeKind>? = null  // whole file, invoked once
    …
}
```

`interestedIn = null` means "call me once for the whole file". That is the right choice at tier 1: the shared
DOM is a single text node, so there is nothing to dispatch by node kind. You read the raw text instead:

```kotlin
override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
    val text = target.parsed.text().toString()
    val parsed = try {
        MyLangParser.parse(text, target.file.path)
    } catch (e: MyLangSyntaxException) {
        sink.report(lines.rangeAt(e.pos), Severity.ERROR, e.message.orEmpty(), CODE)
        return
    }
    …
}
```

### 5.3 The rule that keeps the editor and the build honest

`AidlAnalyzer` runs **the same parser and the same generator the build task runs**, and reports only what the
generator reports. That is a deliberate design rule, not an implementation detail: two separate checkers drift,
and the failure mode (a file the editor calls clean and the build rejects) is the worst kind. If you have a
compiler for your language, drive the analyzer from it.

Two corollaries visible in `AidlAnalyzer`:

- It runs at `AnalyzerTier.SEMANTIC`, not `SYNTAX`, because resolving a type reference reads the module's other
  `.aidl` files from disk. Tier choice is about cost, and the framework schedules accordingly.
- It surfaces only errors. The compiler's advisory warnings need the full SDK type table, which exists at build
  time and not in the editor, so reporting them there would produce false positives.

### 5.4 Give the generated code back to the editor

A declaration language is only useful if what it generates resolves. See
[Synthetic classes](#11-synthetic-classes): `AndroidAidlProvider` parses a module's `.aidl` files and
contributes exactly the shapes the build will generate, so a `Service` extending `IFoo.Stub` resolves before
any build has run, and nothing appears or disappears once one has.

---

## 6. Tier 2: the language backend

### 6.1 The interface

[`dev.ide.lang.LanguageBackend`](../language-api/src/main/kotlin/dev/ide/lang/LanguageBackend.kt)

```kotlin
interface LanguageBackend {
    val id: String                          // "jdt" | "kotlin" | "xml" | "mylang"
    val languages: Set<LanguageId>
    val capabilities: Set<BackendCapability>
    fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer
}
```

It is deliberately tiny, because the backend object itself is a factory. All the work lives on the
`SourceAnalyzer` it creates, which is **per module** so it can hold that module's incremental state.

A backend is **editor-side only**. Emitting bytecode is the build system's job, and each language module owns
its own build task (`lang-jdt` drives ecj, `lang-kotlin` drives K2). The backend never sees the build. See
[Build integration](#12-build-integration).

A minimal registration, following `XmlLanguageBackend`:

```kotlin
class MyLangBackend : LanguageBackend {
    override val id = "mylang"
    override val languages = setOf(LANGUAGE_ID)
    override val capabilities = setOf(
        BackendCapability.ERROR_RECOVERY,
        BackendCapability.COMPLETION,
    )
    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer = MyLangSourceAnalyzer(ctx)

    companion object { val LANGUAGE_ID = LanguageId("mylang") }
}
```

### 6.2 Capabilities are a contract, not a hint

[`BackendCapability`](../language-api/src/main/kotlin/dev/ide/lang/LanguageBackend.kt) declares which optional
services your analyzer returns. The paired `SourceAnalyzer` property must be non-null exactly when the
capability is present:

| Capability | Analyzer member that must be non-null |
| --- | --- |
| `ERROR_RECOVERY` | (no member; required for the editor and completion to work at all) |
| `INCREMENTAL` | `incrementalParser.reparse` does real incremental work |
| `BINDINGS` | `resolve`, `scopeAt`, `expectedTypeAt` return real results |
| `COMPLETION` | `completionContributions()` is non-empty |
| `SNIPPETS` | Items may carry `CaretAction.ExpandSnippet` |
| `POSTFIX` | Contributes or handles `PostfixTemplate`s |
| `INLAY_HINTS` | `inlayHints` |
| `SIGNATURE_HELP` | `signatureHelp` |
| `SEMANTIC_HIGHLIGHT` | `semanticHighlighter` |
| `CODE_FOLDING` | `folding` |
| `FORMAT` | `formatting` |
| `ORGANIZE_IMPORTS` | `importOrganizer` |

Declare only what you implement. Every optional member defaults to null or empty, so a backend that starts with
`ERROR_RECOVERY` alone is valid and complete.

### 6.3 The error-tolerance contract

This is the single hardest requirement, and it is not optional:

> A `ParsedFile` always covers the whole file even when the source is syntactically invalid, which it almost
> always is while the user is typing. Broken regions are represented as nodes of kind `NodeKind.ERROR` or
> `NodeKind.MISSING`, not by throwing.

Concretely, your parser must:

- never throw from `parseFull`, whatever the buffer contains, including an empty file and a half-typed token;
- produce a root whose range spans `[0, text.length)`;
- represent a region it could not understand as an `ERROR` node it recovered past, and a token it synthesized
  to keep the tree well-formed (a missing `)`) as `MISSING`;
- keep `nodeAt(offset)` meaningful inside those regions, because that is what the caret lands in.

If your parser generator produces a throwing parser, wrap it: parse, catch, and fall back to a tree whose root
holds one `ERROR` child. That is enough for the editor to function while you improve recovery.

### 6.4 The DOM

[`dev.ide.lang.dom`](../language-api/src/main/kotlin/dev/ide/lang/dom/Dom.kt)

```kotlin
interface DomNode {
    val kind: NodeKind
    val range: TextRange       // half-open [start, end) in UTF-16 offsets
    val parent: DomNode?
    val children: List<DomNode>
    fun text(): CharSequence
}

interface ParsedFile : DomNode {
    val file: VirtualFile
    val documentVersion: Long
    val diagnostics: List<Diagnostic>
    fun nodeAt(offset: Int): DomNode          // deepest node containing offset, ERROR/MISSING included
    fun nodesIn(range: TextRange): Sequence<DomNode>
}
```

You do not replace your own tree with this. You **adapt** it: wrap your native nodes in `DomNode`
implementations, usually lazily, so that IDE features never depend on your parser's types.

`NodeKind` is a `@JvmInline value class` over a string, not an enum, precisely so you can add kinds. Reuse the
constants in `NodeKind.Companion` where they fit (`CLASS_DECL`, `METHOD_DECL`, `NAME_REF`, `MEMBER_ACCESS`,
`METHOD_CALL`, `TYPE_REF`, `LITERAL`, `BLOCK`), because cross-cutting consumers key off them:

- analyzers use `interestedIn: Set<NodeKind>` to be dispatched only to the nodes they care about;
- completion patterns (`DomPatterns`) match on kind;
- the block editor's mappings project by kind.

Define your own kinds (`NodeKind("mylang.rule")`) for anything with no neutral equivalent.

`documentVersion` is a cheap staleness key. Consumers compare it against the document snapshot's version rather
than re-parsing to find out whether a tree is current, so it must actually track the snapshot it was built
from.

### 6.5 Incremental parsing

[`dev.ide.lang.incremental`](../language-api/src/main/kotlin/dev/ide/lang/incremental/Incremental.kt)

```kotlin
interface IncrementalParser {
    fun parseFull(snapshot: DocumentSnapshot): ParsedFile
    fun reparse(previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>): ReparseResult
}
```

`parseFull` is the only required half. A correct first implementation of `reparse` is to call `parseFull` and
return `ReparseResult(tree, tree.range, reusedSubtrees = 0)`, which is exactly what `PlainTextAnalyzer` does.
Do that first, measure, and only then implement real incrementality (shift ranges after the edit, widen the
dirty region to the nearest reparsable boundary, reparse that span, reattach unchanged subtrees by reference).

Completion fires on nearly every keystroke, so on a large file this is the difference between a responsive and
an unusable editor. Declare `BackendCapability.INCREMENTAL` only once `reparse` genuinely reuses subtrees.

### 6.6 Resolution

[`dev.ide.lang.resolve`](../language-api/src/main/kotlin/dev/ide/lang/resolve/Resolve.kt) defines four types
your backend implements, and they are the input to both navigation and completion:

| Type | Role |
| --- | --- |
| `Symbol` | A resolved declaration: `name`, `kind`, `type`, `owner`, `modifiers`, `origin`, `declaration()`, `documentation()` |
| `TypeRef` | A resolved type: `qualifiedName`, `typeArguments`, `isAssignableFrom`, `supertypes()`, `members(accessibleFrom)` |
| `Scope` | Names visible at a position: `symbols(filter)`, `resolve(name)`, `enclosing` |
| `ResolveResult` | `Resolved(symbol)` / `Ambiguous(candidates)` / `Unresolved` |

The three `SourceAnalyzer` entry points that surface them:

```kotlin
fun resolve(node: DomNode): ResolveResult          // a reference node to a symbol
fun scopeAt(file: VirtualFile, offset: Int): Scope // visible names, the name-completion candidate set
fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef?  // for ranking
fun resolveType(node: DomNode): TypeRef? = null    // the type an expression PRODUCES
```

`expectedTypeAt` and `resolveType` are opposite directions and both matter. `expectedTypeAt` is what the
*context* wants (`int x = |` wants something int-assignable) and drives completion ranking. `resolveType` is
what an expression *produces* and is used by refactorings such as "introduce variable" to name a declared type
instead of `var`.

Return `ResolveResult.Unresolved` freely. Resolution runs on broken code constantly, and an honest "I do not
know" is correct behaviour, not a failure.

**Where symbols come from.** Source symbols come from other `ParsedFile`s. Binary symbols come from the
module's `ClasspathSnapshot`, which is the same hashed classpath the build uses. Using it rather than deriving
your own is what makes resolution and compilation agree, and it gives you cache invalidation on classpath
change for free.

### 6.7 The compilation context

[`CompilationContext`](../language-api/src/main/kotlin/dev/ide/lang/LanguageBackend.kt) is built from the
project model and handed to `createAnalyzer`:

| Member | Use | Default |
| --- | --- | --- |
| `sourceRoots` | The module's source roots | required |
| `classpath` | Hashed `ClasspathSnapshot`; changing its fingerprint invalidates your caches | `ClasspathSnapshot.EMPTY` |
| `bootClasspath` | The platform SDK jars | `ClasspathSnapshot.EMPTY` |
| `languageLevel` | The target level | `LanguageLevel.DEFAULT` |
| `outputDir` | Where compiled output lands, or null for a language that produces none | `null` |
| `processors` | Annotation processors on the classpath | empty |
| `sourceAttachments` | Library `-sources.jar`s, for parameter names and doc comments. Not compiled | empty |
| `attribute(key)` | A language-specific input the core has no name for | `null` |

Only `sourceRoots` is required. Everything else describes a module the way the *model* can on its own, which
is the JVM reading of one, so a language that has no classpath simply leaves those alone.

### 6.7.1 Supplying your own context

The catch is that the host builds that context with
[`ModuleCompilationContext`](../language-api/src/main/kotlin/dev/ide/lang/ModuleCompilationContext.kt), which
walks the project model: dependencies with `api`/`implementation` export semantics, a platform SDK boot
classpath, a Java language level. No amount of model-walking produces a virtualenv, an include path or a
sysroot. Contribute a
[`CompilationContextProvider`](../language-api/src/main/kotlin/dev/ide/lang/LanguageBackend.kt) and the host
asks you first for the languages you claim:

```kotlin
val PY_INTERPRETER = ContextKey<String>("mylang.interpreter")

object MyLangContexts : CompilationContextProvider {
    override val languages = setOf(MyLangBackend.LANGUAGE_ID)

    override fun contextFor(
        workspace: Workspace, module: Module, language: LanguageId, variant: Set<String>?,
    ): CompilationContext? {
        val facet = module.facets.get(MYLANG_FACET) ?: return null   // not my module after all
        return object : CompilationContext {
            override val sourceRoots = module.sourceSets
                .flatMap { it.contentRoots }
                .filter { PACKAGE_ROOT in it.roles }
                .map { it.dir }

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> attribute(key: ContextKey<T>): T? =
                if (key === PY_INTERPRETER) facet.interpreter as T else null
        }
    }
}

// in Plugin.register:
reg.register(COMPILATION_CONTEXT_PROVIDER_EP, MyLangContexts)
```

The rules, which the host holds you to:

- Only providers claiming the language are asked, in registration order, and the **first non-null answer
  wins**. Returning null means "not mine after all" and falls back to the model-derived context, so a provider
  can handle only the modules it recognizes.
- A provider that **throws is logged and skipped**, never propagated: analysis of every other language in the
  project does not stop because one plugin's provider is broken.
- It runs on the analysis dispatcher, so it must not block on the network or mutate the model.

`ContextKey` has **reference identity** like `FacetKey`: the provider that writes an attribute and the backend
that reads it are the same plugin naming the same `val`, which is why the core never has to know the key
exists.

If your language does have a classpath but needs something extra alongside it, call
`ModuleCompilationContext.create(workspace, module, variant)` and add to what it returns, rather than
reassembling the dependency walk yourself. `ClasspathEntryKind` is open for the same reason the model's other
vocabularies are, so an entry can be an include directory or a site-packages directory rather than a jar:

```kotlin
val INCLUDE_DIR = ClasspathEntryKind("INCLUDE_DIR")
val SITE_PACKAGES = ClasspathEntryKind("SITE_PACKAGES")
```

### 6.8 Register the backend

```kotlin
private class MyLangPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "mylang-language",
        name = "MyLang",
        description = "MyLang editing: parsing, code completion, and navigation.",
        dependsOn = listOf("jdt-language"),   // so the Java backend stays the backendFor fallback
    )

    override fun register(reg: PluginRegistration) {
        reg.register(LANGUAGE_BACKEND_EP, MyLangBackend())
        reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".mylang"), MyLangBackend.LANGUAGE_ID))
    }
}
```

The `dependsOn` edge is load-bearing, not decorative. `backendFor` falls back to `languageBackends.first()`,
so if your backend were to load first it would silently become the fallback for every unclaimed language.

### 6.9 How the module resolves your analyzer

Registering on `LANGUAGE_BACKEND_EP` is sufficient. It is worth knowing what happens next, because this is
the one place where the routing used to stop short.

A module's analyzers live in one module-scoped service,
[`ModuleAnalyzers`](../ide-core/src/main/kotlin/dev/ide/core/ModuleAnalyzers.kt), which holds a
`LanguageId -> SourceAnalyzer` map and builds each entry on first use through the backend registered for that
language:

```kotlin
reg.service(MODULE_ANALYZERS, ServiceScopeLevel.MODULE) {
    val ctx = getService(ENGINE_CONTEXT)
    val module = module()
    ModuleAnalyzers { language -> ctx.buildAnalyzer(module, language) }
}
```

So the set of analyzable languages is exactly the set the registered backends claim. Your analyzer is
constructed the first time a file of your language is opened, cached for the module's lifetime, and disposed
with the module container.

This replaced a host-side `when` that mapped a `LanguageId` onto one of three hand-declared service keys with
an `else` branch onto Java. Under that scheme a fourth language was selected by `backendFor` and then never
reached: the module resolved the Java analyzer for its files, so a correctly registered backend silently did
nothing. If you are reading older notes that describe adding a `ServiceKey` per language, they predate this.

### 6.10 Participating in cache invalidation

An analyzer that caches resolution state beyond its own parse trees, typically because it holds a live
compiler environment, must be told when what it resolved against changed. That is the neutral hook
`SourceAnalyzer.invalidateCaches(reason)`:

```kotlin
override fun invalidateCaches(reason: CacheInvalidation) {
    if (reason == CacheInvalidation.BINDINGS) dropMyBindingCache()
}
```

| Reason | Raised when |
| --- | --- |
| `SYNTHETIC_CLASSES` | A `SyntheticClassProvider`'s answer changed: an Android resource edit regenerating `R`, a new ViewBinding, a generator's output |
| `BINDINGS` | A file this analyzer resolved against was created, deleted, moved, or changed outside the editor. Trees stay valid; what they resolved TO may not |

The host raises these against every already-built analyzer in every live module, and never builds one to do
it, so a language nobody has opened a file in costs nothing. A backend that caches nothing needs no override.

The reason this is a neutral hook rather than the host reaching for a concrete analyzer type is that the
previous arrangement could not include a backend it did not know: invalidation was a cast to
`JdtSourceAnalyzer` and to `JavaSourceAnalyzer`, so any other backend simply kept serving stale resolutions.

## 7. Code completion

Completion has no per-language service. The engine runs every matching **contributor** over one shared result
set, so backends, cross-cutting features, and plugins all use the same API. The full design is in
[completion-contributor-api.md](completion-contributor-api.md).

### 7.1 The contributor

[`dev.ide.lang.completion.CompletionContributor`](../language-api/src/main/kotlin/dev/ide/lang/completion/Contributor.kt)

```kotlin
interface CompletionContributor {
    val id: String   // "mylang.members", "platform.bufferWords"
    suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet)
}
```

Register it either as part of your analyzer, which is the normal choice for a backend:

```kotlin
override fun completionContributions(): List<CompletionContribution> = listOf(
    CompletionContribution(
        contributor = MyLangMemberContributor(this),   // bound to this analyzer's resolver
        pattern = DomPatterns.node().withKind(NodeKind.MEMBER_ACCESS),
        languages = setOf(MyLangBackend.LANGUAGE_ID),
        order = 10,
    ),
)
```

or app-globally on `COMPLETION_CONTRIBUTOR_EP` when it is not tied to analyzer state.

Prefer `completionContributions()` for anything that needs resolution. Contributors published there are bound
to the analyzer's own resolver and symbol model, so they share its state instead of re-resolving.

`CompletionContribution` gates *where* a contributor runs, so the engine only invokes the relevant ones:

| Field | Meaning |
| --- | --- |
| `pattern` | An `ElementPattern<DomNode>` over the caret node. Default: any node |
| `languages` | Empty means every language |
| `order` | Lower runs first. Backends run early so cross-cutting contributors can filter and decorate their output; buffer-words runs last |

Language routing here is the same mechanism IntelliJ expresses as a `language=` attribute on a language-keyed
extension point. The engine resolves contributors through a
[`LanguageExtensionIndex`](../language-api/src/main/kotlin/dev/ide/lang/LanguageExtension.kt) keyed by
language id rather than scanning every registration, because this runs on nearly every keystroke. The index
is available to any consumer of a language-keyed extension point:

```kotlin
val index = LanguageExtensionIndex(extensions.extensions(MY_EP))
val applicable = index.forLanguage(LanguageId("mylang"))
```

Anything implementing `LanguageScoped` can go through it, and the convention it encodes is that an **empty
`languages` set means every language**: a contributor that names no language is cross-cutting and runs
everywhere. (`Analyzer` is the deliberate exception, as noted in
[Analyzers](#101-analyzers): it must name its languages to run at all.)

### 7.2 What a contributor is given

`CompletionParams` is built once by the engine, so no contributor re-parses:

| Member | Use |
| --- | --- |
| `document`, `offset`, `prefix` | The buffer and the caret |
| `position` | Deepest DOM node at the caret, the pattern-matching subject. Null when the file did not parse |
| `parsedFile` | The tolerant tree |
| `scope` | Visible names, from `scopeAt` |
| `expectedType` | From `expectedTypeAt`, for ranking |
| `typeResolver` | `resolveType` as a lambda, for a receiver's type |
| `replacementRange` | What an accepted item replaces |
| `trigger` | `Explicit` or `TypedChar(c)` |
| `matcher` / `prefixMatches(name)` | The graded matcher |

Always gate candidates through `params.prefixMatches(name)` rather than a raw `startsWith`. The matcher grades
exact, prefix, camel-hump, and substring matches, which is what makes `mDL` complete `myDynamicList`
uniformly across languages.

### 7.3 What a contributor can do

`CompletionResultSet` is a mutable accumulator passed to each contributor in turn, so a later contributor sees
what earlier ones added. That single object delivers four capabilities:

| Capability | Call |
| --- | --- |
| Add | `addElement(item)`, `addAllElements(items)` |
| Filter | `removeIf { … }` |
| Decorate | `replaceAll { it.copy(…) }` (rewrite insert text, docs, caret) |
| Stop | `stopHere()` when the result is definitive, e.g. a resolved member access |

Two more calls matter for correctness:

- `markIncomplete()` when you truncate your candidate list, so the engine sets `CompletionResult.isIncomplete`
  and the editor re-queries as the prefix narrows.
- `setReplacementRange(range)` when your language's word boundaries differ from the engine's prefix-derived
  guess. XML does this because `:@?+/` participate in its names. The authoritative backend sets it; otherwise
  the engine's range stands.

### 7.4 The item

```kotlin
CompletionItem(
    label = "myMethod",
    insertText = "myMethod()",
    kind = CompletionItemKind.METHOD,
    detail = "(String, Int): Unit",     // signature, second line
    container = "com.example.Thing",    // origin, right-aligned
    documentation = "…",
    symbol = resolvedSymbol,            // enables navigation and docs-on-demand
    additionalEdits = listOf(importEdit),
    caret = CaretAction.At(9),          // land between the parens
    relevance = CompletionRelevance(fitsExpectedType = true, inScope = true, proximity = 1),
)
```

Two fields deserve attention.

**`caret`** is the extensible seam for smart insertion. The editor knows nothing about your language; the
contributor decides behaviour and the editor applies it. `AtEnd`, `At(offset)`, `Select(offset, length)` for a
placeholder to overtype, and `ExpandSnippet(expansion)` for full tab-stop and choice-popup behaviour. Add
variants there, not in the editor.

**`relevance`** carries facts you already know at emit time so the ranking chain does not re-resolve anything:
`fitsExpectedType`, `contextBoost`, `callableWeight`, `inScope`, `deprecated`, `proximity`. Every field has a
neutral default, so filling none ranks exactly as if you had never set it.

### 7.5 Ranking

Do not scatter magic `sortPriority` numbers. Contribute a `CompletionWeigher`:

```kotlin
interface CompletionWeigher {
    val id: String
    fun weigh(item: CompletionItem, params: CompletionParams): Double   // higher sorts earlier
    val order: Int get() = 0                                            // lower order compares first
}
```

Registered on `COMPLETION_WEIGHER_EP`. The engine sorts the merged set by every weigher in ascending `order`,
higher weight first, and `sortPriority` remains only as the final within-backend tiebreaker. This makes
proximity, expected-type fit, and language-specific boosts independent and individually overridable.

### 7.6 Postfix templates and snippets

[`PostfixTemplate`](../language-api/src/main/kotlin/dev/ide/lang/postfix/Postfix.kt) on `POSTFIX_TEMPLATE_EP`
gives you `expr.if`, `expr.not`, `expr.for` style expansions. A template can be gated on the receiver's type
through `CompletionParams.typeResolver`, which is why `.not` offers only on a Boolean.

[`SnippetExpansion`](../language-api/src/main/kotlin/dev/ide/lang/template/Snippet.kt) drives linked tab stops
and choice popups for an accepted item. Advertise `BackendCapability.SNIPPETS` or `POSTFIX` when you use them.

---

## 8. Editor services

Each of these is an optional property on `SourceAnalyzer`, paired with a `BackendCapability`. Implement them in
whatever order serves your users; none is a prerequisite for another.

### 8.1 Structure, docs, and navigation

Two plain methods with defaults, not separate services:

```kotlin
fun fileStructure(file: VirtualFile, text: CharSequence): List<StructureItem> = emptyList()
fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? = null
```

`fileStructure` drives the structure view, the outline, and sticky-scroll headers. `StructureItem` carries
`name`, `detail`, `kind`, `nameOffset` (where the caret lands, and the line pinned as a header), `endOffset`,
and `depth`. Note it takes the **live buffer text**, so the result matches what the editor shows rather than
the last saved parse.

`quickDoc` returns a `QuickDocInfo` with a `DocFormat` of `JAVADOC`, `KDOC`, or `PLAIN`, so the renderer knows
how to parse the body. Return raw markup for a source-backed symbol and cleaned text otherwise.

Go-to-definition needs no separate service: it works off `resolve(node)` and `Symbol.declaration()`.

### 8.2 Semantic highlighting

[`SemanticHighlightService`](../language-api/src/main/kotlin/dev/ide/lang/highlight/SemanticHighlight.kt)

```kotlin
suspend fun highlight(file: VirtualFile): List<SemanticToken>
```

The lexical layer is fast and guesses by shape: a capitalized word is a "type", a word before `(` is a "call".
The semantic layer runs on a resolved parse and replaces those guesses with the truth. Return a flat list of
`SemanticToken(range, kind, modifiers)`; the UI overlays them on the lexical spans with semantic winning on
overlap, and ranges you omit keep their lexical color.

`HighlightKind` is open and string-backed, so you can contribute kinds beyond the built-ins and an unknown kind
degrades to its nearest base color. `HighlightModifier` layers orthogonal facts (static, readonly, extension,
`@Composable`, suspend) as color tweaks and font styles.

This runs on the shared engine thread and is expected to poll `dev.ide.platform.EngineCancellation` so that
completion can preempt it.

### 8.3 The rest

| Service | Interface | Returns |
| --- | --- | --- |
| Folding | [`FoldingService`](../language-api/src/main/kotlin/dev/ide/lang/folding/CodeFolding.kt) | `List<FoldRegion>` |
| Formatting | [`FormattingService`](../language-api/src/main/kotlin/dev/ide/lang/formatting/Formatting.kt) | `List<DocumentEdit>` from `format` / `formatRange`, driven by a `FormatStyle` |
| Organize imports | [`ImportOrganizerService`](../language-api/src/main/kotlin/dev/ide/lang/imports/ImportOrganizer.kt) | `List<DocumentEdit>` |
| Inlay hints | [`InlayHintService`](../language-api/src/main/kotlin/dev/ide/lang/hints/InlayHint.kt) | `List<InlayHint>` for a range |
| Signature help | [`SignatureHelpService`](../language-api/src/main/kotlin/dev/ide/lang/signature/SignatureHelp.kt) | A `SignatureHelp` for the parameter-info popup |

All of them return `DocumentEdit`s or plain data rather than mutating a document. That is what lets the host
apply them atomically and re-analyze afterwards.

---

## 9. Indexing

An index answers cross-file questions without opening every file: go-to-symbol, unimported-name completion,
"who implements this", "is this name declared anywhere". The framework owns the engine (dictionary, postings,
trigrams, persistence, queries); an extension declares only **what** to index and **how to serialize** it.

### 9.1 The extension

[`dev.ide.index.IndexExtension`](../index-api/src/main/kotlin/dev/ide/index/IndexExtension.kt)

```kotlin
interface IndexExtension<K : Any, V : Any> {
    val id: IndexId
    val version: Int
    val keyDescriptor: KeyDescriptor<K>
    val valueExternalizer: Externalizer<V>
    val inputFilter: InputFilter
    val matching: MatchingMode
    fun index(input: IndexInput): Map<K, Collection<V>>   // pure and deterministic
}
```

A complete, real example, [`KotlinMainIndex`](../lang-kotlin/src/main/kotlin/dev/ide/lang/kotlin/index/KotlinMainIndex.kt),
which finds runnable entry points in project Kotlin source:

```kotlin
object KotlinMainIndex : IndexExtension<String, EntryPointValue> {
    override val id = IndexId("kotlin.mains")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = EntryPointExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<EntryPointValue>> {
        val fileId = input.fileId
        if (fileId < 0) return emptyMap()
        val text = input.text() ?: return emptyMap()
        val name = input.sourcePath?.fileName?.toString() ?: "Main.kt"
        // Reuse the KtFile parsed for this file across every Kotlin source index in this pass.
        val kt = input.shared("kt.file") { KotlinMainScan.parse(name, text) }
        val hits = KotlinMainScan.mainsOf(kt)
        if (hits.isEmpty()) return emptyMap()
        return mapOf(EntryPointIndex.KEY to hits.map { (fqn, instance) -> EntryPointValue(fqn, fileId, instance) })
    }
}
```

Register it like anything else:

```kotlin
reg.register(INDEX_EP, MyLangSymbolIndex)
```

The built-ins are all registered together in `IndexingPlugin` in
[`BuiltInPlugins.kt`](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt).

### 9.2 The five declarations

| Member | Guidance |
| --- | --- |
| `id` | A stable `IndexId`, namespaced by producer: `kotlin.mains`, `java.classNames`, `mylang.symbols`. It is also the key in the per-indexer timing breakdown, so it must be a fixed index-kind name, never a file or project name |
| `version` | Bump whenever `index()` output or the value encoding changes. Persisted segments keyed by an old version are discarded |
| `keyDescriptor` | Serializes **and orders** keys; ordering is what enables prefix scans. Use `StringKeyDescriptor` unless your keys are not strings |
| `valueExternalizer` | Binary read/write for the value. Reuse an existing one where the shape fits: `SymbolExternalizer`, `ClassNameExternalizer`, `MemberExternalizer`, `EntryPointExternalizer`, `StringExternalizer` |
| `inputFilter` | Decides which units you consume. Always gate on both `origin` and the unit name |
| `matching` | `PREFIX_ONLY`, or `PREFIX_AND_FUZZY` to have the engine build a trigram index for substring and fuzzy queries. Fuzzy costs space, so opt in deliberately |

### 9.3 The input

[`IndexInput`](../index-api/src/main/kotlin/dev/ide/index/IndexInput.kt) is one unit of work: a class-file
entry, a source file, a resource file.

| Member | Notes |
| --- | --- |
| `origin` | `SDK`, `LIBRARY`, `SOURCE`, `LIBRARY_SOURCE`. Drives ranking proximity and the completion origin label |
| `contentHash` | The cache key |
| `unitName` | `java/util/List.class` for a binary unit, a source path for a source unit |
| `sourcePath` | Set for project-source units, null for library and SDK units |
| `fileId` | Interned, project-stable id of a source unit, or -1. Store this in values instead of repeating a path string; resolve it back with `IndexService.filePath(id)` |
| `bytes()`, `text()`, `dom()` | Lazy accessors |
| `shared(key) { … }` | **The parse-sharing memo. Read the next paragraph** |

**`input.shared` is not an optimization you can skip.** One `IndexInput` instance is handed to every extension
that consumes a file in a pass. Without the memo, ten Kotlin source indexes parse the same file ten times.
`shared(key, compute)` caches an expensive per-file artifact under a stable key so the file is parsed once,
including caching a null from a failed parse. `IndexInput.CLASS_READER` is the platform's coordination key for
a binary unit's parsed ASM `ClassReader`, which took the per-class parse count for `android.jar` from roughly
six to one. If your language has several indexes, agree on one key and route every parse through it.

### 9.4 Two sides: static and source

The engine has two halves and your `inputFilter` picks which you land in:

- **Static (SDK and library) indexes** are disk-backed immutable segments, one per artifact, keyed by content
  hash and queried in place through a bounded block cache. Heap stays flat regardless of index size. Because
  segments are content-addressed, they live under the host's shared cache root and are **reused across
  projects**, so every project does not re-index the same AndroidX and stdlib jars. Segment writes are atomic
  (unique temp plus atomic rename), so two builders of the same segment cannot corrupt it.
- **Source indexes** are in-memory and incrementally rebuilt on edit, always per-project.

### 9.5 Querying

```kotlin
interface IndexService {
    fun <V : Any> exact(id: IndexId, key: String): Sequence<V>
    fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int = 100): Sequence<Hit<V>>
    fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int = 100): Sequence<Hit<V>>
    val status: IndexStatus
    …
}
```

Analyzers reach it through `AnalysisTarget.index`, project analyzers through `ProjectAnalysisScope.index`.

If your language splits an index by producer the way Java and Kotlin do (one id per language for the same
logical service), publish an `ALL` list and use the merging helpers `exactAll`, `prefixAll`, `fuzzyAll`, which
re-rank by score so one language cannot crowd the other out before the limit cut. `ClassNameIndex`,
`SourceSymbolIndex`, `PackagesIndex`, `MembersIndex`, and `SubtypeIndex` all follow this shape.

### 9.6 The dumb-mode gate

`IndexStatus.ready` is true only after a build has **successfully** completed. It stays false before the first
build, while rebuilding, and after a failure.

Any check that concludes something is missing must gate on it. An unresolved-name inspection that runs against
a cold index reports every symbol in the project as unresolved. The established pattern is that an
index-backed lookup returns nothing until `ready`, and the consumer treats "not ready" as "indeterminate,
report nothing" rather than "absent".

---

## 10. Diagnostics and quick-fixes

There is one diagnostic model and one pipeline. Compiler errors and analyzer findings merge into the same
stream, so the editor shows them identically and a fix can key off either.

### 10.1 Analyzers

[`dev.ide.analysis`](../analysis-api/src/main/kotlin/dev/ide/analysis/Analyzers.kt) offers two shapes over a
common base:

```kotlin
interface FileAnalyzer : Analyzer {
    val interestedIn: Set<NodeKind>?      // null = invoked once for the whole file
    fun analyze(target: AnalysisTarget, sink: DiagnosticSink)
}

interface ProjectAnalyzer : Analyzer {
    suspend fun analyze(scope: ProjectAnalysisScope, sink: ProjectDiagnosticSink)
}
```

The framework performs **one shared DOM traversal per file** and dispatches each node to the analyzers that
registered interest in its `NodeKind`. So N analyzers cost one walk, which is why declaring `interestedIn`
precisely matters. `FileAnalyzer.analyze` is synchronous and runs inside a cancellable read action against an
already-parsed tree.

`ProjectAnalyzer` is suspending because it may pull many files and query the index. It is the heaviest, lowest
priority tier, and its sink names the file each finding belongs to.

Pick a tier by what the analyzer actually reads:

| Tier | Reads | Runs | Examples |
| --- | --- | --- | --- |
| `SYNTAX` | The DOM only | Effectively every keystroke | Naming, structure, formatting |
| `SEMANTIC` | `AnalysisTarget.resolver` (types and symbols) | On the settled buffer | Unused variable, type mismatch |
| `PROJECT` | The cross-file `IndexService` | Lowest priority | Unused public API, duplicate declarations |

Choosing too low a tier is a real performance bug: an analyzer that reads sibling files from disk at `SYNTAX`
runs that I/O on every keystroke.

### 10.2 Reporting

```kotlin
sink.report(
    range = TextRange(start, end),
    severity = Severity.WARNING,
    message = "…",
    code = "MYLANG_UNUSED_RULE",
    fixes = listOf(RemoveRuleFix(…)),
    tags = setOf(DiagnosticTag.UNNECESSARY),
    related = listOf(RelatedRange(…)),
)
```

You describe the problem; the framework stamps the source, applies the profile's severity override, and filters
suppressions before publishing. Do not implement severity settings or suppression yourself.

**Always set `code`.** It is the join key for quick-fixes, the analysis profile, and suppression. A diagnostic
with no code cannot be fixed, configured, or suppressed.

### 10.3 Compiler diagnostics

If your language compiles, register a `DiagnosticProvider` on `DIAGNOSTIC_PROVIDER_EP` so the compiler's own
errors flow into the same stream as analyzer findings. That is how the Java and Kotlin backends surface
compile errors in the editor, and it is what lets a fix be keyed on a compiler code the compiler knows nothing
about.

### 10.4 Fixes and intentions

[`QuickFix.kt`](../analysis-api/src/main/kotlin/dev/ide/analysis/QuickFix.kt) has two providers, keyed
differently:

| Provider | Keyed by | Registered on | Use for |
| --- | --- | --- | --- |
| `QuickFixProvider` | `Diagnostic.code` | `QUICK_FIX_PROVIDER_EP` | A fix for a problem |
| `ActionProvider` | Caret position | `ACTION_PROVIDER_EP` | An intention or refactor with no diagnostic |

```kotlin
interface QuickFixProvider {
    val forCodes: Set<String>
    val languages: Set<LanguageId> get() = emptySet()   // empty = all languages
    fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix>
}
```

**Set `languages`.** It defaults to every language, and several languages emit diagnostics sharing a code such
as `UNRESOLVED_REFERENCE`. Without the filter, your language's "Add import" fix attaches to Kotlin and XML
diagnostics too.

A fix returns a `WorkspaceEdit`, a map of file to `DocumentEdit`s, applied atomically under the model write
lock. Two properties follow, and both are deliberate:

- Fixes are computed **lazily**, only when the fix menu opens or the diagnostic is hovered.
- Edits are computed against a **fresh snapshot at apply time**, so an edit built against a stale version is
  never applied blindly.

Because `WorkspaceEdit` reuses `DocumentEdit`, fixes flow through the same machinery as incremental reparsing,
which is what makes applying one atomic and makes it trigger re-analysis.

---

## 11. Synthetic classes

A synthetic class is a type contributed to resolution with **no source or bytecode on disk**. The motivating
case is Android's `R`, which is generated at build time but needed for completion before any build has run.
The same shape serves `BuildConfig`, ViewBinding, Dagger components, Room output, and AIDL stubs.

For a language author, this is how the product of your declaration language becomes navigable.

[`dev.ide.lang.synthetic`](../language-api/src/main/kotlin/dev/ide/lang/synthetic/SyntheticClass.kt)

```kotlin
fun interface SyntheticClassProvider {
    fun classesFor(context: SyntheticClassContext): List<SyntheticClass>
}
```

You describe a class as **structure**, not syntax:

```kotlin
SyntheticClass(
    fqName = "com.example.IFoo",
    kind = SyntheticTypeKind.INTERFACE,
    methods = listOf(SyntheticMethod("doWork", "void", listOf(SyntheticParam("id", "int")))),
    nestedClasses = listOf(
        SyntheticClass(
            fqName = "com.example.IFoo.Stub",
            superClass = "android.os.Binder",
            methods = listOf(SyntheticMethod("asInterface", "com.example.IFoo", …)),
        ),
    ),
)
```

The language backend renders that however it needs (the Java backend emits source into its name-environment
overlay), so the type resolves uniformly for completion, analysis, and go-to-definition, exactly like a real
one.

Four rules:

- **`fqName` is the top-level class.** Nested types go in `nestedClasses`, with their own fully-qualified
  names.
- **It is asked per module.** `SyntheticClassContext` carries `module` and `workspace`; read facets, source
  roots, and dependencies yourself and return nothing when the provider does not apply. The Android `R`
  provider returns an empty list for a non-Android module.
- **Be cheap or be cached.** The host caches the rendered result and refreshes it on file changes, but
  `classesFor` itself is called per module.
- **Match what the build will actually generate.** The point is that nothing appears or disappears once a
  build has run. If your synthetic shape and your generator disagree, you have created a class of bug where
  the editor is green and the build is red, or worse the reverse.

Register with:

```kotlin
reg.register(SYNTHETIC_CLASS_EP, MyLangGeneratedProvider())
```

---

## 12. Build integration

A language that only edits is half a language. Three SPIs cover the range, and picking the right one is most
of the work:

| You want | Use | Why |
| --- | --- | --- |
| To add a step to an existing pipeline (compile my files, generate something, post-process output) | [`BuildPlugin`](../build-api/src/main/kotlin/dev/ide/build/Plugins.kt) on `BUILD_PLUGIN_EP` | Reuses the Java or Android pipeline and wires to its tasks by name |
| To emit Kotlin or Java source before compilation | [`SourceGenerator`](../build-api/src/main/kotlin/dev/ide/build/SourceGenerator.kt) on `SOURCE_GENERATOR_EP` | The build wires the output as a `GENERATED` source root, so it is compiled and indexed like hand-written code |
| To own a project's builds entirely | [`BuildSystem`](../build-api/src/main/kotlin/dev/ide/build/Build.kt) on `BUILD_SYSTEM_EP` | A genuinely different pipeline or a foreign build system |

Most languages want `BuildPlugin`. Reach for `BuildSystem` only when the whole graph is different. This
section covers what a language author needs; [custom-build-plugins.md](custom-build-plugins.md) is the full
guide to the build extension surface.

### 12.1 Tasks

The task engine mirrors Gradle's model without hosting Gradle.

```kotlin
interface Task {
    val name: TaskName            // ":app:compileMyLang"
    val inputs: TaskInputs
    val outputs: TaskOutputs
    val dependsOn: List<TaskName> get() = emptyList()      // hard: failure blocks this task
    val mustRunAfter: List<TaskName> get() = emptyList()   // ordering only
    val mustRunBefore: List<TaskName> get() = emptyList()
    suspend fun execute(ctx: TaskContext): TaskResult
}
```

**Declare inputs and outputs honestly.** They are the entire basis of incrementality: the executor fingerprints
them, compares against the persisted record, and returns `TaskResult.UpToDate` without running you. Under-declare
and you get stale output; over-declare and you never skip.

Build them with `TaskInputsImpl` / `TaskOutputsImpl` from `:build-engine`, as a `get()` property so they are
re-read each time the engine fingerprints the task:

```kotlin
override val inputs: TaskInputs get() = TaskInputsImpl().apply {
    filePaths("sources", myLangSources)                 // content-hashed
    dirPaths("deps", depOutputDirs(module))             // recursive content
    classpath("compileClasspath", module.classpath())   // hash-based, not path-based
    property("languageLevel", languageLevel.name)
}
override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("classes", outputDir) }
```

`TaskInputs` and `TaskOutputs` in `build-api` declare `files`/`property`/`classpath` and `files`/`dir`; the
`:build-engine` implementations add the `Path`-based `filePaths`, `dirPaths`, `filePath`, and `dirPath`
variants that most tasks actually use. Declaring nothing at all is meaningful: `isEmpty()` makes the engine
report the task NO-SOURCE and skip it, which is how `processResources` behaves for a module with no resource
roots.

There is also **implicit dependency inference**: the engine matches declared input paths against other tasks'
declared output paths and infers the edge, so consuming another task's output makes you depend on it without
naming it. That does not cover a directory that is empty at graph-build time, which is why `generateSources`
gets an explicit edge to the compile tasks.

`TaskContext` gives you `progress`, `checkCanceled()`, `logger()` for raw transcript lines, `buildLog` for
level-tagged structured entries, and `diagnostics` for structured `BuildDiagnostic`s the console can group by
task. Poll `checkCanceled()` in any loop.

Return `TaskResult.Success`, `UpToDate`, or `Failed(message, cause)`. Do not throw for an expected compile
error; `Failed` carries it into the console properly.

### 12.2 Wiring into an existing pipeline

```kotlin
class MyLangBuildPlugin : BuildPlugin {
    override val id = "mylang"
    override fun appliesTo(config: BuildConfiguration) = config.request.goal != BuildGoal.CLEAN

    override fun apply(config: BuildConfiguration) {
        for (m in config.project.modules) {
            val compile = TaskName(":${m.name}:compileMyLang")
            config.tasks.register(compile) { CompileMyLangTask(m, compile, config.env) }
            // The Java compile step must see our generated classes.
            config.tasks.named(Lifecycle.compileJava(m.name)).configure { dependsOn(compile) }
        }
    }
}
```

Two properties make this work without your plugin knowing which pipeline is running:

- **Registration is lazy.** The factory passed to `register` runs only when the graph is realized, and
  `named` returns a handle to a task whether or not it exists yet. So you can wire to a task another plugin
  registers later.
- **Configuring an absent task is silently ignored.** One plugin can target several pipelines without probing
  which one is active.

[`Lifecycle`](../build-api/src/main/kotlin/dev/ide/build/Plugins.kt) names the anchors every pipeline
registers: `generateSources`, `compileKotlin`, `compileJava`, `processResources`, `classes`, `jar`, and
`assemble(module, variant)`. Wire your final task to `assemble`.

`BuildEnv` on `config.env` resolves paths so you never rediscover the host's layout: `workspaceRoot`,
`sharedCachesRoot`, `bootClasspath(module)`, `buildDir(module)`, and `generatedDir(module, id)`.

[`JavaPlugin`](../jvm-build/src/main/kotlin/dev/ide/build/jvm/JavaPlugin.kt) is the reference: it registers
`compileJava → processResources → classes → jar` per module and adds `compileKotlin` ahead of `compileJava`
for modules carrying `.kt` sources. Note that the compile tasks belong to the **language** modules
(`JdtCompileTask` from `lang-jdt`, `KotlinCompileTask` from `lang-kotlin`) and the build plugin only composes
them. Follow that split: your compile task lives in your language module.

### 12.3 Generating source

```kotlin
interface SourceGenerator {
    val id: String                                    // the generated sub-directory name
    fun appliesTo(request: SourceGenRequest): Boolean
    fun generate(request: SourceGenRequest): SourceGenResult
}
```

`SourceGenRequest` carries plain paths and names, with no model types, so the SPI stays in `build-api`:
`kotlinSources`, `javaSources`, `sourceRoots` (roots, for package inference), `classpath`, `outputDir` (created
for you), `declaredDependencies` (direct `group:name` coordinates, never the transitive closure), and
`acceptedWarnings`.

Gate `appliesTo` on `declaredDependencies` rather than the full classpath when activation should follow an
explicit opt-in. That is how KSP matches AGP behaviour: a processor runs because the module declared its
runtime, not because it arrived transitively through some other library.

### 12.4 Run configurations

`RunTaskProvider` on `RUN_TASK_PROVIDER_EP` adds rows to the Run picker:

```kotlin
interface RunTaskProvider {
    fun tasksFor(module: Module): List<RunTaskSpec>
    fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? = null
}
```

Dispatch is split by id prefix. An id carrying a built-in prefix (`build:`, `run:`, `assemble:`) runs through
the host's own pipeline and needs no `actionFor`. Any other id comes back to your `actionFor`, which returns a
`RunAction` (a console header, the graph to run, an optional banner, and an optional `onSuccess` step) that the
host streams through the same executor, console, and cancellation path as a built-in task.

---

## 13. Module types, facets, and project templates

### 13.1 Module type

[`ModuleType`](../project-model-api/src/main/kotlin/dev/ide/model/ProjectModel.kt) tells the model what a
module of your language looks like:

```kotlin
object MyLangModuleType : ModuleType {
    override val id = "mylang-lib"
    override val displayName = "MyLang Library"
    override fun defaultSourceSets() = listOf(
        SourceSetTemplate(
            "main", DependencyScope.IMPLEMENTATION,
            mapOf("src/main/mylang" to setOf(ContentRole.SOURCE)),
        ),
    )
    override fun defaultFacets() = emptyList<FacetTemplate>()
    override fun supportedBuildSystems() = setOf(BuildSystemId.NATIVE)
}
```

`id` is persisted in `module.toml` and resolved back through `ModuleTypeExtensionPoint` on load, so it must
stay stable. `platform` defaults to Android for an `android-*` id and JVM otherwise; override it if that
inference is wrong for you.

Register it on the extension point:

```kotlin
reg.register(ModuleTypeExtensionPoint, MyLangModuleType)
```

`ModuleTypeRegistry(ext).register(...)` through `contributeVia` does the same thing and is what the built-ins
use; the registry is the read side, for resolving a persisted type id back to a `ModuleType`.

**If your language is not laid out like a JVM module**, say so rather than approximating. Five of the model's
vocabularies are open value types, not enums, so a module type can name its own:

```kotlin
val PACKAGE_ROOT = ContentRole("mylang-package")   // instead of passing as ContentRole.SOURCE
val HEADERS = ContentRole("mylang-headers")
val MYLANG = PlatformKind("MYLANG")                // resolves an SDK of its own kind, never android.jar
val BUNDLE = LibraryKind("BUNDLE")                 // instead of calling a package a jar
val LEVEL = LanguageLevel("MYLANG_2")              // instead of claiming JAVA_17

/** On the runtime path, never the compile one. */
val LINK_ONLY = DependencyScope.register(
    DependencyScope("LINK_ONLY", "linkOnly", onCompile = false, onRuntime = true, onTest = true),
)
```

`ContentRole`, `PlatformKind`, `LibraryKind` and `LanguageLevel` round-trip through `module.toml` on their
`id`/`name` alone. `DependencyScope` also carries classpath semantics a name cannot recover, so register it:
otherwise a project that persisted it still loads, but the scope is re-derived permissively. The built-in
values keep the spellings they have always been written under, so none of this is a format migration.

### 13.2 Facets

A facet is domain-specific configuration attached to a module without the core knowing the domain
(`AndroidFacet` is the built-in example). Define a `FacetKey<T>` and a `Facet`, then contribute a
[`FacetCodec`](../project-model-api/src/main/kotlin/dev/ide/model/FacetCodec.kt) so it round-trips through
`module.toml`:

```kotlin
val MYLANG_FACET = FacetKey<MyLangFacet>("mylang")

data class MyLangFacet(val dialect: String) : Facet {
    override val key get() = MYLANG_FACET
}

object MyLangFacetCodec : FacetCodec<MyLangFacet> {
    override val key = MYLANG_FACET
    override val tomlTable = "mylang"          // the [mylang] table in module.toml
    override fun encode(f: MyLangFacet) = mapOf("dialect" to f.dialect)
    override fun decode(v: Map<String, Any?>) = MyLangFacet(v["dialect"] as? String ?: "strict")
}

// in Plugin.register:
reg.register(FACET_CODEC_EP, MyLangFacetCodec)
```

The codec is **required, not optional**: `ModifiableModule.putFacet` refuses a facet whose key has no
registered codec, and `FacetContainer.get` answers null for one. The facet type and its codec are two halves
of one contribution.

Two matching rules to know:

- `FacetKey` has **reference identity**. Declare it once as a `val` and have the facet and the codec name that
  same instance; two keys sharing an id are two different keys.
- The **`tomlTable` name is the on-disk identity**, in a namespace flat across every plugin. `decode` resolves
  by table, so the last registration for a table wins.

A table nobody claims is not lost: it is carried through a load and a save untouched, so a project edited with
your plugin disabled keeps its configuration.

### 13.3 Project templates

[`ProjectTemplate`](../project-model-api/src/main/kotlin/dev/ide/model/template/ProjectTemplate.kt) puts your
language in the Create Project gallery. It is declarative about its inputs so the UI is data-driven: it renders
one control per `TemplateParameter` and hands the values back as `TemplateArgs`.

```kotlin
object MyLangAppTemplate : ProjectTemplate {
    override val id = TemplateId("mylang-app")
    override val displayName = "MyLang Application"
    override val description = "A MyLang project with a runnable entry point."
    override val category = TemplateCategory.OTHER
    override val iconId = "code"

    override fun parameters() = listOf(
        TemplateParameter.Choice(
            key = "flavor", label = "Dialect",
            options = listOf(Option("strict", "Strict"), Option("loose", "Loose")),
        ),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        scaffold.writeText("src/main/mylang/${pkg.replace('.', '/')}/Main.mylang", template(pkg))
    }

    override fun dependencies(args: TemplateArgs) = listOf(
        TemplateDependency(module = "app", coordinate = "com.example:mylang-runtime:1.0"),
    )
}
```

Notes that save time:

- `TemplateArgs.NAME` and `TemplateArgs.PACKAGE` are reserved and always provided; the screen renders them
  itself. Declare only your extras.
- `parameters()` supports `Text` (with `TextValidation` of `IDENTIFIER`, `PACKAGE_NAME`, or `PROJECT_NAME`),
  `Choice`, and `Toggle`.
- `generate` is **synchronous**, so it cannot resolve Maven dependencies. Declare them in `dependencies(args)`
  and the host resolves and attaches each one afterwards, reusing the same resolver and offline cache as the
  Dependencies screen.
- `ProjectScaffold` gives you `workspace` (the model transaction surface), `rootDir`, `languageLevel` (injected
  by the host, so the template stays platform-agnostic), `moduleType(id)`, `writeText`, and `writeBytes`. Use
  `writeBytes` for any binary asset; `writeText` round-trips through UTF-8 and will corrupt a PNG.

Register with:

```kotlin
reg.register(ProjectTemplateExtensionPoint, MyLangAppTemplate)
```

### 13.4 Importing and editing foreign build files

Two further SPIs exist if your language ships its own project format:

- [`ProjectImporter`](../project-model-api/src/main/kotlin/dev/ide/model/sync/ProjectSync.kt) on
  `PROJECT_IMPORTER_EP` reads build files into an `ExternalProjectModel` snapshot. `detect(root)` must not
  throw and must not write; `resolve` may read the file system and the network but must not mutate the model.
  `syncFiles()` returns the globs whose change makes the model stale, so the host can offer a Sync.
- [`BuildFileWriter`](../project-model-api/src/main/kotlin/dev/ide/model/sync/ProjectSync.kt) on
  `BUILD_FILE_WRITER_EP` writes dependency changes back, so the Dependencies screen works on your projects.

Reading a project model and building it are deliberately separate SPIs: a `BuildSystem` only builds.

---

## 14. Package it as a plugin

Everything above ships as one plugin. Follow the api / impl split the built-in languages use:

| Module | Contains |
| --- | --- |
| `:lang-mylang` | The parser, DOM adapter, resolver, completion contributors, indexes, and the compile task |
| `:ide-core` | The `Plugin` entry point (one `BuiltInPlugin` line), for a language shipped in-tree |

A complete entry point:

```kotlin
private class MyLangPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "mylang-language",
        name = "MyLang",
        description = "MyLang editing, indexing, diagnostics, and compilation.",
        dependsOn = listOf("jdt-language"),
    )

    override fun register(reg: PluginRegistration) {
        // Editor
        reg.register(LANGUAGE_BACKEND_EP, MyLangBackend())
        reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".mylang"), MyLangBackend.LANGUAGE_ID))

        // Indexes
        reg.register(INDEX_EP, MyLangSymbolIndex)
        reg.register(INDEX_EP, MyLangClassNamesIndex)

        // Analysis
        reg.register(ANALYZER_EP, MyLangUnusedRuleAnalyzer())
        reg.register(DIAGNOSTIC_PROVIDER_EP, MyLangCompilerDiagnostics())
        reg.register(QUICK_FIX_PROVIDER_EP, MyLangQuickFixes())

        // Generated types
        reg.register(SYNTHETIC_CLASS_EP, MyLangGeneratedProvider())

        // Build
        reg.register(BUILD_PLUGIN_EP, MyLangBuildPlugin())
        reg.register(RUN_TASK_PROVIDER_EP, MyLangRunTasks())

        // Project model
        reg.contributeVia { ext, pid ->
            ModuleTypeRegistry(ext).register(MyLangModuleType, pid)
            ProjectTemplateRegistry(ext).register(MyLangAppTemplate, pid)
        }
    }
}
```

Then one line in `BuiltInPlugins.assemble`:

```kotlin
BuiltInPlugin(MyLangPlugin()),
```

Because it is non-essential, a user can turn the whole language off from **Settings → Plugins**, and every
surface above disappears with it.

Two ordering rules to respect:

- `dependsOn = listOf("jdt-language")` keeps the Java backend as the `backendFor` fallback.
- Contributions that need the currently-open project take `ApplicationEnvironment` and read `env.activeEngine`
  lazily at callback time, never during `register`. The `R` provider does this; a synthetic-class provider for
  your language probably needs to as well.

---

## 15. Test it

Every layer is testable without launching the app, and mostly without a project.

**The parser.** Assert the error-tolerance contract directly, because it is the requirement most likely to
break silently:

```kotlin
@Test fun `parses a truncated file without throwing`() {
    val tree = parser.parseFull(snapshot("rule Foo {"))
    assertEquals(0, tree.range.start)
    assertEquals(10, tree.range.end)                  // covers the whole buffer
    assertTrue(tree.nodesIn(tree.range).any { it.kind == NodeKind.ERROR })
    assertNotNull(tree.nodeAt(10))                    // the caret position resolves
}
```

Fuzz it: parse every prefix of a representative file and assert none throws and each covers its whole input.

**Indexes.** An `IndexExtension` is a pure function. Build a fake `IndexInput` and assert the map.

**Analyzers.** Drive `analyze(target, sink)` with a recording sink and assert ranges, codes, and severities.
Assert the code strings explicitly, since fixes and suppression key off them.

**Completion.** [`CompletionRunner.kt`](../language-api/src/main/kotlin/dev/ide/lang/completion/CompletionRunner.kt)
provides engine-free `complete(...)` extensions on both `CompletionContributor` and `SourceAnalyzer`, running
into a `BasicCompletionResultSet` with no EP contributors or weighers. `:ide-core` also carries a completion regression suite covering quality, latency, and
allocation; add cases there for a language that ships.

**Build tasks.** Run the task twice against unchanged inputs and assert the second returns
`TaskResult.UpToDate`. That single test catches most incrementality mistakes.

**Templates.** Generate into a temp directory and assert the file layout, then open the result and assert the
module model.

Practical notes: `:ide-core` is excluded under `CI_CORE_ONLY`, so run its tests with that flag unset; and
several SPI methods are `suspend`, so drive them with `runBlocking`.

---

## 16. Checklist

**Tier 1, a language with no backend**

- [ ] `FileTypeMapping` on `FILE_TYPE_EP` with a stable `LanguageId`
- [ ] An `EditorLanguageProfile` for coloring, Toggle Comment, and the Enter handler
- [ ] A `FileAnalyzer` whose `languages` claims the id, driven by the same parser the build uses
- [ ] A `SyntheticClassProvider` if the language generates code
- [ ] A `BuildPlugin` or `SourceGenerator` if it compiles or generates
- [ ] The plugin declared in `BuiltInPlugins`

**Tier 2, a full backend**

- [ ] Everything above
- [ ] A `LanguageBackend` on `LANGUAGE_BACKEND_EP`, with `dependsOn = listOf("jdt-language")`
- [ ] A parser that never throws and always covers the whole buffer
- [ ] A DOM adapter reusing the standard `NodeKind` constants where they fit
- [ ] `parseFull`; `reparse` real only once measured
- [ ] `resolve` / `scopeAt` / `expectedTypeAt`, plus `resolveType` for refactorings
- [ ] Completion contributors published from `completionContributions()`
- [ ] Candidates gated through `params.prefixMatches`, not `startsWith`
- [ ] `CompletionRelevance` filled at emit time; ranking through a `CompletionWeigher`
- [ ] Indexes with a bumped `version`, sharing one parse through `input.shared`
- [ ] Index-backed checks gated on `IndexStatus.ready`
- [ ] Every diagnostic carries a `code`; every `QuickFixProvider` sets `languages`
- [ ] Capabilities declared exactly matching the services returned

---

## Appendix A: extension points for language work

| FQN | Id | Type | Adds |
| --- | --- | --- | --- |
| `dev.ide.lang.FILE_TYPE_EP` | `platform.fileType` | `FileTypeMapping` | Suffix to language routing |
| `dev.ide.lang.LANGUAGE_BACKEND_EP` | `platform.languageBackend` | `LanguageBackend` | Parsing, resolution, editor services |
| `dev.ide.lang.COMPILATION_CONTEXT_PROVIDER_EP` | `platform.compilationContext` | `CompilationContextProvider` | Your language's analysis inputs |
| `dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP` | `platform.completionContributor` | `CompletionContribution` | Completion items |
| `dev.ide.lang.completion.COMPLETION_WEIGHER_EP` | `platform.completionWeigher` | `CompletionWeigher` | Completion ranking |
| `dev.ide.lang.postfix.POSTFIX_TEMPLATE_EP` | `platform.postfixTemplate` | `PostfixTemplate` | Postfix templates |
| `dev.ide.lang.synthetic.SYNTHETIC_CLASS_EP` | `platform.syntheticClass` | `SyntheticClassProvider` | Types with no file on disk |
| `dev.ide.index.INDEX_EP` | `platform.index` | `IndexExtension<*, *>` | A searchable index |
| `dev.ide.analysis.ANALYZER_EP` | `platform.analyzer` | `Analyzer` | Inspections |
| `dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP` | `platform.diagnosticProvider` | `DiagnosticProvider` | Compiler diagnostics |
| `dev.ide.analysis.QUICK_FIX_PROVIDER_EP` | `platform.quickFixProvider` | `QuickFixProvider` | Fixes keyed by code |
| `dev.ide.analysis.ACTION_PROVIDER_EP` | `platform.actionProvider` | `ActionProvider` | Intentions keyed by position |
| `dev.ide.build.BUILD_PLUGIN_EP` | `platform.buildPlugin` | `BuildPlugin` | Tasks in an existing pipeline |
| `dev.ide.build.BUILD_SYSTEM_EP` | `platform.buildSystem` | `BuildSystem` | A whole pipeline |
| `dev.ide.build.SOURCE_GENERATOR_EP` | `platform.sourceGenerator` | `SourceGenerator` | Generated sources |
| `dev.ide.build.RUN_TASK_PROVIDER_EP` | `platform.runTaskProvider` | `RunTaskProvider` | Run-picker rows |
| `dev.ide.model.ModuleTypeExtensionPoint` | `platform.moduleType` | `ModuleType` | A module kind |
| `dev.ide.model.template.ProjectTemplateExtensionPoint` | `platform.projectTemplate` | `ProjectTemplate` | A Create-Project entry |
| `dev.ide.model.FACET_CODEC_EP` | `platform.facetCodec` | `FacetCodec<*>` | Facet persistence |
| `dev.ide.model.sync.PROJECT_IMPORTER_EP` | `platform.projectImporter` | `ProjectImporter` | Reading a foreign project |
| `dev.ide.model.sync.BUILD_FILE_WRITER_EP` | `platform.buildFileWriter` | `BuildFileWriter` | Writing build files |
| `dev.ide.model.FileIconExtensionPoint` | `platform.fileIcon` | `FileIconProvider` | File-tree icons |
| `dev.ide.block.BLOCK_MAPPING_EP` | `platform.blockMapping` | `BlockMapping` | Block-editor projection |

## Appendix B: class index

### Language: [`:language-api`](../language-api)

| FQN | File |
| --- | --- |
| `dev.ide.lang.LanguageBackend` / `SourceAnalyzer` / `CompilationContext` / `CompilationContextProvider` / `ContextKey` / `BackendCapability` / `LanguageId` / `CacheInvalidation` | [LanguageBackend.kt](../language-api/src/main/kotlin/dev/ide/lang/LanguageBackend.kt) |
| `dev.ide.lang.ModuleCompilationContext` | [ModuleCompilationContext.kt](../language-api/src/main/kotlin/dev/ide/lang/ModuleCompilationContext.kt) |
| `dev.ide.lang.LanguageScoped` / `LanguageExtensionIndex` / `appliesTo` | [LanguageExtension.kt](../language-api/src/main/kotlin/dev/ide/lang/LanguageExtension.kt) |
| `dev.ide.lang.FileTypeMapping` | [FileType.kt](../language-api/src/main/kotlin/dev/ide/lang/FileType.kt) |
| `dev.ide.lang.dom.DomNode` / `ParsedFile` / `NodeKind` / `TextRange` / `Diagnostic` / `Severity` | [Dom.kt](../language-api/src/main/kotlin/dev/ide/lang/dom/Dom.kt) |
| `dev.ide.lang.incremental.IncrementalParser` / `DocumentSnapshot` / `DocumentEdit` | [Incremental.kt](../language-api/src/main/kotlin/dev/ide/lang/incremental/Incremental.kt) |
| `dev.ide.lang.resolve.Symbol` / `TypeRef` / `Scope` / `ResolveResult` / `StructureItem` / `QuickDocInfo` | [Resolve.kt](../language-api/src/main/kotlin/dev/ide/lang/resolve/Resolve.kt) |
| `dev.ide.lang.completion.CompletionContributor` / `CompletionParams` / `CompletionResultSet` / `CompletionWeigher` | [Contributor.kt](../language-api/src/main/kotlin/dev/ide/lang/completion/Contributor.kt) |
| `dev.ide.lang.completion.CompletionItem` / `CaretAction` / `CompletionRelevance` | [Completion.kt](../language-api/src/main/kotlin/dev/ide/lang/completion/Completion.kt) |
| `dev.ide.lang.highlight.SemanticHighlightService` / `SemanticToken` / `HighlightKind` | [SemanticHighlight.kt](../language-api/src/main/kotlin/dev/ide/lang/highlight/SemanticHighlight.kt) |
| `dev.ide.lang.folding.FoldingService` | [CodeFolding.kt](../language-api/src/main/kotlin/dev/ide/lang/folding/CodeFolding.kt) |
| `dev.ide.lang.formatting.FormattingService` / `FormatStyle` | [Formatting.kt](../language-api/src/main/kotlin/dev/ide/lang/formatting/Formatting.kt) |
| `dev.ide.lang.imports.ImportOrganizerService` | [ImportOrganizer.kt](../language-api/src/main/kotlin/dev/ide/lang/imports/ImportOrganizer.kt) |
| `dev.ide.lang.hints.InlayHintService` | [InlayHint.kt](../language-api/src/main/kotlin/dev/ide/lang/hints/InlayHint.kt) |
| `dev.ide.lang.signature.SignatureHelpService` | [SignatureHelp.kt](../language-api/src/main/kotlin/dev/ide/lang/signature/SignatureHelp.kt) |
| `dev.ide.lang.postfix.PostfixTemplate` | [Postfix.kt](../language-api/src/main/kotlin/dev/ide/lang/postfix/Postfix.kt) |
| `dev.ide.lang.template.SnippetExpansion` | [Snippet.kt](../language-api/src/main/kotlin/dev/ide/lang/template/Snippet.kt) |
| `dev.ide.lang.synthetic.SyntheticClass` / `SyntheticClassProvider` | [SyntheticClass.kt](../language-api/src/main/kotlin/dev/ide/lang/synthetic/SyntheticClass.kt) |

### Index, analysis, build, model

| FQN | File |
| --- | --- |
| `dev.ide.index.IndexExtension` | [IndexExtension.kt](../index-api/src/main/kotlin/dev/ide/index/IndexExtension.kt) |
| `dev.ide.index.IndexService` / `IndexStatus` / `KeyDescriptor` / `Externalizer` | [Index.kt](../index-api/src/main/kotlin/dev/ide/index/Index.kt) |
| `dev.ide.index.IndexInput` | [IndexInput.kt](../index-api/src/main/kotlin/dev/ide/index/IndexInput.kt) |
| `dev.ide.index.IndexOrigin` / `MatchingMode` / `IndexScope` | [IndexOrigin.kt](../index-api/src/main/kotlin/dev/ide/index/IndexOrigin.kt), [MatchingMode.kt](../index-api/src/main/kotlin/dev/ide/index/MatchingMode.kt), [IndexScope.kt](../index-api/src/main/kotlin/dev/ide/index/IndexScope.kt) |
| Shared index ids and externalizers | [IndexValues.kt](../index-api/src/main/kotlin/dev/ide/index/IndexValues.kt) |
| `dev.ide.analysis.FileAnalyzer` / `ProjectAnalyzer` / `AnalysisTarget` / `DiagnosticSink` / `AnalyzerTier` | [Analyzers.kt](../analysis-api/src/main/kotlin/dev/ide/analysis/Analyzers.kt) |
| `dev.ide.analysis.QuickFix` / `QuickFixProvider` / `WorkspaceEdit` | [QuickFix.kt](../analysis-api/src/main/kotlin/dev/ide/analysis/QuickFix.kt) |
| `dev.ide.build.BuildSystem` / `Task` / `TaskGraph` / `RunTaskProvider` / `RunAction` | [Build.kt](../build-api/src/main/kotlin/dev/ide/build/Build.kt) |
| `dev.ide.build.BuildPlugin` / `TaskContainer` / `BuildEnv` / `Lifecycle` | [Plugins.kt](../build-api/src/main/kotlin/dev/ide/build/Plugins.kt) |
| `dev.ide.build.SourceGenerator` / `SourceGenRequest` | [SourceGenerator.kt](../build-api/src/main/kotlin/dev/ide/build/SourceGenerator.kt) |
| `dev.ide.model.ModuleType` / `Facet` / `FacetKey` | [ProjectModel.kt](../project-model-api/src/main/kotlin/dev/ide/model/ProjectModel.kt) |
| `dev.ide.model.template.ProjectTemplate` / `TemplateParameter` / `ProjectScaffold` | [ProjectTemplate.kt](../project-model-api/src/main/kotlin/dev/ide/model/template/ProjectTemplate.kt) |
| `dev.ide.model.sync.ProjectImporter` / `BuildFileWriter` | [ProjectSync.kt](../project-model-api/src/main/kotlin/dev/ide/model/sync/ProjectSync.kt) |
| `dev.ide.model.FacetCodec` / `FacetCodecRegistry` / `FacetData` | [FacetCodec.kt](../project-model-api/src/main/kotlin/dev/ide/model/FacetCodec.kt) |
| `dev.ide.model.ModuleTypeRegistry` / `ProjectTemplateRegistry` / `FileIconRegistry` | [ModuleTypeRegistry.kt](../project-model-api/src/main/kotlin/dev/ide/model/ModuleTypeRegistry.kt) |
| `dev.ide.model.ContentRole` / `PlatformKind` / `LibraryKind` / `LanguageLevel` / `DependencyScope` | [ProjectModel.kt](../project-model-api/src/main/kotlin/dev/ide/model/ProjectModel.kt) |

### Editor text layer

| FQN | File |
| --- | --- |
| `dev.ide.ui.ext.EditorLanguageProfile` / `SyntaxFamily` / `EditorLanguageRegistry` | [EditorLanguages.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/EditorLanguages.kt) |
| `dev.ide.core.ModuleAnalyzers` | [ModuleAnalyzers.kt](../ide-core/src/main/kotlin/dev/ide/core/ModuleAnalyzers.kt) |

### Reference implementations

| Language | Entry point |
| --- | --- |
| XML | [XmlLanguageBackend.kt](../lang-xml/src/main/kotlin/dev/ide/lang/xml/XmlLanguageBackend.kt) |
| Kotlin | [KotlinLanguageBackend.kt](../lang-kotlin/src/main/kotlin/dev/ide/lang/kotlin/KotlinLanguageBackend.kt), and [kotlin-completion.md](kotlin-completion.md) |
| Java | [JavaLanguageBackend.kt](../lang-java/src/main/kotlin/dev/ide/lang/java/JavaLanguageBackend.kt) |
| AIDL (tier 1) | [AidlAnalyzer.kt](../ide-core/src/main/kotlin/dev/ide/core/analysis/AidlAnalyzer.kt), [AndroidAidlProvider.kt](../android-support/src/main/kotlin/dev/ide/android/support/AndroidAidlProvider.kt) |
| Plain text fallback | [PlainTextAnalyzer.kt](../ide-core/src/main/kotlin/dev/ide/core/PlainTextAnalyzer.kt) |
