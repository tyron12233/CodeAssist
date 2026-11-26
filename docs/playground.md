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
./gradlew :playground-ui:assemblePlaygroundProduction  # build/playground-web-prod/  (wasm-opt)
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

## Loading screen

`index.html` shows a loading screen at once, covering the app until the engine is ready: the app (a download of about
48 MB in development mode), the worker, the libraries (n of m), then indexing them. It reports a failure instead of
sitting at a blank page. The app and the worker report stages through `showLoading` (`EngineClient.kt`).

## Limits

* The interpreter is a tree-walker inside a VM: about 850 VM steps per loop iteration. Fine for demos and exercises,
  not for heavy computation. A run has a time budget (`budgetMillis`) and a call depth limit of 80 (a wasm worker's
  stack is small, and overflowing it is an uncatchable error: the page replaces the worker and reports a lost run).
* Stop terminates the worker and starts another; the libraries come from the HTTP cache.
* Only the standard library and kotlinx.coroutines are available to a program.
* Rename works within the current file; shortcut glyphs are missing from the shell's font.

## Tests

`:playground-spike:wasmJsNodeTest` runs the engine's tests under Node (completion, diagnostics, multi-file projects,
whole programs); `:lang-kotlin:jvmTest` and friends cover the language stack on the JVM.
