# How Kotlin completion and editor analysis work

CodeAssist provides Kotlin editor support — completion, resolution, diagnostics, quick-fixes, and the
usual highlighting/navigation surface — **on device**, without running the Kotlin compiler's resolution
pipeline. The Kotlin backend uses the compiler only to *parse*, then builds its own symbol model, a
type-inference subset, a flow/reachability layer, and completion + a semantic-diagnostics engine on top
of the framework's backend-neutral DOM. The result is tuned to the same ranked, low-latency experience as
the Java/JDT backend.

This document explains the approach. For the editor SPI these pieces implement, see
[extension-points.md](extension-points.md) and [language-support.md](language-support.md). Kotlin-to-bytecode
compilation for the build is a separate track — see [build-system.md](build-system.md) and
[kotlin-compiler-plugins-and-codegen.md](kotlin-compiler-plugins-and-codegen.md).

## Why not full compiler resolution?

Running the Kotlin compiler's frontend (FIR/resolution) for every keystroke is too heavy for an on-device
editor. Instead the backend treats the compiler as a *parser only*:

1. A resolution-free **standalone PSI host** stands up a single `KotlinCoreEnvironment` and turns editor
   text into a `KtFile` (error-tolerant — a half-typed buffer still parses). An edit reparses only the
   changed subtree of the prior PSI in place (falling back to a full parse), so a keystroke on a large
   file is cheap.
2. The PSI tree is adapted to the framework's neutral DOM (`DomNode`/`ParsedFile`), so every editor
   feature works against the same tree shape as Java.
3. **All resolution and FIR output is discarded.** The backend builds its own symbol table, type
   inference, flow analysis, completion, and diagnostics from the PSI plus the project classpath.

## Symbol sources

Completion candidates and resolution targets come from two places:

- **Project sources.** Every project `.kt` file is parsed to PSI and scanned into a declaration index
  (classes, functions, properties, and extension functions), updated incrementally as files change. Live
  editor buffers are folded in so a declaration just typed in another open file (or the buffer being
  edited) resolves before it is saved and reindexed.
- **Classpath binaries.** For library and SDK types the backend branches on the `@kotlin.Metadata`
  annotation:
  - **Kotlin libraries** are decoded with `kotlin-metadata-jvm`, recovering the real Kotlin shape that
    plain bytecode erases — extension functions, properties, default arguments, and nullability.
  - **Plain Java/Android types** are read directly from bytecode with ASM.

  The classpath scan is **lazy and persistent**: results are cached per jar in a content-keyed sidecar,
  and jars with no Kotlin module metadata are skipped without decoding a class.

Synthetic ("light") classes contributed by the host — Android `R`/`BuildConfig`, ViewBinding — and
compiler-plugin-generated members the parse-only model can't see (kotlinx.serialization's `serializer()`,
via the `platform.kotlinSyntheticMember` EP) are injected so they resolve and complete like real types.
Android framework namespaces (`android.*`/`androidx.*`) are hidden from type-name completion only in a
module that genuinely can't use them; on-classpath evidence always keeps them visible.

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
- member and call return types (including overload resolution),
- constructor calls,
- chained calls (`a.b().c`),
- generic type-argument inference,
- lambda / SAM parameter and receiver types,
- **smart casts** and **null-flow narrowing** (see below).

This is deliberately a subset — enough to resolve and rank completion well, and to drive the diagnostics,
without a full type checker.

### Generic and lambda inference

Generic type arguments are inferred through a **bounded constraint system** (`KotlinConstraintSystem`) —
the editor resolver's stand-in for the K2 compiler's `NewConstraintSystem`. Each type variable accumulates
LOWER/UPPER/EXACT bounds; a subtyping constraint is decomposed (recursing over type arguments, projecting
across classifiers) down to bounds or a concrete subtyping check, and a contradiction marks an overload
candidate inapplicable — which is what overload resolution reads to discard it. Generic subtyping is
decomposed covariantly (exact for the read-only collection types inference rides, a safe
over-approximation elsewhere: it can only miss a contradiction, never invent one). `KotlinGenericInference`
handles lower-bound propagation, uninferable type parameters, and missing-argument checks;
`KotlinLambdaInference` supplies the expected functional type, receiver, and parameter types for a lambda
from its enclosing call slot.

### Smart casts

A simple-name reference is narrowed by an enclosing `is` check, purely from its **position** in the
parse tree (no flow graph): `if (x is T) x.‹member›` resolves `x`'s members against `T`. Covered shapes,
in `KotlinResolver.smartCastTypeAt`:

- the then-branch of `if (x is T)` and the else-branch of `if (x !is T)`;
- the short-circuit RHS of `x is T && …` and `x !is T || …`;
- a `when (x) { is T -> … }` branch (single positive `is`; a subject `val` narrows too);
- a `while (x is T)` body;
- the statements after an early-exit guard `if (x !is T) return` / `throw` / `break` / `continue`.

The cast target's generic arguments are erased and it is made non-null (`is T` implies `T`). Because a
name reference's smart cast is a function of its position alone, `inferType`'s per-snapshot cache holds it
correctly. Both completion and the diagnostics consume it for free, since each resolves a receiver through
`inferType`. It is conservative in the same direction as the rest of the model: a missed narrowing only
under-reports, a spurious one only fails to flag an error, never the reverse. The preview interpreter keeps
its own flow-driven narrowing; this position-based path is gated off while that stack is active.

### Null-flow narrowing

Nullability narrowing — the null-check half of smart casting — proves a value non-null after a guard
(`if (x != null)`, `x ?: return`, `x!!`, `requireNotNull(x)`), gated by the Kotlin smart-cast **stability**
rules so a conclusion is only drawn for a value that genuinely cannot change:

- `KotlinNullFlow` covers **immutable** values (a `val` local without a delegate, a
  function/lambda/`for`/`catch` parameter) with the same cheap position-based ancestor walk as the `is`
  smart casts — a dominating guard proves non-null with no need to reason about intervening writes.
- `KotlinVarNullFlow` covers **local `var`s** with a small CFG-based data-flow pass: it narrows at a guard,
  RESETS at each reassignment, and drops any `var` written inside a loop or closure from the analysis.

Everything the passes can't model soundly (a `var` captured in a closure, a delegated or custom-getter
property, a qualified receiver, an `open` member) is left UNKNOWN, so the result under-reports and never
produces a false "non-null". This feeds the null-family checks (`kt.unsafeNullable`, `kt.redundantNotNull`,
`kt.redundantSafeCall`, `kt.uselessElvis`, `kt.senselessComparison`).

### Control flow / reachability

`KotlinControlFlow` is a structured abstract interpretation over the PSI (a data-flow lattice over the CFG
without materialising the graph) computing whether control can complete a construct normally. Its
three-valued result (LIVE / DEAD / UNKNOWN) drives precise **missing return** (does every path return?)
and **unreachable code** (is a statement's entry dead?) — flagging only on a definite verdict, so an
unmodelled construct degrades to UNKNOWN and under-reports rather than false-positives.

## Completion

At the caret the backend splices a completion marker (the dummy-identifier technique), parses, finds the
marker element, and classifies the position to decide the candidate set:

- **member access** after `.` / `?.` → the receiver's members ∪ extensions (or a package's members);
- **name reference** in expression position → scope symbols + visible types;
- **type reference** in a type position → visible classifiers;
- **infix name** at a binary operator → infix functions applicable to the left operand.

Candidates are prefix-filtered, ranked with the shared prefix/fuzzy/proximity scorer, and mapped to
neutral `CompletionItem`s; auto-import edits (in sorted insertion position) come from `KotlinAutoImport`.
In expression position three extra contributions merge ahead of the plain candidates:

- **named arguments** — inside a call's argument list, the callee's not-yet-supplied parameter names (`name = `);
- **override stubs** — at a member-declaration spot in a class body, overridable inherited members
  (`override fun foo(): T { TODO(…) }`);
- **expected type** — `true`/`false` where a Boolean is wanted and `Enum.CONSTANT` at an enum slot, and the
  ranking floats candidates whose type is assignable to the expected type first.

Beyond symbols, the popup also offers:

- **keywords, modifiers, and live templates** (`KotlinKeywords`) — offered only where the grammar admits
  them, driven by a caret `Place` read off the PSI ancestor chain (statement / member / top-level /
  expression / the various parameter slots), with a few positions (`by`, variance, accessors, `where`)
  handled by dedicated predicates. Curated per-position sets derived by probing the real parser, so no
  re-parse per keystroke is needed;
- **postfix templates** (`KotlinPostfixTemplates`, via `platform.postfixTemplate`) — `cond.if`,
  `value.val`, `list.for`, `expr.let` (null-safe idiom) rewrite the whole expression, gated on the
  receiver's resolved type.

### Compose-aware completion

In a `@Composable` context the calling-convention analysis (`KotlinCallingConvention`, walking PSI
boundaries) boosts `@Composable` callables to the top of the popup (a ranking boost, not a filter — normal
code stays available). The same analysis backs the `kt.composableInvocation` diagnostic (a `@Composable`
call from a confidently non-composable context) and the `kt.suspendContext` check for `suspend` calls.

## Diagnostics

The Kotlin backend now runs a full **semantic-analysis pipeline**, not just parse-level well-formedness.
`KotlinSemanticChecks` implements the checks; `IncrementalSemanticAnalysis` runs them with per-declaration
caching (an edit re-checks only the declarations that changed, re-anchoring the rest) and publishes into
the shared analysis pipeline alongside the other languages. Diagnostics are gated on "dumb mode" — library
symbols resolve to nothing while the workspace index is still building, so the checks back off until it's
ready to avoid false "unresolved" reports.

The `kt.*` codes are centralized in `KotlinDiagnosticCodes` (the string values are the contract quick-fixes
and tests key on). Categories, with representative codes:

- **Resolution / calls / types** — `kt.unresolved`, `kt.typeMismatch`, `kt.argumentCount`,
  `kt.constructorArgs`, `kt.namedArgument`, `kt.notCallable`, `kt.cannotInferType`, `kt.overloadAmbiguity`,
  `kt.classifierAsValue`, `kt.destructuring`, `kt.delegateOperator`.
- **Nullability** — `kt.unsafeNullable`, `kt.redundantNotNull`, `kt.redundantSafeCall`, `kt.uselessElvis`,
  `kt.senselessComparison`.
- **Control flow** — `kt.missingReturn`, `kt.unreachable`, `kt.uninitializedVariable`, `kt.whenExhaustive`,
  `kt.duplicateWhenBranch`, `kt.unreachableCatch`.
- **Declarations / modifiers** — `kt.conflictingDeclaration`, `kt.conflictingImport`, `kt.modifiers`,
  `kt.valReassign`, `kt.valVarParameter`, `kt.mustBeInitialized`, `kt.lateinit`, `kt.constMisuse`, `kt.functionNoBody`.
- **Inheritance / override** — `kt.abstractNotImplemented`, `kt.nothingToOverride`, `kt.overrideRequired`,
  `kt.abstractInstantiation`, `kt.supertypeNotInitialized`.
- **Generics / variance** — `kt.typeArgumentCount`, `kt.upperBoundViolated`, `kt.varianceConflict`,
  `kt.conflictingProjection`, `kt.redundantProjection`.
- **Redundancy / style (warnings/hints)** — `kt.unusedImport`, `kt.unusedPrivate`, `kt.unusedLocal`,
  `kt.unusedParameter`, `kt.varCouldBeVal`, `kt.uselessCast`, `kt.castNeverSucceeds`, `kt.uselessIsCheck`,
  `kt.incomparableEquality`, `kt.usePropertyAccess`, `kt.redundantStringTemplate`, `kt.unusedExpression`,
  `kt.nameShadowing`, `kt.assignmentInExpression`, `kt.variableExpected`, `kt.deprecation`.
- **Compose** — `kt.composableInvocation`, `kt.suspendContext`, plus the preview checks
  `kt.previewNotComposable` / `kt.previewParameters`.

Like the rest of the model the checks are conservative — an unmodelled shape under-reports rather than
producing a false error.

### Quick-fixes

Diagnostics carry code-keyed fixes into the editor lightbulb / Alt-Enter:

- **Import** — for each `kt.unresolved` name under the caret, one fix per candidate FQN (top-level callable
  or type), spliced in sorted position; a `kt.delegateOperator` additionally offers the missing
  `getValue`/`setValue` import (`import androidx.compose.runtime.getValue` for `by mutableStateOf`).
- **Implement members** — for `kt.abstractNotImplemented`, generate `override` stubs for the unimplemented
  inherited members and insert them into the class body (same stub text completion's override items use).

## Other editor features

The same parse-only model powers the rest of the editor surface:

- **Go-to-definition** across project files and the classpath, and **find implementations** — gutter
  markers on an inheritable type (interface, `open`/`abstract`/`sealed` class) with direct inheritors in
  the `SubtypeIndex`, navigating to a source subtype.
- **Quick documentation on hover** (raw KDoc from the declaration's PSI, or the resolved symbol's doc) and
  **signature help** in a call's argument list.
- **Inlay hints**, **semantic highlighting** (type-aware callee/reference coloring), **code folding**,
  **re-indentation formatting** and **Optimize Imports** (sort/dedupe/collapse + drop unused), and a
  **structure view / sticky-scroll headers** from the syntactic tree.

All of these run on the engine's single serialized worker; per-snapshot resolver memo caches are shared
across a keystroke's passes (diagnostics, highlight, inlays, preview) so only the first pass pays the
inference/overload-resolution cost.

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

This now flows through ONE `IndexExtension` (`KotlinCallableIndex`, id `kotlin.callables`) on
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
and fall back to the in-memory `ClasspathReader.scan`/`.kxt` ONLY when there is no index — the standalone /
unit-test path, exactly as `kotlin.typeShape` keeps a live-decode fallback. So the cold build of the
in-memory scan no longer happens in the wired (IDE) configuration; extension completion instead degrades to
empty while the index is still building (the same graceful-degrade contract as type-name completion).
Verified by `KotlinCallableIndexTest`.

## Compilation

Editor analysis is independent of code generation. Kotlin-to-bytecode compilation for the build is a
separate track: an in-process K2 compiler with per-file, ABI-aware incremental compilation (a body-only
edit recompiles just the changed file; an ABI change falls back to a module recompile). It runs on
device (ART), not only on the desktop JVM. See [build-system.md](build-system.md) for how the build graph
drives it and how Java/Kotlin interop is wired.

## Status

Kotlin support is **beta**. The list below reflects what ships today and what is planned.

### What works today

- **Completion**: member completion after `.`/`?.` (including stdlib **extension functions** resolved
  through the receiver's supertype chain), name/scope and type completion, infix functions, named
  arguments, override stubs, expected-type ranking, **keywords / modifiers / live templates**, and
  **postfix templates**. `@Composable` callables are boosted in a composable context.
- **Resolution** from project sources and classpath binaries, with Kotlin libraries decoded from
  `@kotlin.Metadata` and Java/Android types read from bytecode; synthetic (`R`/ViewBinding) and
  compiler-plugin (`serializer()`) members injected.
- **Inference subset**: locals from initializers, member/call return types, overload resolution, constructor
  calls, `a.b().c` chains, **generic type-argument inference** (a bounded constraint system), and
  **lambda/SAM** parameter/receiver types.
- **Smart casts** after `is` (`if`, `!is`+early-return, `&&`/`||`, `when`, `while`) and **null-flow
  narrowing** for immutable values and (CFG-based) local `var`s.
- **Semantic diagnostics**: a broad `kt.*` catalog — unresolved references, type mismatch, argument/overload
  errors, nullability, missing return, unreachable/uninitialized, non-exhaustive `when`, `val` reassignment,
  override/abstract correctness, generics/variance, unused symbols/imports, redundant casts/elvis/`!!`,
  deprecation, and the Compose composable-invocation check — run incrementally and merged into the shared
  analysis pipeline.
- **Quick-fixes**: Import (unresolved name / delegate operator), Implement members.
- **Navigation & docs**: go-to-definition, find implementations (inheritor gutter markers), quick
  documentation (KDoc) on hover, signature help.
- **Editor surface**: inlay hints, semantic highlighting, code folding, formatting, Optimize Imports,
  structure view / sticky scroll.
- **Kotlin → bytecode build**: in-process K2 codegen with per-file, ABI-aware incremental compilation,
  running on device (ART), with Java↔Kotlin interop wired into the build graph.

### Planned / TODO

- **Find usages and rename** for Kotlin symbols (Java rename ships; Kotlin is not yet wired).
- **More quick-fixes / intentions** keyed off the existing diagnostics (make `var`, add non-null assertion,
  remove redundant cast, remove unused import/symbol, add `override`).
- **Visibility diagnostics** (using an `internal`/`private` declaration out of scope).
- **Inference improvements** — see *Known limitations* below.

### Known limitations

These are deliberate simplifications of the editor-time model (the build's K2 compiler is unaffected):

- **No full type checker.** Resolution is a pragmatic subset aimed at resolving/ranking and driving
  diagnostics, not at verifying a program. The checks are conservative (they under-report rather than
  false-positive), so code that does not type-check can still produce completions.
- **Partial smart casts / null flow.** `is` narrowing is modeled position-by-position for simple-name
  subjects in the supported shapes; null narrowing covers immutable values and local `var`s but backs off
  on delegated/custom-getter properties, qualified receivers, `open` members, and `var`s written in a
  loop/closure (errs toward NOT narrowing).
- **Generic inference is bounded, not complete.** The constraint system decomposes generics covariantly and
  omits use-site variance projections, so it can miss a contradiction (degrading to a tiebreak), and
  completions on a heavily generic expression may fall back to the erased/declared shape.
- **Limited lambda / SAM inference.** Parameter and return types inferred *through* lambdas and SAM
  conversions are only partially handled.
- **Independent of the compiler frontend.** Because the editor model does not use the compiler's
  resolution, its conclusions can differ from the compiler's in edge cases.
