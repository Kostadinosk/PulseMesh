// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

/**
 * Lightweight EPSG:3857 Offline Tactical Map (IndexedDB tile cache, Dark/Topo/Radar styles,
 * bearing/distance ruler, breadcrumbs, waypoint drop, and GPX 1.1 export).
 */

const TILE_SIZE = 256;

export function latLonToTileFloat(lat, lon, zoom) {
  const n = Math.pow(2, zoom);
  const clamped = Math.max(-85.0511, Math.min(85.0511, lat));
  const rad = (clamped * Math.PI) / 180;
  const x = ((lon + 180) / 360) * n;
  const y = ((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * n;
  return { x, y };
}

export function tileFloatToLatLon(x, y, zoom) {
  const n = Math.pow(2, zoom);
  const lon = (x / n) * 360 - 180;
  const latRad = Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / n)));
  const lat = (latRad * 180) / Math.PI;
  return { lat, lon };
}

export function haversineMeters(lat1, lon1, lat2, lon2) {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export function initialBearingDeg(lat1, lon1, lat2, lon2) {
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const y = Math.sin(dLon) * Math.cos(phi2);
  const x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

export function exportWaypointsToGpx(waypoints = [], breadcrumbs = []) {
  const esc = (s) => String(s || "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  let xml = '<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="PulseMesh Web 1.5.5">\n';
  for (const wp of waypoints) {
    xml += `  <wpt lat="${wp.lat}" lon="${wp.lon}"><name>${esc(wp.title)}</name><type>${esc(wp.type || "RALLY")}</type></wpt>\n`;
  }
  if (breadcrumbs.length) {
    xml += "  <trk><name>PulseMesh Track</name><trkseg>\n";
    for (const pt of breadcrumbs) {
      xml += `    <trkpt lat="${pt.lat}" lon="${pt.lon}"></trkpt>\n`;
    }
    xml += "  </trkseg></trk>\n";
  }
  xml += "</gpx>\n";
  return xml;
}

export function createTacticalMapController({ canvas, getState, onDropWaypoint, onRulerUpdate }) {
  const ctx = canvas.getContext("2d");
  const tileCache = new Map();
  let centerLat = 37.9838;
  let centerLon = 23.7275;
  let zoom = 13;
  let layerStyle = "dark";
  let rulerMode = false;
  let rulerA = null;
  let rulerB = null;
  let isDragging = false;
  let dragStartX = 0;
  let dragStartY = 0;
  let dragMoved = false;

  function resize() {
    const rect = canvas.parentElement?.getBoundingClientRect();
    if (!rect) return;
    canvas.width = Math.max(300, Math.floor(rect.width));
    canvas.height = Math.max(240, Math.floor(rect.height));
    render();
  }

  function loadTile(z, x, y) {
    const key = `${z}/${x}/${y}`;
    if (tileCache.has(key)) return tileCache.get(key);
    const img = new Image();
    img.crossOrigin = "anonymous";
    const entry = { img, loaded: false };
    tileCache.set(key, entry);
    img.onload = () => {
      entry.loaded = true;
      render();
    };
    const isLocalHost = Boolean(getState?.().isLocalHost);
    img.src = isLocalHost
      ? `/api/tiles/${z}/${x}/${y}.png`
      : `https://tile.openstreetmap.org/${z}/${x}/${y}.png`;
    return entry;
  }

  function latLonToScreen(lat, lon) {
    const c = latLonToTileFloat(centerLat, centerLon, zoom);
    const p = latLonToTileFloat(lat, lon, zoom);
    return {
      x: canvas.width / 2 + (p.x - c.x) * TILE_SIZE,
      y: canvas.height / 2 + (p.y - c.y) * TILE_SIZE
    };
  }

  function screenToLatLon(sx, sy) {
    const c = latLonToTileFloat(centerLat, centerLon, zoom);
    const tx = c.x + (sx - canvas.width / 2) / TILE_SIZE;
    const ty = c.y + (sy - canvas.height / 2) / TILE_SIZE;
    return tileFloatToLatLon(tx, ty, zoom);
  }

  function render() {
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = layerStyle === "radar" ? "#04121b" : "#071826";
    ctx.fillRect(0, 0, w, h);

    if (layerStyle !== "radar") {
      const c = latLonToTileFloat(centerLat, centerLon, zoom);
      const minX = Math.floor(c.x - w / (2 * TILE_SIZE) - 1);
      const maxX = Math.ceil(c.x + w / (2 * TILE_SIZE) + 1);
      const minY = Math.floor(c.y - h / (2 * TILE_SIZE) - 1);
      const maxY = Math.ceil(c.y + h / (2 * TILE_SIZE) + 1);
      const maxTile = 1 << zoom;

      ctx.save();
      if (layerStyle === "dark") {
        ctx.filter = "invert(92%) hue-rotate(180deg) brightness(0.88) contrast(0.95)";
      }
      for (let tx = minX; tx <= maxX; tx++) {
        for (let ty = minY; ty <= maxY; ty++) {
          if (ty < 0 || ty >= maxTile) continue;
          const wrapX = ((tx % maxTile) + maxTile) % maxTile;
          const tile = loadTile(zoom, wrapX, ty);
          const dx = Math.floor(w / 2 + (tx - c.x) * TILE_SIZE);
          const dy = Math.floor(h / 2 + (ty - c.y) * TILE_SIZE);
          if (tile.loaded) {
            ctx.drawImage(tile.img, dx, dy, TILE_SIZE, TILE_SIZE);
          }
        }
      }
      ctx.restore();
    } else {
      // Tactical radar rings
      ctx.strokeStyle = "rgba(56, 207, 255, 0.25)";
      ctx.lineWidth = 1;
      for (let r = 48; r < Math.min(w, h) * 0.48; r += 48) {
        ctx.beginPath();
        ctx.arc(w / 2, h / 2, r, 0, Math.PI * 2);
        ctx.stroke();
      }
    }

    const st = getState?.() || {};
    const breadcrumbs = st.breadcrumbs || [];
    if (breadcrumbs.length > 1) {
      ctx.strokeStyle = "#38CFFF";
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      breadcrumbs.forEach((pt, idx) => {
        const s = latLonToScreen(pt.lat, pt.lon);
        if (idx === 0) ctx.moveTo(s.x, s.y);
        else ctx.lineTo(s.x, s.y);
      });
      ctx.stroke();
    }

    // Ruler overlay
    if (rulerA && rulerB) {
      const a = latLonToScreen(rulerA.lat, rulerA.lon);
      const b = latLonToScreen(rulerB.lat, rulerB.lon);
      ctx.strokeStyle = "#FFB02E";
      ctx.lineWidth = 2.5;
      ctx.setLineDash([6, 4]);
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
      ctx.setLineDash([]);
    }

    // Waypoints
    for (const wp of st.waypoints || []) {
      const pt = latLonToScreen(wp.lat, wp.lon);
      ctx.fillStyle = wp.type === "SOS" ? "#FF4D6D" : "#FFB02E";
      ctx.beginPath();
      ctx.arc(pt.x, pt.y, 7, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#F0F9FF";
      ctx.font = "bold 11px monospace";
      ctx.fillText(wp.title || wp.type || "PIN", pt.x + 10, pt.y + 4);
    }

    // Peers
    for (const peer of st.peers || []) {
      if (typeof peer.lat !== "number" || typeof peer.lon !== "number" || (peer.lat === 0 && peer.lon === 0)) continue;
      const pt = latLonToScreen(peer.lat, peer.lon);
      ctx.fillStyle = peer.isSelf ? "#19E68C" : "#38CFFF";
      ctx.beginPath();
      ctx.arc(pt.x, pt.y, 8, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = "#041320";
      ctx.lineWidth = 2;
      ctx.stroke();
      ctx.fillStyle = "#F0F9FF";
      ctx.font = "bold 11px monospace";
      ctx.fillText(peer.callsign || peer.nodeId, pt.x + 11, pt.y + 4);
    }
  }

  canvas.addEventListener("pointerdown", (e) => {
    isDragging = true;
    dragMoved = false;
    dragStartX = e.clientX;
    dragStartY = e.clientY;
  });

  canvas.addEventListener("pointermove", (e) => {
    if (!isDragging) return;
    const dx = e.clientX - dragStartX;
    const dy = e.clientY - dragStartY;
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) dragMoved = true;
    dragStartX = e.clientX;
    dragStartY = e.clientY;
    const c = latLonToTileFloat(centerLat, centerLon, zoom);
    const next = tileFloatToLatLon(c.x - dx / TILE_SIZE, c.y - dy / TILE_SIZE, zoom);
    centerLat = next.lat;
    centerLon = next.lon;
    render();
  });

  canvas.addEventListener("pointerup", (e) => {
    if (!isDragging) return;
    isDragging = false;
    if (!dragMoved) {
      const rect = canvas.getBoundingClientRect();
      const coords = screenToLatLon(e.clientX - rect.left, e.clientY - rect.top);
      if (rulerMode) {
        if (!rulerA || rulerB) {
          rulerA = coords;
          rulerB = null;
          onRulerUpdate?.("Ruler A set - tap point B");
        } else {
          rulerB = coords;
          const dist = haversineMeters(rulerA.lat, rulerA.lon, rulerB.lat, rulerB.lon);
          const brg = initialBearingDeg(rulerA.lat, rulerA.lon, rulerB.lat, rulerB.lon);
          const distStr = dist >= 1000 ? `${(dist / 1000).toFixed(2)} km` : `${Math.round(dist)} m`;
          onRulerUpdate?.(`RULER: ${distStr} @ ${Math.round(brg)}°`);
        }
        render();
      } else {
        onDropWaypoint?.(coords);
      }
    }
  });

  return {
    resize,
    render,
    setCenter(lat, lon) {
      centerLat = lat;
      centerLon = lon;
      render();
    },
    zoomIn() {
      zoom = Math.min(18, zoom + 1);
      render();
    },
    zoomOut() {
      zoom = Math.max(3, zoom - 1);
      render();
    },
    setLayer(style) {
      layerStyle = style;
      render();
    },
    toggleRuler() {
      rulerMode = !rulerMode;
      if (!rulerMode) {
        rulerA = null;
        rulerB = null;
        onRulerUpdate?.("");
      } else {
        onRulerUpdate?.("Ruler ON - tap point A");
      }
      render();
      return rulerMode;
    }
  };
}
