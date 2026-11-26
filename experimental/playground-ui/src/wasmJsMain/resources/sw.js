// The Playground's service worker: faster repeat visits, nothing more (it does not make the page work offline).
//
// What is cached is content-hashed, so it never goes stale and is served from disk without a request:
//   worker-<hash>/, jars-<hash>/   the engine and the libraries it loads (the hash is in index.html)
//   <hash>.wasm                    the app (webpack names the wasm by its content)
// composeResources/ (fonts, strings) keep their names between builds: served from the cache, refreshed behind it.
// index.html and playground-ui.js have fixed names and are always asked for first, so a new build is picked up at
// once; the cache is only their fallback when the network is down.
// Old builds' files are dropped when a newer index.html is seen: the engine files that do not carry its hash, and
// all but the newest few wasm files.

const CACHE = 'playground-v1';
const HASHED = /\/(?:worker|jars)-([0-9a-f]{12})\/|\/[0-9a-f]{16,}\.wasm$/;
// Fonts and strings keep their names from build to build, so they are served from the cache and refreshed behind it.
const REVALIDATED = /\/composeResources\//;
const KEEP_WASM = 2;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil((async () => {
  for (const name of await caches.keys()) if (name !== CACHE) await caches.delete(name);
  await self.clients.claim();
})()));

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (HASHED.test(url.pathname)) e.respondWith(cacheFirst(req));
  else if (REVALIDATED.test(url.pathname)) e.respondWith(staleWhileRevalidate(req, e));
  else if (/\/(?:index\.html|playground-ui\.js)?$/.test(url.pathname)) e.respondWith(networkFirst(req));
});

async function cacheFirst(req) {
  const cache = await caches.open(CACHE);
  const hit = await cache.match(req);
  if (hit) return hit;
  const res = await fetch(req);
  if (res.ok) await cache.put(req, res.clone());
  return res;
}

async function staleWhileRevalidate(req, e) {
  const cache = await caches.open(CACHE);
  const hit = await cache.match(req);
  const fresh = fetch(req).then(async (res) => { if (res.ok) await cache.put(req, res.clone()); return res; });
  if (hit) { e.waitUntil(fresh.catch(() => {})); return hit; }
  return fresh;
}

async function networkFirst(req) {
  const cache = await caches.open(CACHE);
  try {
    const res = await fetch(req, { cache: 'no-cache' });
    if (res.ok) {
      await cache.put(req, res.clone());
      if (new URL(req.url).pathname.endsWith('/') || new URL(req.url).pathname.endsWith('index.html')) prune(res.clone());
    }
    return res;
  } catch (err) {
    const hit = await cache.match(req);
    if (hit) return hit;
    throw err;
  }
}

// Drop what the page in [res] no longer uses.
async function prune(res) {
  try {
    const hash = /PLAYGROUND_ASSETS = "([0-9a-f]{12})"/.exec(await res.text())?.[1];
    if (!hash) return;
    const cache = await caches.open(CACHE);
    const wasm = [];
    for (const r of await cache.keys()) {
      const m = /\/(?:worker|jars)-([0-9a-f]{12})\//.exec(r.url);
      if (m && m[1] !== hash) await cache.delete(r);
      else if (/\.wasm$/.test(r.url)) wasm.push(r);
    }
    for (const r of wasm.slice(0, Math.max(0, wasm.length - KEEP_WASM))) await cache.delete(r);
  } catch (err) { /* a failed prune only leaves old files */ }
}
