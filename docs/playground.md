# The Playground (browser)

A Kotlin playground that runs entirely in the browser, with the IDE's own editor and language engine. There is no
server: the page is static files.

```
page (Compose Multiplatform for web, the desktop IDE shell)          Web Worker (Kotlin/Wasm)
  CodeAssistApp + PlaygroundIdeBackend  --- postMessage --->   EditorSession  : completion, diagnostics, hints, ...
  EngineClient                          <--- postMessage ----  PlaygroundEngine: parse -> lower -> :jvm-vm -> :interp-core
```

* **Editor and shell:** `experimental/playground-ui`. The same `CodeAssistApp` as the desktop IDE, over a backend
  (`PlaygroundIdeBackend`) whose project is an in-memory set of files.
* **Engine:** `experimental/playground-spike`. The Kotlin language stack (`:kotlin-syntax`, `:lang-kotlin`, the index)
  compiled to Kotlin/Wasm, running in a worker so a long run or analysis never blocks the UI. A program is parsed and
  lowered to a tree, and `:jvm-vm` (a closed-world bytecode VM with the JDK floor in Kotlin) runs `:interp-core` over it.
  `:playground-spike` also has a small CodeMirror page (`src/web`) used to develop the engine on its own.
* **Libraries:** the jars the VM interprets and the editor completes against (`jars/`), fetched by the worker at start.

## Build

```
./gradlew :playground-ui:assemblePlayground            # build/playground-web/       (development mode)
./gradlew :playground-ui:assemblePlaygroundProduction  # build/playground-web-prod/  (wasm-opt; about 9 minutes)
```

The production build needs Binaryen's `wasm-opt` on the machine (`brew install binaryen`, `apt install binaryen`, or
`BINARYEN_HOME` pointing at a directory with `bin/wasm-opt`). Gradle's own download of it is a project repository, which
the settings' repository policy (`FAIL_ON_PROJECT_REPOS`) refuses; Node comes from the machine for the same reason.

Serve the output directory with any static server (`python3 -m http.server`, nginx, a bucket behind a CDN). Needs no
special headers: no `SharedArrayBuffer`, no cross-origin isolation.

## Caching

`index.html` and `playground-ui.js` are the only files with fixed names. The worker and the jars sit in directories named
for a hash of what they hold (`worker-<h>/`, `jars-<h>/`), the page is built to ask for exactly those, and webpack hashes
the `.wasm` files itself. So:

* `index.html`, `playground-ui.js`: `Cache-Control: no-cache` (revalidate each visit).
* `worker-*/`, `jars-*/`, `*.wasm`: `Cache-Control: public, max-age=31536000, immutable`.

A browser then never runs a new page against an old worker or old jars. That mix fails in odd ways (a run that finishes
with no output, a worker that dies on a method that is not there), and it is what a server without cache headers produces.

Measured: the app's wasm is 50 MB in development mode and 11 MB in production, `playground-ui.js` 3.5 MB and 0.5 MB.
Most of the rest of the production directory (about 47 MB) is the jars the engine loads.

## Soft keyboard (touch devices)

The editor draws its text on a canvas, so there is no text field for a phone's keyboard to type into. `index.html` has a
hidden `<textarea>` for that (`window.playgroundIme`) and `EditorTextInput.wasmJs.kt` feeds what is typed in it to the
editor session: text, Enter, Backspace. iOS Safari raises the keyboard only for a `focus()` made synchronously inside a
touch handler, and the editor learns of a tap through coroutines, so every tap focuses the field at once and the editor
then claims it (`EditorImeHandle.show`); a tap it does not claim (a button) drops the focus and the keyboard. Predictive
text and CJK reach the editor when a word is finished, not as it is composed.

## Loading screen

`index.html` shows a loading screen at once, covering the app until the engine is ready: the app (a download of about
48 MB in development mode), the worker, the libraries (n of m), then indexing them. It reports a failure instead of
sitting at a blank page. The app and the worker report stages through `showLoading` (`EngineClient.kt`).

## Limits

* The interpreter is a tree-walker inside a VM: about 850 VM steps per loop iteration. Fine for demos and exercises,
  not for heavy computation. A run has a time budget (`budgetMillis`) and a call depth limit of 80, or 30 on WebKit (Safari and every iOS
  browser: a worker there has a stack about a quarter of Chrome's, and overflowing it is an uncatchable error: the page
  replaces the worker and reports a lost run). The VM runs a call to another bytecode method in the same loop rather than
  recursing on the host stack, which is what makes even 30 fit.
* Stop terminates the worker and starts another; the libraries come from the HTTP cache.
* Only the standard library and kotlinx.coroutines are available to a program.
* Rename works within the current file; shortcut glyphs are missing from the shell's font.

## Tests

`:playground-spike:wasmJsNodeTest` runs the engine's tests under Node (completion, diagnostics, multi-file projects,
whole programs); `:lang-kotlin:jvmTest` and friends cover the language stack on the JVM.
