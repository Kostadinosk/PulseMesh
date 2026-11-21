// PulseMesh v1.4.7 Offline PWA Service Worker (iOS / iPhone & Desktop)
const CACHE_NAME = "pulsemesh-web-v147";
const TILE_CACHE = "pulsemesh-tiles-v147";
const APP_SHELL = [
  "./",
  "./index.html",
  "./manifest.json",
  "../assets/pulsemesh-logo.png"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((k) => k !== CACHE_NAME && k !== TILE_CACHE)
          .map((k) => caches.delete(k))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);

  // Never intercept live WebSocket / SSE / local host API / ntfy live streams / Cloudflare Workers D1 relay
  if (url.pathname.startsWith("/api/") || url.pathname === "/ws" || url.hostname.endsWith("ntfy.sh") || url.hostname.endsWith("workers.dev")) {
    return;
  }

  // Cache OpenStreetMap tiles for offline iPhone map viewing
  if (url.hostname.includes("tile.openstreetmap.org")) {
    event.respondWith(
      caches.open(TILE_CACHE).then((cache) =>
        cache.match(req).then((hit) => {
          const net = fetch(req)
            .then((res) => {
              if (res && res.ok) cache.put(req, res.clone());
              return res;
            })
            .catch(() => hit);
          return hit || net;
        })
      )
    );
    return;
  }

  // App shell: network-first with instant offline cache fallback
  event.respondWith(
    fetch(req)
      .then((res) => {
        if (res && res.ok && url.origin === self.location.origin) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(req, clone)).catch(() => {});
        }
        return res;
      })
      .catch(() => caches.match(req).then((r) => r || caches.match("./index.html")))
  );
});
