# How Kotlin completion works

CodeAssist provides Kotlin code completion **on device** without running the Kotlin compiler's
resolution pipeline. The Kotlin backend uses the compiler only to *parse*, then builds its own symbol
model, a small type-inference subset, and completion on top of the framework's backend-neutral DOM. The
result is completion tuned to the same ranked, low-latency experience as the Java/JDT backend.

This document explains the approach. For the editor SPI these pieces implement, see
[extension-points.md](extension-points.md) and [language-support.md](language-support.md).

## Why not full compiler resolution?

Running the Kotlin compiler's frontend (FIR/resolution) for every keystroke is too heavy for an on-device
editor. Instead the backend treats the compiler as a *parser only*:

1. A resolution-free **standalone PSI host** stands up a single `KotlinCoreEnvironment` and turns editor
   text into a `KtFile` (error-tolerant — a half-typed buffer still parses).
2. The PSI tree is adapted to the framework's neutral DOM (`DomNode`/`ParsedFile`), so every editor
   feature works against the same tree shape as Java.
3. **All resolution and FIR output is discarded.** The backend builds its own symbol table, type
   inference, and completion from the PSI plus the project classpath.

## Symbol sources

Completion candidates come from two places:

- **Project sources.** Every project `.kt` file is parsed to PSI and scanned into a declaration index
  (classes, functions, properties, and extension functions), updated incrementally as files change.
- **Classpath binaries.** For library and SDK types the backend branches on the `@kotlin.Metadata`
  annotation:
  - **Kotlin libraries** are decoded with `kotlin-metadata-jvm`, recovering the real Kotlin shape that
    plain bytecode erases — extension functions, properties, default arguments, and nullability.
  - **Plain Java/Android types** are read directly from bytecode with ASM.

  The classpath scan is **lazy and persistent**: results are cached per jar in a content-keyed sidecar,
  and jars with no Kotlin module metadata are skipped without decoding a class.

## Mapped types and built-ins

Kotlin's mapped types (`String`, `List`, `Int`, …) have no `.class` of their own — they map onto JVM
types at compile time. The backend bridges them with a Kotlin→Java type map plus built-in supertype
chains, so member completion on a `String` or `List` resolves to the expected Kotlin surface. An
**extension index keyed by receiver FQN** (walking supertypes) makes standard-library extensions such as
`list.map` or `string.trim` appear on `.`.

## Resolution and inference

Resolution derives scopes from PSI parents; a declared-type-driven inference subset covers the common
editor cases:

- locals typed from their initializers,
- member and call return types,
- constructor calls,
- chained calls (`a.b().c`).

This is deliberately a subset — enough to rank completion well without a full type checker.

## Completion

At the caret the backend splices a completion marker (the dummy-identifier technique), parses, and reads
the surrounding context to decide the candidate set: member completion after `.`, name completion in a
scope, or type completion in a type position. Candidates are ranked with the shared prefix/fuzzy scorer,
and default Kotlin imports are taken into account.

## Performance with large classpaths (Compose / AndroidX)

Type-name completion is served from the disk-backed index (`java.classNames`/`kotlin.typeShape`) — prefix-
queried and capped, so it scales with the number of *matches*, not the size of the classpath. **Extensions
and top-level callables** were the exception: they came from an in-memory scan (`ClasspathReader.scan`,
backed by a per-jar `.kxt` cache) that was loaded whole and returned whole, with the prefix filter applied
only afterward. With a large Kotlin classpath that made every keystroke do work proportional to the *entire*
library surface.

### Shipped — prefix pushdown + memoization

The completion path now pushes the typed prefix down into the symbol service so a keystroke is O(matches):

- **Top-level callables** (`KotlinSymbolService.topLevelCallables(prefix)`): the by-name map is walked for
  buckets whose name starts with the prefix instead of `values.flatten()`-ing the whole top-level universe.
  `KotlinResolver.scopeSymbolsAt(offset, namePrefix)` threads the prefix through (and `callTargets` passes
  the known callee name as the prefix).
- **Members + extensions** (`membersForCompletion(fqn, args, prefix)` / `extensionsFor(fqn, args, prefix)`):
  extensions are filtered by name **before** `bindExtensionReceiver` allocates a bound symbol per generic
  receiver, so the large per-receiver and `kotlin.Any` buckets no longer materialize in full each keystroke.
  `companionMembersFor` is prefix-aware too.
- **Supertype memo** (`supertypeMemo`): the recursive Kotlin supertype walk (the hot part of
  `extensionsFor`/`supertypesOf`) is memoized per receiver FQN, dropped on any edit (`setOverlay`), since a
  source type's chain can change but a classpath type's cannot.

These keep the analyzer's existing serialization/correctness (the same single engine thread; see the editor
threading notes) and don't change the non-completion `membersOf` path, so analysis/resolution are unaffected.

### Shipped — extensions + top-level moved into the persistent disk index

The remaining cost was the **first** member/name completion after launch (or after the analyzer is rebuilt):
it triggered `ClasspathReader.scan`, which decodes the `@Metadata` of every class in every Kotlin jar to
harvest extensions + top-level callables — a blocking decode storm, and even warm it stayed fully
heap-resident.

This now flows through ONE new `IndexExtension` (`KotlinCallableIndex`, id `kotlin.callables`) on
`platform.index`, mirroring `KotlinTypeShapeIndex`: the engine builds it during indexing (per `.class`,
content-hashed, persisted, block-cached, incremental) and the symbol service queries it instead of the
scan. Keys are **tagged so one ordered index serves both query shapes**:

- **top-level callable** -> key `top:<name>`. Completion prefix-queries `top:<namePrefix>`; resolution
  exact-queries `top:<name>`. Nothing is fully resident.
- **extension** -> key `ext:<receiverFqn> <name>`. Completion expands the receiver to
  `{ fqn, kotlin.Any, ...supertypes }` and prefix-queries `ext:<target> <namePrefix>` per target, so BOTH
  dimensions (receiver and name) are filtered on disk; the large `kotlin.Any` bucket no longer loads in full.
  The space separator (FQNs/identifiers contain none) keeps each receiver's extensions contiguous in the
  ordered terms.

The value is a context-free `CallableShape` (the `KotlinSymbol` fields the completion item + auto-import +
interpreter need); the consumer rebinds the live resolution context via `toSymbol(ctx)` at query time, like
`kotlin.typeShape`. Per-class `index(input)` decodes the file/multi-file facade (`FooKt`) and emits
`decoded.topLevel` / `decoded.extensions`, reusing the engine's existing `.class` traversal.

`KotlinSymbolService.extensionsFor`/`topLevelCallables`/`topLevelByName` query the index WHEN ONE IS WIRED
(the prefix-pushdown work above was shaped so this is just a data-source swap behind unchanged signatures)
and fall back to the in-memory `ClasspathReader.scan`/`.kxt` ONLY when there is no index -- the standalone /
unit-test path, exactly as `kotlin.typeShape` keeps a live-decode fallback. So the cold build of the
in-memory scan no longer happens in the wired (IDE) configuration; extension completion instead degrades to
empty while the index is still building (the same graceful-degrade contract as type-name completion).
Verified by `KotlinCallableIndexTest` (producer entries, codec round-trip, and an index-wired analyzer
resolving an stdlib extension + top-level callable through the index, not the scan).

## Compilation

Editor completion is independent of code generation. Kotlin-to-bytecode compilation for the build is a
separate track: an in-process K2 compiler with per-file, ABI-aware incremental compilation (a body-only
edit recompiles just the changed file; an ABI change falls back to a module recompile). It runs on
device (ART), not only on the desktop JVM. See [build-system.md](build-system.md) for how the build graph
drives it and how Java/Kotlin interop is wired.

## Status

Kotlin support is **beta**. The list below reflects what ships today and what is planned.

### What works today

- **Member completion** after `.`, including standard-library **extension functions** (`list.map`,
  `string.trim`) resolved through the receiver's supertype chain.
- **Name/scope completion** and **type completion** in type positions.
- **Resolution** from project sources and classpath binaries, with Kotlin libraries decoded from
  `@kotlin.Metadata` (extensions, properties, default args, nullability) and Java/Android types read
  from bytecode.
- The declared-type-driven **inference subset** (locals from initializers, member/call return types,
  constructor calls, `a.b().c` chains) that drives completion ranking.
- **Go-to-definition** across project files and the classpath.
- **Syntax / well-formedness diagnostics** surfaced from the tolerant parse.
- **Kotlin → bytecode build**: in-process K2 codegen with per-file, ABI-aware incremental compilation,
  running on device (ART), with Java↔Kotlin interop wired into the build graph.

### Planned / TODO

- **Richer semantic diagnostics.** Today only parse-level issues surface; the goal is a Kotlin diagnostic
  provider feeding the shared analysis pipeline. Candidate checks, roughly in priority order:
  - unresolved references (unknown name / member / import);
  - type mismatch (assignment, argument, and return position);
  - nullability violations (calling a member on a nullable receiver, passing `null` to a non-null
    parameter, unnecessary `!!`);
  - missing return / not all paths return a value;
  - non-exhaustive `when` over a sealed type or enum;
  - `val` reassignment and assignment to an immutable;
  - unused symbol / unused import / unused parameter;
  - unreachable code and redundant casts;
  - override and abstract-member errors (missing `override`, unimplemented members);
  - visibility violations (using an `internal`/`private` declaration out of scope);
  - deprecation warnings (`@Deprecated`).
- **Quick-fixes / intentions** keyed off those diagnostics (add import, add missing members, make `var`,
  add non-null assertion, remove redundant cast).
- **Find usages and rename** for Kotlin symbols.
- **Inference improvements** — see *Known limitations* below.
- **KDoc on hover** and signature help.

### Known limitations

These are deliberate simplifications of the editor-time model (the build's K2 compiler is unaffected):

- **No full type checker.** Resolution is a pragmatic subset aimed at ranking completion well, not at
  verifying a program. Code that does not type-check can still produce completions.
- **No smart casts.** Flow-sensitive narrowing after an `is` check or a null guard is not modeled, so a
  member that only exists after a smart cast may not appear.
- **Limited generic inference.** Generic type arguments are not fully substituted through call chains;
  completions on a heavily generic expression may fall back to the erased/declared shape.
- **Limited lambda / SAM inference.** Parameter and return types inferred *through* lambdas and SAM
  conversions are only partially handled.
- **Independent of the compiler frontend.** Because the editor model does not use the compiler's
  resolution, its conclusions can differ from the compiler's in edge cases.
