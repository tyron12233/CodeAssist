# Interpreting the Compose runtime (milestone A) — resolving the preview version ceiling

Status: phase A proven and committed; phase B proven on desktop through material3 (Column/Row/Box/BasicText and
material3 `Text`/`Button` emit real `LayoutNode` trees through the interpreted UI stack); render (phase C) + the
preview wiring (phase D) remain.

## The problem

The Compose `@Preview` renderer runs the previewed composable **inside the IDE's own composition**
(`ComposePreviewRenderer.Render` is a `@Composable` that threads the ambient `currentComposer` into the
interpreter). That composer is the IDE's **bundled** Compose runtime (androidx ~1.8.x). When the previewed
project pins a much newer Compose (e.g. material3 `1.5.0-alpha24` → runtime `1.12.0`), the interpreted
project composables emit their compiler-generated positional-memoization protocol
(`startRestartGroup` / `$changed`→`$dirty` bits / `shouldExecute` / `skipToGroupEnd` /
`sourceInformationMarkerStart`) onto a composer whose slot/group accounting differs by ~4 versions. The slot
table misaligns, `rememberedValue()` returns the wrong slot, and composition corrupts (a stale value bleeding
across unrelated `remember`s, "Start/end imbalance"). The failure is **version-proportional**: the same path
worked for material3 `1.4.0` (Compose ~1.6, close to the bridged 1.8); it breaks the further the project's
Compose version is from the bundled one.

The "material3 flip" (interpret `androidx.compose.material3.*` from the project jars, bridge the rest) does not
fix this because it still bridges the **runtime** — the composer is still the bundled version. See
`docs/compose-interpreter.md` for the flip and the reflective-ABI bridge it is built on.

## The fix: interpret the runtime at the project's own version

Interpret the Compose runtime (`androidx.compose.runtime` — Composer, SlotTable, Recomposer, snapshot system)
from the **project's** jars, so the interpreted project composables drive a **matching-version** composer.
Bridge only the true platform floor (the Kotlin/coroutine stdlib and, ultimately, `android.graphics` drawing).

### Phase A — the composer interprets correctly (DONE, committed)

Desktop spikes (`interp-compose` desktopTest, `InterpretedComposerSpike` + `ComposerSpikeFixture`; seconds per
iteration, no device) drive a real composition on an **interpreted** composer — only `androidx.compose.runtime`
and the spike package are interpreted, the kotlinx.coroutines / java.util.concurrent floor is bridged — and
check each result against running the same code for real:

1. `setContent` + `remember` through a full composeInitial (the remember calc runs exactly once).
2. Several sequential `remember` slots + a `key(i) { }` loop of nested remembers (memoize in correct order,
   `start|a|b|k0|k1|k2|end`).
3. State-driven **recomposition** through the sanctioned Recomposer frame loop
   (`Recomposer(context+dispatcher+clock)` + `runRecomposeAndApplyChanges` + `BroadcastFrameClock`): the
   state-reading scope's body runs exactly twice (initial + one recomposition).
4. **Nested composables** — an interpreted `@Composable` calling nested `@Composable`s, each a
   compiler-generated restart group with its own `remember` (correct order, no repeats).

So the bytecode VM interprets the whole composition machinery — Composer, SlotTable, the Recomposer's suspend
loop, snapshot-driven invalidation, frame-driven re-apply, and composable-to-composable restart-group
threading. The runtime-interpretation path is viable and the ceiling is confirmed to be version skew, not a VM
limitation.

Also committed as prerequisites (needed regardless of rendering): three VM interpret/bridge-boundary fixes —
`ReifiedInlineExecutor` interprets the host's project classes (not only Kotlin facades); `ReflectiveBridge`
passes a `VmLambda` through opaquely when its target interface is absent on the host; `AsmPeerFactory` realizes
an interface peer that inherits a default method as a generated subclass on ART (which lacks JDK-16
`InvocationHandler.invokeDefault`).

## The architecture: thread the source interpreter into the interpreted runtime

The too-new path keeps the existing preview shape — the user's `@Preview` stays **source**-interpreted by
interp-core, so live-edit-without-recompile is preserved — and changes only *where the composer comes from*:
instead of the bridged **host** composer, it threads an **interpreted** composer (a `VmObject`) produced by the
**project's own** runtime running on the bytecode VM. The two interpreters are threaded — the source interpreter
drives the user body, the bytecode VM runs the runtime/ui/foundation/material3 and the library composables the
body calls — and both share the one interpreted composer as a VM value.

This is viable because the composer already flows as an opaque `Any` across the existing bridge:

- **Library composables the user body calls already thread the composer as `Any`.** When the VM holds a library
  composable's bytes, `ComposeDispatcher.invokeComposable` routes it through
  `VmLibraryExecutor.callComposable(owner, method, args, composer, …)`, which passes the composer straight through
  — so it carries a `VmObject` composer unchanged. (The reflective `ComposableAbi.call` path, by contrast, passes
  the composer to a *real host* composable and *cannot* accept a `VmObject`. That is exactly why the
  interpreted-runtime path routes every library composable through `callComposable`, never the reflective path —
  the earlier "ComposableAbi can't take a VmObject" finding stands, and it's what forces the `callComposable`
  route, not a reason to compile the user code.)

- **The group/slot protocol the interpreter emits is the one piece that must gain a VM backend.** Both
  `ComposeDispatcher.invokeComposable` (the caller-side group per library call) and `ComposeRuntime.invokeComposable`
  (the user body's restart group + the `$changed` skip fast path) drive the composer through `ComposableAbi`, whose
  ~12 composer ops (`startGroup`/`endGroup`/`startRestartGroup`/`endRestartGroup`/`argsChanged`/`isSkipping`/
  `skipToGroupEnd`/`updateScope`/`currentMarker`/`endToMarker`) today reflect on the composer's host class
  (`composer.javaClass.methods.first{…}.invoke(…)`). A `VmObject` composer's `javaClass` is the VM wrapper, not
  `ComposerImpl`, so **this driver is the piece that must gain a VM-dispatched backend** — invoke each op through the
  VM (`invokeInstance(composer, "startRestartGroup", …)`) when the composer is VM-owned. `ComposeDispatcher`'s
  composer-detection on lambda args (`COMPOSER.isInstance(...)`) must likewise recognize a VM-owned composer.

So the integration is: (1) stand up an interpreted `Composition`/`Recomposer`/`Applier` on the project runtime
(phases A/B prove this composes correctly, composer as a `VmObject`); (2) give `ComposableAbi`/`ComposeRuntime` a
VM backend for the composer ops (and make composer-detection VM-aware); (3) at the user-content point, hand that
`VmObject` composer to `ComposeDispatcher` and run the source-interpreted `@Preview` exactly as today. The
bridged-composer tree-walker stays the fast path for close-version projects; the host picks by version distance.

What the phase-A/B spikes establish for this plan: the project-version runtime/ui/foundation/material3 genuinely
interpret and compose real node trees with the composer as a `VmObject` — the substrate both the user body and the
library composables thread. What they do **not** yet exercise is the two-interpreter threading itself — a
source-interpreted body driving that `VmObject` composer through a VM-backed `ComposableAbi` — which is the next
spike.

## Remaining phases

### Phase B — the full UI stack composes to a node tree (interpreted)

**Proven on desktop** (`InterpretedUiStackSpike` + `UiStackSpikeFixture`): the VM interprets
`androidx.compose.{runtime,ui,foundation}` together and composes real `foundation` composables, emitting a real
`LayoutNode` tree that matches running the same code for real. Two spikes: `Column { Box(); Box() }` →
`(((),()))`; and `Column { BasicText("a"); Row { BasicText("b"); Box() } }` → `(((),((),())))`, which adds a
second layout (`Row`) and `BasicText` — foundation's text primitive, whose compose-time path reads
`LocalFontFamilyResolver` and builds a text modifier element — so a non-trivial leaf, layout nesting, and the
real `FontFamily.Resolver` (`createFontFamilyResolver()`) all interpret. The composables emit via
`ReusableComposeNode<ComposeUiNode, Applier<Any>>`, so the harness supplies an `AbstractApplier<Any>` and the
emitted nodes arrive as `Any` — the fixture never names the internal `LayoutNode` type. It records each node's
parent (the applier's `current`) rather than calling the internal `insertAt`, so a shallow initial composition
reads back structurally without the Owner-bound node linkage.

Two boundary conditions surfaced and are handled in the harness:

- **The graphics floor is needed at node CONSTRUCTION, not just draw.** `LayoutNode.<init>` → `NodeChain.<init>`
  → `InnerNodeCoordinator.<clinit>` creates a static `Paint()` (`SkiaBackedPaint`), which loads the graphics
  native. So the ui NODE layer is *not* platform-agnostic (correcting the assumption that "much of ui" iterates
  on desktop unaided) — real `LayoutNode`s need the graphics floor on both platforms (Skiko on desktop,
  `android.graphics` on device). Desktop iteration now bundles the Skiko native runtime in `interp-compose`
  `desktopTest` (`compose.desktop.currentOs`); only the native lib loads, no display is opened.
- **Owner-supplied CompositionLocals must be provided.** `LayoutNode.setCompositionLocalMap` eagerly reads
  `LocalDensity` / `LocalLayoutDirection` / `LocalViewConfiguration` when a node's resolved locals apply, and
  `BasicText` reads `LocalFontFamilyResolver`; a real Owner provides these, so the headless harness wraps the
  content in a `CompositionLocalProvider` supplying minimal values (`Density(1f)`, `LayoutDirection.Ltr`, a
  4-member `ViewConfiguration`, `createFontFamilyResolver()`).

material3 works, including the flagship. Policy widened to `androidx.compose.{material3,material,animation}`:
`Column { Text("hello"); Text("world") }` composes interpreted (`(((),()))` — material3 `Text` resolves
`LocalContentColor`/`LocalTextStyle`, both defaulted so no `MaterialTheme` wrapper is needed, and delegates to
foundation `BasicText`); and `Button(onClick = {}) { Text("Click") }` composes interpreted to `(((())))` — the
`Surface`→`Row`→`Text` node chain, exercising the material3 component machinery (theme locals, ripple
`Indication`, shape/color/modifier chains) all interpreted. Remaining in phase B: `TextField` (a
`SubcomposeLayout` — the device flip spike's hard case), and the value-type tail the device material3-flip spike
mapped (Style, Modifier, Alignment/Dp/Color/Arrangement) for even-newer material3. Three boundary-crossing VM
fixes are already committed; the pervasive value types are the known tail. **This all composes; measure/layout/
draw is phase C.**

### Phase C — rendering: interpreted node tree → pixels

The interpreted `ui.node` LayoutNodes measure/layout/draw; the draw calls must reach a real
`android.graphics.Canvas` (the bridged floor) to produce a bitmap. This is the render boundary: interpret up to
the draw commands, bridge the actual pixel drawing. Produces the bitmap the preview panel displays (the preview
already streams a bitmap out-of-process; see `compose-preview-isolation`).

### Phase B′ — thread the source interpreter to the interpreted composer

The two-interpreter threading (see "The architecture" above): a **source-interpreted** `@Composable` body drives
an **interpreted** (`VmObject`) composer, calling bytecode-interpreted library composables that share it. The work
is a VM-dispatched backend for `ComposableAbi`/`ComposeRuntime`'s composer ops (today host reflection on
`composer.javaClass`) plus VM-aware composer-detection in `ComposeDispatcher`.

Because the current recomposition loop rides on *real* host `MutableState` + the real snapshot system driving the
real Recomposer, the threading splits: **B′.1 initial composition** (the composer ops for one `composeInitial` —
`remember`-once + node emission) first; **B′.2 recomposition** (an interpreted snapshot/Recomposer, and routing
interp-core's state reads to interpreted `MutableState`) later.

- **Bootstrapping proven** (`InterpretedComposerThreadingSpike`): host code obtains the interpreted composer out
  of an interpreted composition — an interpreted `setContent` hands its `currentComposer` to a host callback and
  the received value is a `VmObject` (the project-runtime composer), not a host `Composer`. This is the seam the
  future VM-backed `ComposableAbi`/`ComposeRuntime` receive the composer through.
- Next: drive that `VmObject` composer's group ops from host through the VM (`invokeInstance(composer,
  "startReplaceableGroup", …)` + `callComposable` for a library composable), then wire interp-core's tree-walker
  so a trivial user `@Composable` (a `remember` + a `Text`) composes through it, matching real.

This preserves live-edit — the reason for keeping the source interpreter rather than compiling the user code.

### Phase C — rendering: interpreted node tree → pixels

The interpreted `ui.node` LayoutNodes measure/layout/draw; the draw calls must reach a real
`android.graphics.Canvas` (the bridged floor) to produce a bitmap. This is the render boundary: interpret up to
the draw commands, bridge the actual pixel drawing. Produces the bitmap the preview panel displays (the preview
already streams a bitmap out-of-process; see `compose-preview-isolation`).

### Phase D — wire into the preview path

A host-callable `VmComposeHost` (interp-compose) sets up an interpreted `Composition`/`Recomposer`/`Applier` from
the project runtime, threads its `VmObject` composer into `ComposeDispatcher`, and runs the source-interpreted
user `@Preview` (phase B′) — replacing the bridged-composer path when the project's Compose version is far enough
from the bundled one that the flip can't align. Live-edit is preserved (the user body is still tree-walked); only
the runtime/library side is recompile-free interpreted bytecode. The host picks this path vs. the bridged
tree-walker by version distance.

## Iteration

Phases A–B iterate on **desktop** (`./gradlew :interp-compose:desktopTest`, seconds) because
`androidx.compose.runtime` and the composition-driving parts of ui/foundation are platform-agnostic. The one
platform tie in the node layer — a real `LayoutNode` needs the graphics native at construction (see phase B) —
is bridged on desktop by bundling the Skiko native runtime, so node-tree construction iterates on desktop too;
actual measure/layout/**draw** (phase C) is where `android.graphics` makes the device authoritative. The device
material3-flip spike (`VmTextFieldArtSpike`, ~2.5 min/cycle) remains the end-to-end check for the
too-new-project case.

See also: `material3-flip-version-ceiling` (the root-cause analysis and the boundary-crossing map),
`docs/compose-interpreter.md` (the reflective ABI bridge and the flip), `docs/compose-preview-isolation.md`
(the out-of-process bitmap-streaming preview).
