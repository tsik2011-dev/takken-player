/* スマートテキスト 読み上げ — service worker
   app shell: network-first (3 s timeout) with cache fallback, so updates arrive when online
   a/<chapter>.bin?v=<hash> (encrypted audio): cache-first by exact URL (a new hash = a new download), saved when played or downloaded for offline use
   a/meta.json, a/data.bin: network-first
   fonts: cache-first */
const VERSION = '2026.10.15-2';
const SHELL = 'stx-shell-' + VERSION;
const PACK = 'stx-pack-v1';
const FONTS = 'stx-fonts';
const SHELL_FILES = ['./', 'index.html', 'manifest.webmanifest', 'silence.mp3', 'a/meta.json', 'a/data.bin',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/icon-maskable-512.png', 'icons/apple-touch-icon.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(SHELL_FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(ks => Promise.all(ks.filter(k => k.startsWith('stx-shell-') && k !== SHELL).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

function timeout(ms) { return new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms)); }

async function networkFirst(req, cacheName) {
  const c = await caches.open(cacheName);
  try {
    const r = await Promise.race([fetch(req), timeout(3000)]);
    if (r && r.ok) c.put(req, r.clone());
    return r;
  } catch (err) {
    const hit = await c.match(req, { ignoreSearch: true }) || await caches.match(req, { ignoreSearch: true });
    if (hit) return hit;
    if (req.mode === 'navigate') { const idx = await caches.match('index.html'); if (idx) return idx; }
    return Response.error();
  }
}

async function cacheFirst(req, cacheName, opaqueOk, exact) {
  const c = await caches.open(cacheName);
  const hit = await c.match(req, { ignoreSearch: !exact });
  if (hit) return hit;
  const r = await fetch(req);
  if (r.ok || (opaqueOk && r.type === 'opaque')) c.put(req, r.clone());
  return r;
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin === location.origin) {
    if (!url.pathname.startsWith(new URL('./', self.registration.scope).pathname)) return;
    if (/\/a\/\d-\d\d\.bin$/.test(url.pathname)) { e.respondWith(cacheFirst(req, PACK, false, true)); return; }
    if (url.pathname.includes('/a/')) { e.respondWith(networkFirst(req, PACK)); return; }
    e.respondWith(networkFirst(req, SHELL));
    return;
  }
  if (/fonts\.(googleapis|gstatic)\.com$/.test(url.hostname)) {
    e.respondWith(cacheFirst(req, FONTS, true).catch(() => Response.error()));
  }
});
