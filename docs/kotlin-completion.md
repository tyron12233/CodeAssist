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
