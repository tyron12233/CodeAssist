# Language support, completion, and analysis

Editor intelligence is built on a backend-neutral DOM and a small set of SPIs. A language backend
parses source into a tolerant DOM, resolves symbols, and offers completion; the analysis pipeline
merges compiler errors and analyzer findings into one diagnostic stream. See
[extension-points.md](extension-points.md) for the `LanguageBackend` contract.

## The backend-neutral DOM

Every backend adapts its native tree to one stable interface so IDE features never depend on a
specific parser:

```kotlin
interface DomNode { val kind: NodeKind; val range: TextRange; val children: List<DomNode>; val parent: DomNode? }
interface ParsedFile : DomNode { val diagnostics: List<Diagnostic>; fun resolve(node: DomNode): Symbol? }
```

`NodeKind` is an open, string-backed classification so new languages add kinds without changing the
core. Resolution targets neutral `Symbol`/`Scope`/`TypeRef` types.

## Code completion

Completion uses an error-tolerant AST that always covers the whole file and the completion-marker
(dummy-identifier) technique: a marker is spliced at the caret so the half-typed buffer parses, and
the surrounding context (member access, qualifier, scope) determines the candidate set. Incremental
reparse keeps editing responsive on large files.

The index is queried for unimported-class (auto-import) and package completion; ranking uses a shared
prefix/fuzzy scorer.

## Language backends

### Java (Eclipse JDT/ecj) — the default

JDT/ecj is pure Java, error-tolerant, incremental, and light on ART, which makes it the default editor
and compile backend. It provides working-copy reconcile, built-in completion, and batch compilation to
`.class`. A bytecode members index supports member completion on library and SDK types.

### XML

An Android-agnostic XML backend for layouts, values, manifests, drawables, and menus. A hand-written,
error-tolerant parser never throws on a half-typed buffer (unclosed or mismatched tags recover through
an open-name stack with diagnostics) and produces a neutral DOM. A context scanner computes where the
caret is (tag name, attribute name, attribute value) and a completion service merges candidates from
injected contributors — the host supplies what belongs where. Android-specific knowledge (widget
hierarchy, attribute inheritance, resource references) lives in a contributor driven by SDK-derived
metadata and a resource index, not in the parser.

### Kotlin

An editor-time Kotlin backend that uses the Kotlin compiler only to parse. A resolution-free standalone
PSI host turns text into a `KtFile` and adapts it to the neutral DOM; the backend then builds its own
symbols, a declared-type-driven inference subset, and completion on top. Two symbol sources feed it:
project `.kt` files parsed to a declaration index, and classpath binaries (Kotlin libraries decoded
from their metadata, plain Java/Android types read from bytecode). Kotlin-to-bytecode codegen is a
separate track wired into the build, with per-file ABI-aware incremental compilation. For the full
approach, see [kotlin-completion.md](kotlin-completion.md).

## Indexing

The indexing engine has two sides:

- **Static (SDK + library) indices** are disk-backed immutable segments — one per artifact, keyed by
  content hash, queried in place through a bounded block cache. Heap stays flat regardless of index
  size; the only resident state is a sparse term index. Because the segments are content-addressed
  (identical jars produce identical segments), they live under the host's **shared cache root** and are
  **reused across projects** (like the dex and Maven caches), so each project doesn't re-index the same
  AndroidX/Compose/stdlib jars. Segment writes are atomic (unique temp + atomic rename), so concurrent
  builders of the same segment can't corrupt it. A per-project `invalidate()` ("Re-index") deletes only
  that project's own segments, never another project's, in the shared store.
- **Source indices** are in-memory and incremental on edit (always per-project).

Built-in indices cover class names, packages, source symbols, bytecode members, and Android resources.
Completion, go-to-symbol, and the unresolved-resource inspection all query the index, with a
repository fallback while it builds.

## Analysis, diagnostics, and quick-fixes

There is one diagnostic model and one pipeline: compiler errors and analyzer findings merge into the
same stream. Analyzers are shaped as file or project analyzers over tiers (`SYNTAX`, `SEMANTIC`,
`PROJECT`). The engine runs file analyzers over one shared DOM walk (gated by node kind), runs the
compiler as a diagnostic provider, applies the analysis profile (enable/severity) and suppression
(`@Suppress` scoped to the enclosing declaration, plus next-line suppression comments), and publishes a
version-gated result set to listeners. Work is debounced per tier and cancellable.

Quick-fixes are keyed by diagnostic code (including compiler codes) and produce workspace edits that
reuse the editor's document-edit type, so applying a fix is atomic and triggers re-analysis.

## Block (projectional) editing

The block editor is a live projection of the shared DOM, not a parallel model. A block mapping
decomposes statements and key expressions into a block tree; a block edit compiles back to a minimal
document edit so untouched code survives byte-for-byte. Typed value sockets (boolean / number / string
/ type / object) drive Scratch-style socket shapes, inferred syntactically with a hook for a semantic
resolver to refine later. For the projection and edit round-trip, see [block-editing.md](block-editing.md).
