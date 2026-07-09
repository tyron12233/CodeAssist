# Notes

An interactive console note-taking app in Kotlin: add, list, search, complete, and remove notes.

## Structure

The sample splits **model** from **view** — a good habit that keeps logic testable:

- `Notebook.kt` — the model. A `Note` data class plus a `Notebook` that stores notes in memory and
  exposes `add`, `all`, `search`, `complete`, and `remove`. There is no printing here.
- `Main.kt` — the view: an interactive command loop that reads a line, runs the matching command on the
  notebook, and prints the result.

## Run it

Press **Run**, then type commands at the `>` prompt:

```
Notes — commands: add <text>, list, done <id>, rm <id>, find <text>, quit
> add Buy milk
Added #1: Buy milk
> add Learn Kotlin
Added #2: Learn Kotlin
> done 2
Completed #2
> list
  [ ] 1. Buy milk
  [x] 2. Learn Kotlin
> quit
Bye!
```

## Extend it

- **Persist** notes: write `Notebook.all()` to a file on change and read it back on start — only the model
  changes, the command loop stays the same.
- Add an `edit <id> <text>` command.
- Show unfinished notes first with `sortedBy { it.done }`.
