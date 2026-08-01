# Interpreting the Compose runtime (milestone A) — resolving the preview version ceiling

Status: phase A proven and committed; phases B–D are the remaining integration.

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

## The architectural consequence: one interpreted world

`ComposableAbi.call` invokes a composable by **reflection**, passing the composer as an argument, and the
composer is a real host object. An interpreted composer is a `VmObject`, which the reflective host-composable
path cannot accept. Therefore the interpreted-runtime preview cannot mix a bridged reflective path with an
interpreted composer: **the whole stack that touches the composer — runtime, ui, foundation, material3, and the
previewed user code — must be interpreted together on one VM**, with the composer flowing as a VM value and all
composer calls staying inside the VM. This is exactly the model the phase-A spikes validate (a single VM
interpreting the runtime + the composable, composer never crossing the bridge).

Implication for user code: for the too-new-project path, interpret the user's **compiled** `@Preview` bytecode
(available from the build) rather than tree-walking its source through interp-core. The tree-walker path
(live-edit without recompile) stays the fast path for close-version projects on the bridged composer; the
interpreted-runtime path recompiles on edit. Both remain; the host picks by version distance.

Two consequences worth stating plainly, since they change what the integration is NOT:

- **`ComposableAbi` / `ComposeDispatcher` are BYPASSED on the interpreted-runtime path, not refactored.** They are
  the reflective bridge that threads a *host* composer into *tree-walker* composables (the close-version path). The
  phase-A/B spikes never touch them — a compiled composable interpreted on one VM calls other composables as ordinary
  bytecode method invocations (`owner.method(composer, $changed)`), and the composer flows as a VM value. So there is
  no "make `ComposableAbi` accept a `VmObject` composer" work item; that reflective path simply stays for the
  bridged-composer/tree-walker case.
- **The driver invokes the user `@Preview` via the VM, NOT host reflection.** In the real too-new case the user's
  classes live only in the project jars — they are not loadable from the IDE runtime — so `Class.forName(userClass)`
  cannot find them and host `java.lang.reflect` is a dead end (verified by a spike). The VM-native mechanism already
  exists: `VmLibraryExecutor.callComposable(ownerFqn, method, args, composer, …)` resolves + invokes an interpreted
  composable by name from the VM's own byte source and threads the composer as an argument. The driver uses that.

## Remaining phases

### Phase B — the full UI stack composes to a node tree (interpreted)

Extend the phase-A harness to interpret `androidx.compose.ui` + `foundation` + `material3` and compose a real
UI composable (`Text`, `Column`) into a collecting `Applier`, verifying the node tree. Open harness detail: a
`ComposeNode` + `AbstractApplier` fixture returned an empty tree even for real Compose across several attempts —
resolve with a known-good node/applier pattern (model on androidx.compose.runtime's own test doubles) before
asserting the interpreted tree. The interpreted UI stack will re-encounter the boundary crossings the device
material3-flip spike mapped (Style, LayoutNode, Modifier, invokeDefault) — three of those already have
committed VM fixes; the rest (pervasive value types — Alignment/Dp/Color/Arrangement) are the known tail.

### Phase C — rendering: interpreted node tree → pixels

The interpreted `ui.node` LayoutNodes measure/layout/draw; the draw calls must reach a real
`android.graphics.Canvas` (the bridged floor) to produce a bitmap. This is the render boundary: interpret up to
the draw commands, bridge the actual pixel drawing. Produces the bitmap the preview panel displays (the preview
already streams a bitmap out-of-process; see `compose-preview-isolation`).

### Phase D — wire into the preview path

A host-callable `VmComposeHost` (interp-compose) sets up an interpreted `Composition`/`Recomposer`/`Applier`
from the project runtime and invokes the user's `@Preview`, replacing the bridged-composer path when the
project's Compose version is far enough from the bundled one that the flip can't align. Recompile-on-edit
(the interpreted-runtime path does not reuse the tree-walker's live-edit). The composition-driving harness ships
as VM-interpreted code (a package the VM's policy interprets, its bytecode read from the classpath), so the host
orchestrates it by invoking one entry point — the productized form of the phase-A/B fixtures.

## Iteration

Phases A–B iterate on **desktop** (`./gradlew :interp-compose:desktopTest`, seconds) because
`androidx.compose.runtime` and much of ui are platform-agnostic. Phase C onward needs the device (the render
boundary is `android.graphics`). The device material3-flip spike (`VmTextFieldArtSpike`, ~2.5 min/cycle) remains
the end-to-end check for the too-new-project case.

See also: `material3-flip-version-ceiling` (the root-cause analysis and the boundary-crossing map),
`docs/compose-interpreter.md` (the reflective ABI bridge and the flip), `docs/compose-preview-isolation.md`
(the out-of-process bitmap-streaming preview).
