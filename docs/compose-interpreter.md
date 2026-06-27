# Interpreting Composable functions on device

**Status:** design of record + de-risking spike. No production modules exist yet. The spike under
`:ide-android` (`dev.ide.android.spike.ComposeAbiSpikeTest`) validates the riskiest assumption before any
module is built — and is **GREEN on device** (emulator, API "17"/Android baklava image, 2026-06-18): all
three rungs pass, retiring risk #2 (the Compose synthetic-param ABI).

## Goal

Run a project's `@Composable` UI **interpreted, on device**, with real recomposition and state, against the
**real AndroidX/Material3** runtime — without compiling the user's code to `.class` and dexing it. The
compile→dex path (`docs/kotlin-completion.md`'s sibling, the on-device Kotlin build) is correct but too slow
for an edit-render loop: the cost is the compiler backend (IR lowering, codegen) and especially D8. An
interpreter skips both.

## Why interpret instead of compile

A normal `@Composable` is **not** plain Kotlin — the Compose compiler plugin rewrites it heavily:

- appends an implicit `Composer $composer` and one or more `int $changed` parameters to every composable,
- wraps restartable bodies in `startRestartGroup(key)` … `endRestartGroup()?.updateScope { … }`,
- drives `remember` via slot-table **positional memoization** keyed by source position,
- wraps composable lambdas in `ComposableLambda`, and
- relies on the snapshot system so `MutableState` reads register recomposition dependencies.

The reframe that makes interpretation tractable: **we re-implement the plugin's transform as interpreter
behaviour, but drive the *real* `androidx.compose.runtime` machinery.** We never re-build the slot table,
snapshot system, or recomposer — correctness comes from the real runtime. We only supply the composer
threading and group structure the plugin would have generated, and we call precompiled library composables
through their plugin-mangled JVM signatures.

## Architecture

```
lang-kotlin (resolution)          constrained sound resolver → a frozen "ResolvedTree"   [exists]
  └─ interp-core                  tree-walking interpreter + Dispatcher seam               [exists — steps 3, 4a]
       └─ interp/compose          Compose bridge (ComposeDispatcher + ComposableAbi)       [exists in :ide-android — step 4b]
            └─ ide-android        hosts the real Compose runtime; Applier → renderer
```

The Compose bridge lives as the `dev.ide.interp.compose` package inside `:ide-android` (where the real
Compose runtime + the on-device spikes already are) rather than its own Gradle module; it can be extracted
to `:interp-compose` later if a non-Android consumer needs it.

`:interp-core` is a pure-JVM module depending on `:lang-kotlin` (so it builds/tests under `CI_CORE_ONLY`,
no Android SDK). The interpreter itself is PSI-free — it only walks `ResolvedTree`; the Kotlin compiler PSI
is a test-only dependency there, used to drive lowering in tests.

### 1. The resolver → interpreter contract (`ResolvedTree`)

The interpreter must never touch the editor resolver's heuristics. The editor `KotlinResolver`
(`lang-kotlin`) is first-candidate-by-arity, types only a handful of node kinds, and degrades to `null` on
anything it can't handle — fine for completion, **unsound for execution**. The interpreter consumes a
separate, *total* artifact: for every node it accepts, an exact answer; for anything outside the supported
subset, a typed `Unsupported` (a clear diagnostic, never a wrong guess).

```kotlin
sealed interface RNode
// every call-site carries its exact, overload-resolved callee + dispatch shape
class RCall(
    val callee: ResolvedCallable,
    val dispatch: DispatchKind,        // MEMBER | EXTENSION | TOP_LEVEL | OPERATOR | INVOKE
    val receiver: RNode?,
    val args: List<RArg>,
    val callSiteKey: Int,              // stable per call-site (PSI offset) — the positional-memoization key
) : RNode
class RName(val binding: Binding) : RNode   // LOCAL | PARAM | PROPERTY | OBJECT
// operators/conventions are PRE-DESUGARED here, NOT at interpret time:
//   a + b → RCall(plus);  a[i] → RCall(get);  a += b → plusAssign | (plus + set);
//   for (x in xs) → iterator()/hasNext()/next();  delegated property → getValue/setValue
```

Building this contract first is non-negotiable — it is the boundary that keeps the resolver independently
testable and the interpreter simple.

### 2. Interpreter core (`interp-core`)

A standard tree-walker over `ResolvedTree`:

- **Values:** boxed `Any?`. User objects are an `InterpretedInstance(class, fields)`; library objects are
  real JVM references.
- **Environment:** lexical scopes with mutable slots; closures capture by reference.
- **Control flow:** `return`/`break`/`continue` as internal signals.
- **Library bridge:** any `RCall` whose callee is a binary (non-source) symbol is invoked **reflectively** on
  the real loaded class. The symbol service already decodes `@Metadata`, so inline/suspend/receiver shape is
  known.
- **No codegen, no dex** — the entire point.

#### Source types & extended language subset (implemented)

Project-source types aren't compiled at preview/run time, so the interpreter materializes them itself rather
than reflecting bytecode that doesn't exist:

- **`SourceObject`** (the `InterpretedInstance` above) — a `ResolvedClass` + a name→value field map. The
  resolver lowers every class/object/enum to a `ResolvedClass` (`KotlinTreeResolver.lowerClasses()`): primary
  constructor params (the `val`/`var` ones are properties), body-property initializers + `init` blocks (run in
  source order during construction), member functions (lowered with an implicit `this` receiver slot),
  supertypes, and enum entries. A bare member access / call / `this` inside a member resolves against a
  pushed *class context*, not the editor resolver (which doesn't model source implicit receivers).
- **Construction** binds the primary params (defaults + named args via `reorderNamedArgs`) and runs the init
  steps; **member dispatch** on a `SourceObject` interprets the member body (or routes a synthetic
  `componentN`/`contains` there). **Objects/companions** are lazily-built singletons (a class name resolves to
  its companion); **enum entries** are tagged singletons (`name`/`ordinal`/`values()`/`valueOf()`/`entries`).
- **Data-class members** (`copy`/`componentN`/`equals`/`hashCode`/`toString`) are compiler-synthesized, so the
  source index never records them — the interpreter computes them from the class's component properties, and
  `SourceObject` itself honors data-class `equals`/`hashCode`/`toString` so the value behaves consistently
  whether compared by the interpreter (`==`) or by host code it's handed to.
- **Extended language subset:** null-safety (`?.` / `?:` / `!!`), `throw` + `try`/`catch`/`finally` (a
  non-`Throwable` thrown value is carried so a `catch` can still match), `when` with `is`/`in`, unary `!` /
  unary `-`, `IntRange` (`a..b`, `in`/`!in`), and destructuring (`val (a, b) = …`). Most desugar to existing
  nodes (`!x` → `if (x) false else true`, `a ?: b` → a temp-local `if`); `!!`/`is`/`try` are dedicated nodes.
- **Inheritance / sealed / abstract:** a `ResolvedClass` carries its `superCall` (superclass + ctor args) and
  `supertypes`. Construction runs the superclass constructor on the *same* instance first, so inherited
  properties + initializers populate the shared field map. Member dispatch walks the instance's class then its
  source supertypes — so an override wins (virtual dispatch, even through a base-typed reference), an inherited
  method/interface-default resolves, and an abstract member binds to its concrete override (abstract bodies
  aren't lowered). A method body resolves inherited members by bare name / implicit `this` via the merged class
  context. Verified in `interp-core/SourceInheritanceTest` (inherited member, override, virtual dispatch through
  a base ref, super-constructor initializers, interface default, abstract template method, sealed `when is`,
  multi-level chain). `super.foo()` (explicit super-call) is still the honest boundary.
- **Honest boundary preserved:** anything still outside the subset (casts, secondary constructors, computed
  property getters, source operator overloads, `super.x`, non-`Int` ranges) lowers to `RNode.Unsupported` — the
  interpreter refuses the function rather than guessing. Verified in `interp-core` (`SourceClassTest`,
  `Phase2FeaturesTest`, `DataClassTest`). **Wired into the Compose preview path:**
  `KotlinSourceAnalyzer.lowerFileClasses` → `IdeServices.lowerComposePreview` (carried on
  `LoweredComposePreview.classes`) → `ComposePreviewRenderer.Render(entry, program, classes)` →
  `Interpreter(classes = …)`, so a `@Preview` that constructs/uses a project data class renders. Verified
  headlessly against the real Compose runtime in `interp-compose` (`SourceClassPreviewTest`).

### 3. Compose bridge (`interp-compose`)

Four plugin behaviours, reproduced against the real runtime:

**(a) Composer threading.** A real `@Composable` library method's JVM signature is
`original… , Composer composer, int $changed [, int $default]`. A reflective call appends:
- the **real composer** currently being threaded,
- `$changed = 0` (conservative "all inputs changed" → always correct; skipping is a later optimization),
- one `$default` bitmask `int` per 32 params **only if** the function has default values, marking which args
  are omitted.

`$changed` is **chunked ~10 value-params per int** (multiple ints for wide functions). This mangling is
**version-coupled to the Compose compiler that built the AAR** — it is a small, well-defined ABI adapter
derived from the actual resolved method, not hardcoded. The spike (below) discovers it by reflection.

**(b) Composable lambdas as arguments.** A `@Composable () -> Unit` parameter is really
`Function2<Composer,Int,Unit>`; a `@Composable ColumnScope.() -> Unit` is
`Function3<ColumnScope,Composer,Int,Unit>`. For `Column(modifier) { … }` the bridge passes a **JVM proxy**
implementing the right `FunctionN`; when the real `Column` invokes it with *its* composer, the proxy
re-enters the interpreter with that composer. This is also how **inline composables work via reflection** —
call the compiled method, hand the content as a proxy; the inlining optimization is lost (fine), correctness
is not.

**(c) `remember` / state.** `remember` is inline — don't reflect it; desugar it against the composer's slot
primitives, keyed by call-site:

```
composer.startReplaceableGroup(callSiteKey)
var v = composer.rememberedValue()
if (v === Composer.Empty || inputsChanged) { v = interpret(calc); composer.updateRememberedValue(v) }
composer.endReplaceableGroup()
```

`mutableStateOf`/`MutableState` are *normal* calls → reflect them; reads of `.value` auto-register with the
real snapshot system, so reactivity is free.

**(d) Groups + granular recomposition.** Each interpreted user-composable is wrapped in
`startRestartGroup(callSiteKey)` … `endRestartGroup()?.updateScope { newComposer, _ -> reinterpret(subtree, newComposer) }`,
so the real Recomposer recomposes **just** that subtree when its observed state changes. Loops fold the index
into the key (mirrors `startMovableGroup`). The root is `composition.setContent { interpret(root) }` on a
real `Composition` backed by a real `Recomposer` + an `Applier` that builds the node tree the renderer
consumes.

### 4. Constrained resolver scope

Because resolution is hand-rolled, the supported subset is written down and everything else is rejected with
a clear diagnostic:

- **IN:** locals/params/properties; member & extension calls; **overload resolution by argument types**;
  constructor calls; operator / iterator / `invoke` / delegation conventions; `if`/`when`/loops; lambdas +
  closures; basic generics (enough for dispatch); nullable + smart-cast on `!= null` / `is`.
- **OUT (v1, rejected):** coroutines / `suspend`; reified inline; advanced inference (builder inference,
  flexible types); sequences. UI/Compose code lives mostly inside the IN box — but **overload resolution and
  smart-casts are mandatory** and are the parts most likely to bite.

## Risk register (ranked)

1. **Sound hand-rolled overload resolution + smart-casts** — wrong dispatch is a silently wrong program.
   Highest risk, most test surface.
2. **Compose synthetic-param ABI** — version-coupled; any mistake is a `NoSuchMethodError`. Bounded but
   brittle. *The spike retires this one.*
3. **Granular recomposition via `updateScope`** — wrong scope/key wiring → stale UI or full-tree recompose.
4. **Composable-lambda proxies** (arity + receiver + composer) for inline content.

## De-risking spike (do this first)

Before any new module, a **vertical ABI spike** in `:ide-android` proves the load-bearing assumption: that
we can, by reflection (no interpreter yet), call a **real** Material3/foundation composable from inside a
real `Composition`, thread a real `Composer` into it, and have a `mutableStateOf` change drive
recomposition through that reflective call.

`dev.ide.android.spike.ComposeAbiSpikeTest` (instrumented; runs on a device/emulator) is that spike. Like the
on-device kotlinc spike (`KotlinCompilerArtSpikeTest`), it is a **discovery artifact** — the failure (logged
to logcat under `ComposeAbiSpike`) tells us exactly which rung of the ABI breaks. Run it with:

```
./gradlew :ide-android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposeAbiSpikeTest
adb logcat -s ComposeAbiSpike
```

> `connectedAndroidTest` does **not** accept Gradle's `--tests` filter — use the
> `-Pandroid.testInstrumentationRunnerArguments.class=…` runner arg. The bare `ComponentActivity` it launches
> (`SpikeComposeActivity`) lives in the app's **debug** source set, not androidTest: a test-only activity in
> the androidTest manifest belongs to the `…​.test` package and `ActivityScenario` rejects launching it
> across the instrumentation-target process boundary.

It escalates through rungs, each logged independently:

1. **Harness** — a real Compose composition renders a trivial *compiled* composable (sanity).
2. **Reflective call** — from inside a compiled `@Composable`, obtain `currentComposer` and reflectively
   invoke a real foundation composable (`Spacer`) with composer + discovered `$changed` ints. Proves the
   mangled-signature dispatch + composer threading.
3. **Recomposition** — a `mutableStateOf` read in the composition, mutated; assert the body (and the
   reflective call) re-runs. Proves reactivity flows through the bridge.

The reflective invoker (`ComposableAbi`) the spike builds — discover the transformed method, append
composer + trailing ints, supply argument values — is the prototype of `interp-compose`'s ABI adapter, so the
spike is not throwaway.

## Milestones

1. ✅ ABI spike green on device (retires risk #2) — `dev.ide.android.spike.ComposeAbiSpikeTest`.
2. ✅ `ResolvedTree` contract + a resolver skeleton for a starter subset — `dev.ide.lang.kotlin.interp`
   (`ResolvedTree.kt`, `KotlinTreeResolver.kt`), verified by `KotlinTreeResolverTest`. The contract is the
   full intended v0 node set; the resolver lowers constants, locals/params (slot-bound), member & top-level
   calls with exact callee selection (ambiguous overloads rejected, not guessed), property reads, `if`/
   `return`, the `+ - * / %` operator conventions desugared to calls, and `=` assignment — everything else
   lowers to `RNode.Unsupported` with a recorded diagnostic.
3. ✅ Plain-Kotlin interpreter (no Compose) over `ResolvedTree` — the new `:interp-core` module
   (`dev.ide.interp.Interpreter`), verified by `InterpreterTest`. Executes constants, locals/params, blocks,
   `if`, `return`, assignment, the primitive numeric operators (as intrinsics — `Int.plus` has no JVM method
   to reflect into), and calls to other **source** functions (interpreted recursively). Member/extension/
   constructor/library dispatch and property access throw `InterpreterException` (the honest boundary) —
   those arrive with the reflective library bridge in step 4.
4. ✅ Reflective dispatch + the Compose bridge.
   - **4a** — `interp-core` gained a `Dispatcher` seam and a default `ReflectiveDispatcher` (member calls via
     the receiver's runtime class; top-level/extension via the resolved `…Kt` facade; constructors via the
     type). The descriptor work that feeds it: `KotlinSymbol.declaringClassFqn` is now carried for binary
     members and top-level/extension **facades** (multi-file class parts resolve to their public facade, e.g.
     `kotlin.math.MathKt`), threaded through the `.kxt` + `kotlin.typeShape` codecs, and surfaced as
     `ResolvedCallable.Library.ownerFqn` with `descriptorPrecise = true`. Verified by `ReflectiveDispatcherTest`
     + an end-to-end `emptyList()` call in `InterpreterTest`, and `KotlinTreeResolverTest` asserts facade
     precision.
   - **4b** — `dev.ide.interp.compose.ComposeDispatcher` (in `:ide-android`) threads a real `Composer` into
     top-level calls via `ComposableAbi` (extracted to `:ide-android` main from the spike) and falls back to
     reflection. **Green on device** (`ComposeInterpreterSpikeTest`): the interpreter walks a hand-built
     `ResolvedTree` for `ui(m: Modifier) { Spacer(m) }` inside a real composition and `Spacer` composes into
     the real runtime.
5. ✅ Granular recomposition + state. `interp-core` gained a `ComposableInvoker` seam (the interpreter runs a
   `@Composable` source call through it) and `RNode.PropertyGet` reads (reflective getter — a
   `MutableState.value` read goes through the real `getValue()`, so the snapshot system records the
   dependency). `dev.ide.interp.compose.ComposeRuntime` (in `:ide-android`) implements the invoker: it opens
   a real `startRestartGroup`, runs the interpreted body, and registers `endRestartGroup().updateScope { … }`
   to re-run the body on recomposition. **Green on device** (`ComposeRecompositionSpikeTest`): an interpreted
   composable reads a real `mutableStateOf`; mutating it makes the real Recomposer recompose the interpreted
   body (1 → 2).
6. Make it real:
   - ✅ **Decode `@Composable` + `inline` onto symbols.** `KotlinSymbol.isComposable`/`isInline`, populated
     from Kotlin metadata (`inline` flag) + bytecode (the `@Composable` annotation or the appended `Composer`
     parameter, read with ASM in `KotlinMetadata`) for binaries, and from PSI annotation/modifier names for
     source. Threaded through the `.kxt` (v7) + `kotlin.typeShape` (v5) codecs, surfaced on
     `ResolvedCallable`, and `ComposeDispatcher` now threads a composer **only** into `@Composable` calls — so
     a non-composable top-level call inside a composable (`mutableStateOf(…)`) is left to plain reflection.
     CI-tested for the source + inline paths (`KotlinTreeResolverTest`); binary `@Composable` detection is
     validated in the real IDE (the device tests hand-build composable callees, since on-device there are no
     scannable Compose jars — that's the classpath-provisioning item below).
   - ✅ **Classpath provisioning (verified).** The symbol service already consumes the project classpath
     (`CompilationContext.classpath`), so the editor resolves composables from the project's Compose AAR jars
     — no new production wiring was needed. The gap was *verification*: `KotlinComposeDetectionTest` now
     proves it end-to-end in CI with a fake `androidx.compose.runtime.Composable` compiled into the test
     classpath — a provisioned jar resolves to a `Library` callee with `isComposable` and a precise `…Kt`
     facade owner. (On-device the IDE supplies those jars; execution loads the classes from the app/project
     dex.)
   - **Widen the resolver subset.** ✅ Comparison (`< > <= >=`) + equality (`== !=`) operators,
     `while`/`do-while`, and `for (x in xs)` (the interpreter reflects `iterator`/`hasNext`/`next` on the
     value) — all interpreted end-to-end (`InterpreterTest`).
   - **Lambdas.** ✅ The resolver lowers `KtLambdaExpression` → `RNode.Lambda`; the interpreter evaluates it
     to a `Closure` (shares the activation env, so captures are read/written in place). `ReflectiveDispatcher`
     wraps a lambda arg as a JVM functional-interface proxy (CI-tested: `xs.forEach { s = s + it }`), and
     `ComposeDispatcher` wraps a **`@Composable` content lambda** as a `FunctionN` proxy that threads the
     library composable's child composer back into the interpreter — **green on device**
     (`ComposeLambdaSpikeTest`: `Box { Spacer(m) }` composes the nested `Spacer` into the real runtime). This
     is the capstone that makes nested Compose (`Column { Text(…) }`-shaped) interpretable.
   - **Remaining:** string-template interpolation, `when`, value-param composable lambdas (`LazyColumn`'s
     `items { i -> }`), full type-directed overload resolution; then `$changed` skipping for perf.

## Editor integration

The editor preview flow is wired (status scope — no rendered-image surface yet):

- **Detection + lowering** (`lang-kotlin`): `KotlinComposePreviews.find(ktFile)` → the `@Preview @Composable`
  targets; `KotlinSourceAnalyzer.composePreviews(file)` + `lowerFile(file)` → the file's functions lowered to
  `ResolvedFunction`s. CI-tested (`KotlinComposePreviewTest`).
- **Backend** (`IdeBackend` → `ide-core`): `composePreviews(path, text)` and `runComposePreview(path, text,
  fn)`. The run lowers the file, checks the preview is fully interpretable, and reports status — delegating
  the actual render to an injected **`ComposePreviewRunner`** port (null for now ⇒ "interpretable; rendering
  coming soon"; the device render host is the remaining piece).
- **UI** (`ide-ui`): a toolbar **Preview** button (`EditorTopBar`) shown when the open file has `@Preview`
  composables; clicking runs the preview and surfaces the result in a dismissible `PreviewStatusBar`.
- **Template**: `JetpackComposeAppTemplate` (`platform.projectTemplate`, registered via
  `AndroidSupport.registerTemplates`) generates a Compose app with `@Preview` composables (a leaf `Text` and a
  `Column { … }`) + the Compose AAR deps.

**Device render surface** (`ide-android`): `ComposePreviewRenderer` is a `@Composable` that hosts the
interpreter + `ComposeDispatcher` + `ComposeRuntime` inside the IDE's own composition — `Render(entry,
program)` threads the ambient `currentComposer` and drives the preview through its restart group, so it
composes as real Compose UI and recomposes on state change. **Green on device** (`ComposePreviewRenderSpikeTest`
renders a `Spacer(Modifier)` preview through it). This is the component the editor preview panel embeds.

**Editor wiring (live pixels).** The toolbar Preview button now opens a `ComposePreviewDialog` that renders
the selected `@Preview` as real UI. The cross-module seam: `ide-ui` defines `ComposePreviewHost` (a
`@Composable Preview(path, fn, text, modifier)`), threaded through `CodeAssistApp` → `IdeUiState`. The bridge
+ render surface live in **`:interp-compose`** (a Compose Multiplatform KMP module — desktop + android — that
re-exports `:interp-core`), so the SAME `ComposePreviewRenderer` runs on both hosts: each lowers the open file
via `IdeServicesBackend.lowerComposePreview` (off the UI thread, serialized on the engine dispatcher) and
composes it through the renderer, which threads the ambient `currentComposer`. `:ide-android` provides
`AndroidComposePreviewHost` (Compose for Android, on device) and `:ide-desktop` provides
`DesktopComposePreviewHost` (Compose for Desktop, JVM). Because the interpreter dispatches library composables
reflectively by FQN, on desktop they resolve against Compose for Desktop — a `@Preview` built from standard
material/foundation composables renders live there too; one that reaches an Android-only API fails to lower
and shows the not-interpretable note. The engine-side `IdeServices.composePreviewRunner` remains the no-host
fallback (status only). (Compile-verified across ide-ui / interp-compose / ide-core / ide-desktop / ide-android;
the renderer itself is device-proven.)

**`@Preview` argument support.** The annotation is parsed into a `PreviewConfig` and honored across the
pipeline (`PreviewAnnotationParser`/`PreviewConstants` in `lang-kotlin`). One `@Composable` expands to one
preview **variant** per `@Preview` (stacked annotations are separate variants), per built-in/source-declared
**MultiPreview** annotation (`@PreviewLightDark`, `@PreviewFontScale`, `@PreviewScreenSizes`,
`@PreviewDynamicColors`, plus same-module/cross-file custom ones resolved through the symbol service), each
carrying a stable `variantId` + `label`/`group`. A constant-folding evaluator reads literals, hex/bin numbers,
unary minus, and named platform constants (`Configuration.UI_MODE_NIGHT_YES`, `Devices.PIXEL_4`, `a or b`).
- **Default size = wrap content**: with no `device`/`widthDp`/`heightDp`/`showSystemUi`, the preview card sizes
  to the composable itself (`PreviewSurface(wrapContent=…)`, bounded by the selected device as a max), matching
  Android Studio — not a full phone frame.
- **Honored render-side**: `widthDp`/`heightDp`/`device` (the preview surface sizes its card to the resolved
  `DeviceProfile` — named ids + `spec:` strings via `PreviewDevices`), `showBackground`/`backgroundColor`,
  `fontScale` (folded into the surface `Density`), `uiMode` night bit + `locale` (applied to the Android host's
  `Configuration`), `showSystemUi` (a mock status/nav-bar chrome), `name`/`group` (the in-pane variant selector).
  `apiLevel` is parsed + shown but cannot truly change `Build.VERSION.SDK_INT` in-process (best-effort).
- **`@PreviewParameter`**: the provider is instantiated through the interpreter
  (`Interpreter.previewParameterValues`; a source provider materialized as a `SourceObject`, a library one
  loaded reflectively by FQN) and the preview renders once per sample value (up to `limit`), stacked in the
  host (`PreviewVariants`).
- **Editor**: argument-name completion inside ANY annotation (`KotlinResolver.annotationParameters` resolves the
  annotation type's params — Kotlin constructor params, source ctor params, or a Java `@interface`'s element
  methods — offered as named-argument items **ranked first**; `KotlinCompletion.annotationArgExtras`, with a
  bundled fallback for `@Preview` when the androidx type isn't resolved), plus `@Preview` `uiMode`/`device`
  constant completion; `kt.previewNotComposable` / `kt.previewParameters` validation diagnostics; one gutter
  affordance per `@Preview` line (`variantId`-keyed). Verified across `KotlinPreviewArgsTest` /
  `KotlinPreviewEditorTest` / `KotlinAnnotationCompletionTest` (lang-kotlin) + `PreviewParameterValuesTest`
  (interp-core).

**Resolver subset (widened).** ✅ String-template interpolation (`"a${x}b"` → `RNode.StringConcat`,
stringified at runtime) and `when` (desugared to a nested `if`/`==` chain; a subject is evaluated once into a
temp local; `is`/`in` conditions still `Unsupported`). CI-tested (`InterpreterTest`: interpolation, subject
`when`, subjectless `when`). ✅ `super.foo(...)` / `super.prop` (`DispatchKind.SUPER`): lowered against the
enclosing class's `this`, dispatched to the nearest SOURCE supertype implementation (skipping the lexical
override); a `super` call into a binary superclass (`super.onCreate` → `ComponentActivity`) has no source body
and no real super-instance, so the interpreter no-ops it. The point is that an unrelated overriding member in a
preview's file (a `MainActivity.onCreate`) now lowers without a diagnostic. CI-tested
(`SuperCallAndReachabilityTest`).

**Preview gate scoping.** `IdeServices.lowerComposePreview` requires only the source classes the preview can
actually REACH to lower cleanly (`reachableSourceClasses`: the transitive closure of constructed types, source
calls, object/enum/property references, and their supertypes/members from the entry function), not every class
in the file. An unrelated `Activity` the preview never instantiates can no longer block rendering.

**Remaining:**
- **`@Composable` content lambdas passed to non-composable scope builders** (`LazyColumn { items(xs) { i -> Text(i) } }`).
  A *direct* composable value-param lambda already works (the proxy strips the trailing `Composer`/`$changed`
  and binds the leading value params). The `items` case breaks because `items` dispatches reflectively
  (non-composable) and its `@Composable` item-content lambda then gets a plain proxy that doesn't thread the
  composer. The principled fix needs `@Composable` detection on **function-type parameters** (a
  `KotlinType.isComposable` decoded from the `KmType`'s `@Composable` annotation) so a composable
  function-typed param always gets the composer-threading proxy regardless of the callee — a dispatcher/proxy
  redesign, tracked separately.
- **`$changed` skipping** for recomposition perf (today `$changed = 0` always recomputes, which is correct
  but not optimal).

### Resolver skeleton — known gaps (tracked for milestone 6)

- **Binary callee descriptor** is best-effort (`ResolvedCallable.Library.descriptorPrecise = false`): the
  symbol model doesn't carry the exact JVM owner FQN/descriptor for every binary member, which the
  interpreter's reflective bridge will need.
- **`@Composable` / `inline`** are not yet decoded onto symbols, so `isComposable`/`isInline` are `false`.
- **Overload resolution** is single-candidate or unique-arg-type-match only; richer cases are `Unsupported`.
- Out of subset entirely (today): string interpolation, `when`, loops, lambdas, smart-casts, `suspend`.
