// The page half of the Playground: a textarea, a button and a worker. All the Kotlin (parser, symbols, lowering,
// the VM that runs the program) lives in the worker, so a long run never freezes this page.

const SAMPLE = `package demo

data class Point(val x: Int, val y: Int) {
    fun dist2() = x * x + y * y
}

fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

fun main() {
    val points = listOf(Point(1, 2), Point(3, 4), Point(0, 5))
    println("points=$points")
    println("farthest=\${points.maxByOrNull { it.dist2() }}")
    println("fib(15)=\${fib(15)}")
    println("hello from Kotlin, running in your browser")
}
`;

import { createEditor } from './editor.js';

const $ = (id) => document.getElementById(id);
const out = $('out'), run = $('run'), status = $('status'), timing = $('timing');
const editorTiming = document.createElement('span');
editorTiming.className = 'hint';
$('status').after(editorTiming);

let worker = null;
let nextId = 1;
let loaded = 0;
let running = false;
const calls = new Map();

/** Ask the engine something and wait for the answer; null if the worker is not ready or goes away. */
function call(type, fields) {
  if (!worker || run.disabled && document.body.dataset.state === 'loading') return Promise.resolve(null);
  const id = nextId++;
  return new Promise((resolve) => { calls.set(id, resolve); worker.postMessage({ type, id, ...fields }); });
}

// The engine lives in a worker so a long run never freezes the page, and so a crash cannot take the page with
// it: a wasm stack overflow (a very deep recursion) is an uncatchable RangeError that leaves the module's state
// unusable, so the answer to any worker failure is to throw the worker away and start another. The jars are in
// the browser's HTTP cache by then, so a restart is quick.
function start() {
  loaded = 0;
  run.disabled = true;
  document.body.dataset.state = 'loading';
  status.textContent = 'starting…';
  worker = new Worker('./worker/codeassist-playground-spike.mjs', { type: 'module' });
  const mine = worker;
  worker.onerror = (e) => { if (mine === worker) crashed(e.message || 'the engine stopped unexpectedly'); };
  worker.onmessage = ({ data }) => { if (mine === worker) handle(data); };
}

function crashed(message) {
  const wasRunning = running;
  running = false;
  for (const resolve of calls.values()) resolve(null);
  calls.clear();
  worker.terminate();
  fail(wasRunning
    ? 'The program crashed the engine (' + message + '). Deep recursion is the usual cause; the engine is restarting.'
    : message);
  document.body.dataset.state = 'fatal';
  setTimeout(start, 0);
}

function handle(data) {
  switch (data.type) {
    case 'boot': worker.postMessage({ type: 'init', base: new URL('./jars/', location.href).href }); break;
    case 'status': status.textContent = `loading… (${++loaded}) ${data.a}`; break;
    case 'ready':
      status.textContent = `ready (loaded in ${(data.a / 1000).toFixed(1)} s)`;
      run.disabled = false;
      document.body.dataset.state = 'ready';
      if (autorun) { autorun = false; doRun(); }
      break;
    case 'result': {
      running = false;
      const [lower, exec] = data.d.split('/');
      out.className = data.c ? 'error' : '';
      out.textContent = data.c ? (data.a + (data.a ? '\n' : '') + data.c) : data.a;
      timing.textContent = `analysis ${lower} ms · run ${exec} ms`;
      status.textContent = 'ready';
      run.disabled = false;
      document.body.dataset.state = data.c ? 'error' : 'done';
      break;
    }
    case 'completions': case 'diagnostics': {
      const resolve = calls.get(data.id);
      if (resolve) { calls.delete(data.id); resolve(JSON.parse(data.a)); }
      break;
    }
    case 'fatal': crashed(data.a); break;
  }
}

let autorun = new URLSearchParams(location.search).has('autorun');

function fail(message) {
  out.className = 'error';
  out.textContent = message;
  status.textContent = 'failed';
  document.body.dataset.state = 'fatal';
}

function doRun() {
  running = true;
  run.disabled = true;
  document.body.dataset.state = 'running';
  status.textContent = 'running…';
  worker.postMessage({ type: 'run', id: nextId++, source: view.state.doc.toString() });
}

run.onclick = doRun;


const view = createEditor({
  parent: $('editor'),
  doc: SAMPLE,
  api: {
    complete: (text, offset) => call('complete', { source: text, offset }).then((r) => r ?? { from: offset, to: offset, items: [] }),
    diagnose: (text) => call('diagnose', { source: text }).then((r) => r ?? []),
  },
  onRun: () => { if (!run.disabled) doRun(); },
  onTiming: (t) => { editorTiming.textContent = t; },
});
window.__view = view; // for tests and for poking at from the console

start();
