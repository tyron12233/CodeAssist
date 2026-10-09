# How the block (projectional) editor works

CodeAssist can show any Java or Kotlin file as a Scratch-style tree of interlocking blocks, edit it there, and
write the changes straight back into the source — leaving every untouched line, comment, and bit of
formatting **byte-for-byte intact**. The block view is a *live projection of the shared DOM*, not a
parallel model.

This document explains how the projection and the edit round-trip work. For the SPI these pieces
implement, see [extension-points.md](extension-points.md).

## One model, projected

There is a single shared document and AST. The block tree is *derived* from it on demand and discarded;
it never becomes a second source of truth. That is what keeps "edit as code" and "edit as blocks"
perfectly in sync — both views are the same `ParsedFile`.

## Gap-carving projection

The projection engine turns a `ParsedFile` into a block tree by **gap carving**:

1. Walk a node's children in source order.
2. The literal source *between* child ranges is kept as read-only chrome.
3. Each child is projected into a typed slot.

Because the chrome is the real text between children, the default serialization round-trips
byte-for-byte — the engine never synthesizes separator text or guesses formatting.

## What decomposes, and what stays text

A per-language **block mapping** decides which node kinds explode into structured blocks and which
collapse to an editable opaque text slot. The Java mapping decomposes statements and the key expressions
(control flow, calls, member/name references, infix operators, literals); containers become a single
foldable list slot; everything else stays editable text. Two notable cases:

- **Call collapse.** A pure-name receiver (e.g. `System.out`) becomes an editable `qualifier` field in
  the call block's header.
- **Fluent chains.** A chain such as `sb.append(x).append(y)` flattens into one call block with
  `name`/`name1`/… fields and one argument slot per argument, with the real source between them as chrome.

## Typed value sockets

Each slot carries a `ValueKind` (boolean / number / string / type / object / unknown) describing what the
position expects, and each block carries the kind it produces. These drive Scratch-style socket shapes
(hexagon = boolean, pill = number, and so on) applied to both empty sockets and the blocks filling them.
Kinds are inferred syntactically (literals, operators, casts, typed initializers, conditions), and the
engine consults a `ValueKindOracle` hook first so a semantic resolver can refine them later.

## Editing: BlockEdit → DocumentEdit

A block edit (set a field, replace with text, delete, insert a template, move, wrap) is compiled to a
**minimal `DocumentEdit`** — the smallest change to the source that realizes it. Untouched code is never
rewritten. Projection ids are deterministic per text: the edit pipeline re-projects the same buffer to
resolve references and holds no state, so the host passes the exact text the displayed tree was
projected from.

## Languages

Java and Kotlin each have a block mapping and their own reading of the DOM (labels, slot categories, the
syntactic value-kind guesses). The two languages share neutral node kinds such as `block` and `method_call`,
so the engine picks the mapping by the file's language rather than by node kind alone. The Kotlin mapping
unwraps the DOM's condition and control-body containers so `if`/`while`/`for` carry their bodies directly,
projects `when` as one clause per entry, and turns a trailing lambda (`Column { ... }`, `items.forEach { ... }`)
into a body slot of its call, which makes the call a C-block.

## Runs of statements

Dragging a block carries the statements below it, as in Scratch. `MoveRange`, `DeleteRange` and `WrapRange`
act on such a run; inserted and moved code is re-indented to its new depth, and an empty `{}` keeps an empty
list slot so a block can be dropped into it.

## The UI

The Blocks view opens on an outline of the file: its functions grouped by class or object (top-level ones
first), and its variables. Tapping a function opens it on its own page as a stack of blocks under its hat.
Variables are listed and edited through forms rather than drawn as blocks, and appear in the palette as a
value block and a `set` block each. The outline also adds functions, variables and, for a class, events
(overrides of inherited methods, offered the way code completion offers them).

A page is one canvas laid out by a single measure-and-place pass and drawn on a Canvas, with pan and zoom.
Long-press a block (or drag it with a mouse) to lift it; the nearest connection within reach is picked as it
moves, the list opens up where it would land and a silhouette marks the spot. Statement runs snap between
statements, around a whole list (a C-block with an empty body wraps it) or above a scratch stack; values snap
into sockets of a compatible kind. A `return`, `throw`, `break` or `continue` only goes last in a list, and
nothing goes after one.

A block dropped on empty canvas becomes a scratch stack: it is kept beside the file in the project's
`.platform/blocks/` directory, not in the source, so it is never compiled. Scratch stacks are edited as blocks
like any other code and can be dragged back in.

The palette's category rail (Control, Logic, Math, Text, Variables, Calls, and Compose for a Compose file)
holds real projected blocks, and its search adds project symbols and classpath members. Tapping a token or a
socket types code with the code editor's completion, re-ranked for the socket's kind; the forms' type,
annotation and value fields complete the same way. Every gesture compiles to a block edit, which flows back
through the surgical projection-to-document-edit pipeline.

## Calls and Compose

A call block shows the callee's parameters as the resolver reports them (the same signature help the code
editor's parameter popup uses): a missing required parameter is a labelled hole, filled positionally when it
is next in line and by name otherwise; removing an argument removes it with its comma. Optional parameters
and overloads are offered from a property sheet on the selected block. When nothing is resolved, an empty
`()` and the end of an argument list carry a small `+`.

Kotlin calls with several or named arguments lay out as property rows. Well-known Compose values read in short
form with a glyph or swatch (`Alignment.CenterHorizontally`, `MaterialTheme.colorScheme.background`,
`MaterialTheme.typography.headlineMedium`), `100.dp` reads as a number with its unit, and a `Modifier` chain is
a card of links (one link inline) that opens an editor with a schematic of the box it describes. Calls to
composables are colored apart from ordinary calls, a builder lambda used as a value
(`buildAnnotatedString { … }`) is a C-block in its socket, and a `@Composable` function's page can show its
Compose preview beside the blocks.

A lambda run with a receiver (`Column { }`, `buildAnnotatedString { }`, `LazyColumn { }`) learns that receiver
from the callee's last parameter type; a call written with only a trailing lambda is asked about as if it had
an empty `()`. What the scope adds is completion at the end of the lambda body less completion at the top of
the enclosing function, offered from a `+` handle at the end of the body and from a Scope palette tab while a
block in that body is selected; picked functions carry the import completion would add. A modifier link with a
lambda (`.clickable { }`) is a C-shaped row of the modifier card with a mouth that takes blocks, and any such
lambda can also be opened as its own page; back returns to the function.
