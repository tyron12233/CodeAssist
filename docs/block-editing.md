# How the block (projectional) editor works

CodeAssist can show any Java file as a Scratch-style tree of interlocking blocks, edit it there, and
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

## The UI

The on-screen editor is a Blockly/Sketchware-style canvas: solid, category-colored puzzle blocks with
notch/bump connectors, C-shaped control blocks wrapping a colored body rail, the typed value sockets
above, segmented rendering of fluent chains, inline completion while editing a socket or field (reusing
the code editor's completion engine, re-ranked for the socket type), index-backed palette search for
draggable templates, and drag-and-drop (long-press to move or delete, drag a palette template to
insert). Every gesture maps to a `BlockEdit`, so it all flows back through the same surgical
projection → document-edit pipeline.
