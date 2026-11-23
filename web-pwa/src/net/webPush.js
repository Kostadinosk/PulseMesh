// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

import { DEFAULT_CLOUDFLARE_RELAY } from "./cloudflareRealtime.js";

export function isPushSupported() {
  return (
    typeof window !== "undefined" &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    "Notification" in window
  );
}

export function isIosDevice() {
  if (typeof navigator === "undefined") return false;
  return /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
}

export function isStandalonePwa() {
  if (typeof window === "undefined") return false;
  return window.matchMedia?.("(display-mode: standalone)")?.matches || window.navigator.standalone === true;
}

export function urlBase64ToUint8Array(base64String) {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const rawData = atob(base64);
  const out = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; i++) out[i] = rawData.charCodeAt(i);
  return out;
}

export async function enableWebPush({
  relayBase = DEFAULT_CLOUDFLARE_RELAY,
  username,
  authToken
}) {
  if (!isPushSupported()) {
    if (isIosDevice() && !isStandalonePwa()) {
      throw new Error("On iPhone/iPad (iOS 16.4+), tap Share -> Add to Home Screen first, then open PulseMesh from the Home Screen to enable Web Push.");
    }
    throw new Error("Web Push is not supported in this browser context (requires HTTPS).");
  }
  if (!username || !authToken) {
    throw new Error("Reserve or sign in to an optional @username first to link background Web Push.");
  }

  const keyRes = await fetch(`${relayBase}/v1/push/vapid-public-key`);
  const keyData = await keyRes.json().catch(() => ({}));
  if (!keyRes.ok || !keyData.enabled || !keyData.vapidPublicKey) {
    throw new Error("VAPID Web Push public key is not configured on this relay yet.");
  }

  const perm = await Notification.requestPermission();
  if (perm !== "granted") {
    throw new Error("Notification permission was not granted.");
  }

  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(keyData.vapidPublicKey)
    });
  }

  const subRes = await fetch(`${relayBase}/v1/push/subscribe`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-PulseMesh-Auth": authToken
    },
    body: JSON.stringify({
      username,
      subscription: sub.toJSON()
    })
  });
  if (!subRes.ok) {
    const err = await subRes.json().catch(() => ({}));
    throw new Error(err.error || `Push subscribe failed (HTTP ${subRes.status})`);
  }
  return sub;
}

export async function disableWebPush({
  relayBase = DEFAULT_CLOUDFLARE_RELAY,
  username,
  authToken
}) {
  if (!isPushSupported()) return;
  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  const endpoint = sub ? sub.endpoint : "";
  if (sub) {
    await sub.unsubscribe().catch(() => {});
  }
  if (username && authToken) {
    await fetch(`${relayBase}/v1/push/subscribe`, {
      method: "DELETE",
      headers: {
        "Content-Type": "application/json",
        "X-PulseMesh-Auth": authToken
      },
      body: JSON.stringify({ username, endpoint })
    }).catch(() => {});
  }
}
