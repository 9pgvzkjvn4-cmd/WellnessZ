/*
 * WellnessZ service worker – APP SHELL ONLY.
 *
 * Hard rule: API responses, session/auth endpoints and anything that may carry
 * PHI are NEVER cached. Only same-origin, GET, static build assets and the
 * app shell (index.html for offline navigation fallback) are cached.
 * Bump CACHE_VERSION on every release that changes the shell.
 */
const CACHE_VERSION = 'wz-shell-v1';
const SHELL = ['/', '/manifest.webmanifest', '/icons/icon-192.png', '/icons/icon-512.png'];

/** Path prefixes that must always go to the network and never into a cache. */
const NEVER_CACHE_PREFIXES = ['/bff', '/api', '/oauth2', '/login', '/logout', '/config.json', '/actuator'];

/**
 * Decide whether a request may be served from / stored in the shell cache.
 * Exposed on `self` for unit tests.
 * @param {string} urlString absolute URL of the request
 * @param {string} method HTTP method
 * @param {string} origin the service worker's origin
 * @returns {boolean}
 */
function isCacheableRequest(urlString, method, origin) {
  if (method !== 'GET') return false;
  const url = new URL(urlString);
  if (url.origin !== origin) return false; // IdP, core API, anything cross-origin
  if (url.search) return false; // query strings are API-ish; shell assets have none
  if (NEVER_CACHE_PREFIXES.some((p) => url.pathname === p || url.pathname.startsWith(p + '/') || url.pathname.startsWith(p + '?'))) {
    return false;
  }
  return url.pathname === '/' || url.pathname === '/index.html' || url.pathname.startsWith('/assets/') ||
    url.pathname.startsWith('/icons/') || url.pathname === '/manifest.webmanifest';
}
self.isCacheableRequest = isCacheableRequest;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_VERSION).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (!isCacheableRequest(req.url, req.method, self.location.origin)) {
    return; // default browser handling: straight to network, nothing stored
  }
  if (req.mode === 'navigate') {
    // Network-first for the shell so new releases are picked up; offline → cached shell.
    event.respondWith(fetch(req).catch(() => caches.match('/')));
    return;
  }
  // Hashed build assets are immutable → cache-first.
  event.respondWith(
    caches.match(req).then(
      (hit) =>
        hit ||
        fetch(req).then((res) => {
          if (res.ok && res.type === 'basic') {
            const copy = res.clone();
            caches.open(CACHE_VERSION).then((cache) => cache.put(req, copy));
          }
          return res;
        }),
    ),
  );
});
