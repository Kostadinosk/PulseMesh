// PulseMesh v1.5.5 Offline PWA Service Worker (iOS 16.4+ Web Push, Safari & Desktop)
const CACHE_NAME = "pulsemesh-web-v200";
const TILE_CACHE = "pulsemesh-tiles-v155";
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

  // Never intercept live WebSocket / SSE / local host API / APK beaming / CA profiles / Cloudflare Workers / ntfy
  if (
    url.pathname.startsWith("/api/") ||
    url.pathname.startsWith("/download/") ||
    url.pathname === "/ca.mobileconfig" ||
    url.pathname === "/ws" ||
    url.hostname.endsWith("ntfy.sh") ||
    url.hostname.endsWith("workers.dev")
  ) {
    return;
  }

  // Cache OpenStreetMap tiles for offline map viewing
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

self.addEventListener("push", (event) => {
  let title = "PulseMesh Alert";
  let body = "New off-grid / hybrid mesh message";
  let tag = "pulsemesh-msg";
  let targetUrl = "./";

  if (event.data) {
    try {
      const parsed = event.data.json();
      if (parsed && typeof parsed === "object") {
        title = parsed.title || title;
        body = parsed.body || parsed.message || body;
        tag = parsed.tag || tag;
        targetUrl = parsed.url || targetUrl;
      }
    } catch {
      const txt = event.data.text();
      if (txt) body = txt;
    }
  }

  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      icon: "../assets/pulsemesh-logo.png",
      badge: "../assets/pulsemesh-logo.png",
      tag,
      data: { url: targetUrl }
    })
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const targetUrl = (event.notification.data && event.notification.data.url) || "./";
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if ("focus" in client) return client.focus();
      }
      if (self.clients.openWindow) return self.clients.openWindow(targetUrl);
      return undefined;
    })
  );
});
