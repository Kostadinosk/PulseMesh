// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.*
import com.example.viewmodel.MeshUiState
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

private data class RssiNodeSeries(
    val id: String,
    val callsign: String,
    val samples: List<Int>,
    val latestRssi: Int,
    val snrDb: Double,
    val hopCount: Int,
    val colorHex: String,
    val composeColor: Color,
)

/**
 * PRO-ONLY Real-Time Multi-Node RSSI Signal Strength Chart.
 * Renders an interactive D3/Recharts-style SVG visualization inside WebView on live devices,
 * with a synchronized native Compose Canvas fallback for previews and Robolectric unit tests.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProMeshRssiChartCard(
    state: MeshUiState,
    modifier: Modifier = Modifier,
) {
    val p = LocalPulsePalette.current
    val isInspectionOrRobolectric = LocalInspectionMode.current ||
        Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

    val seriesPalette = remember(p.isLight) {
        if (p.isLight) {
            listOf(
                "#008F4C" to Color(0xFF008F4C),
                "#0284C7" to Color(0xFF0284C7),
                "#D97706" to Color(0xFFD97706),
                "#DC2626" to Color(0xFFDC2626),
                "#7C3AED" to Color(0xFF7C3AED),
            )
        } else {
            listOf(
                "#00E676" to Color(0xFF00E676),
                "#00B0FF" to Color(0xFF00B0FF),
                "#FF9100" to Color(0xFFFF9100),
                "#FF1744" to Color(0xFFFF1744),
                "#C084FC" to Color(0xFFC084FC),
            )
        }
    }

    // Rolling 30-sample RSSI buffer per node
    val historyMap = remember { mutableStateMapOf<String, List<Int>>() }
    var sampleTick by remember { mutableIntStateOf(0) }
    var historyRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            sampleTick++
            delay(1200.milliseconds)
        }
    }

    val remotePeers = remember(state.nodes) {
        state.nodes.filter { !it.isSelf }.take(5)
    }

    LaunchedEffect(sampleTick, state.nodes, state.lastRssiDbm, state.rxFramesCount) {
        if (remotePeers.isEmpty()) {
            val prev = historyMap["LOCAL_RF"] ?: List(14) { state.lastRssiDbm }
            val next = (prev + state.lastRssiDbm).takeLast(30)
            historyMap["LOCAL_RF"] = next
        } else {
            val activeIds = remotePeers.map { it.id }.toSet()
            historyMap.keys.filter { it !in activeIds && it != "LOCAL_RF" }.forEach {
                historyMap.remove(it)
            }
            remotePeers.forEach { peer ->
                val prev = historyMap[peer.id] ?: List(10) { peer.rssiDbm }
                val next = (prev + peer.rssiDbm).takeLast(30)
                historyMap[peer.id] = next
            }
        }
        historyRevision++
    }

    val activeSeries = remember(historyRevision, remotePeers, state.lastRssiDbm, seriesPalette) {
        if (remotePeers.isEmpty()) {
            val samples = historyMap["LOCAL_RF"] ?: listOf(state.lastRssiDbm)
            listOf(
                RssiNodeSeries(
                    id = state.localNodeId,
                    callsign = "${state.operatorCallsign} (Noise Floor)",
                    samples = samples,
                    latestRssi = samples.lastOrNull() ?: state.lastRssiDbm,
                    snrDb = 12.0,
                    hopCount = 0,
                    colorHex = seriesPalette[0].first,
                    composeColor = seriesPalette[0].second,
                )
            )
        } else {
            remotePeers.mapIndexed { idx, peer ->
                val (hex, col) = seriesPalette[idx % seriesPalette.size]
                val samples = historyMap[peer.id] ?: listOf(peer.rssiDbm)
                RssiNodeSeries(
                    id = peer.id,
                    callsign = peer.callsign,
                    samples = samples,
                    latestRssi = peer.rssiDbm,
                    snrDb = peer.snrDb,
                    hopCount = peer.hopCount,
                    colorHex = hex,
                    composeColor = col,
                )
            }
        }
    }

    val payloadJson = remember(activeSeries, p.isLight) {
        val root = JSONObject().apply {
            put("isLight", p.isLight)
            put("bg", colorToHex(p.background))
            put("grid", colorToHex(p.border))
            put("text", colorToHex(p.textSecondary))
            put("label", colorToHex(p.textPrimary))
            val arr = JSONArray()
            activeSeries.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("name", s.callsign)
                        put("color", s.colorHex)
                        put("rssi", s.latestRssi)
                        put("snr", s.snrDb)
                        put("hops", s.hopCount)
                        put("values", JSONArray(s.samples))
                    }
                )
            }
            put("series", arr)
        }
        root.toString()
    }

    Surface(
        color = p.card,
        shape = tacticalCardShape(isSimpleMode = false),
        border = BorderStroke(1.dp, p.cyan.copy(alpha = 0.7f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("pro_mesh_rssi_chart_card"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = "Real-Time RSSI Chart",
                        tint = p.cyan,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "REAL-TIME MESH RSSI TELEMETRY",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = p.textPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "D3 / Recharts SVG Stream • -30 to -110 dBm • Pro Only",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = p.textSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Surface(
                    color = p.cyanDim,
                    shape = tacticalButtonShape(false),
                    border = BorderStroke(1.dp, p.cyan),
                ) {
                    Text(
                        text = if (remotePeers.isEmpty()) "RF BASELINE" else "${remotePeers.size} NODES LIVE",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = p.cyan,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                        ),
                    )
                }
            }

            // Chart Viewport (D3/Recharts SVG WebView on device, Compose Canvas in preview/tests)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.background)
                    .border(1.dp, p.border, RoundedCornerShape(8.dp)),
            ) {
                if (isInspectionOrRobolectric) {
                    RssiCanvasChart(
                        series = activeSeries,
                        palette = p,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    D3RechartsRssiWebView(
                        payloadJson = payloadJson,
                        backgroundColor = p.background,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Live Node Metric Pills (RSSI dBm + SNR + Quality Tier)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                activeSeries.forEach { s ->
                    val tier = when {
                        s.latestRssi >= -65 -> "EXCELLENT"
                        s.latestRssi >= -80 -> "GOOD"
                        s.latestRssi >= -92 -> "WEAK"
                        else -> "CRIT"
                    }
                    Surface(
                        color = p.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, s.composeColor.copy(alpha = 0.65f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(s.composeColor),
                            )
                            Text(
                                text = "${s.callsign.take(10)}: ${s.latestRssi} dBm • SNR ${"%.1f".format(s.snrDb)}dB [$tier]",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = p.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun D3RechartsRssiWebView(
    payloadJson: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val latestJson by rememberUpdatedState(payloadJson)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(latestJson, isLoaded) {
        if (isLoaded) {
            webViewRef?.evaluateJavascript(
                "if (window.updateRssiChart) { window.updateRssiChart($latestJson); }",
                null,
            )
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(backgroundColor.toArgb())
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        isLoaded = true
                        view?.evaluateJavascript(
                            "if (window.updateRssiChart) { window.updateRssiChart($latestJson); }",
                            null,
                        )
                    }
                }
                loadDataWithBaseURL(
                    "https://localhost/",
                    D3_RECHARTS_RSSI_HTML,
                    "text/html",
                    "UTF-8",
                    null,
                )
                webViewRef = this
            }
        },
        update = { view ->
            view.setBackgroundColor(backgroundColor.toArgb())
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
    )
}

@Composable
private fun RssiCanvasChart(
    series: List<RssiNodeSeries>,
    palette: PulseMeshPalette,
    modifier: Modifier = Modifier,
) {
    val linePath = remember { Path() }
    val areaPath = remember { Path() }

    Box(modifier = modifier.padding(10.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val leftPad = 36f
            val rightPad = 10f
            val topPad = 12f
            val bottomPad = 16f
            val chartW = (size.width - leftPad - rightPad).coerceAtLeast(10f)
            val chartH = (size.height - topPad - bottomPad).coerceAtLeast(10f)

            val minRssi = -110f
            val maxRssi = -30f
            fun yForRssi(dbm: Int): Float {
                val norm = ((dbm.toFloat().coerceIn(minRssi, maxRssi) - minRssi) / (maxRssi - minRssi))
                return topPad + chartH * (1f - norm)
            }

            // Grid lines (-40, -65, -85, -105 dBm)
            listOf(-40, -65, -85, -105).forEach { lvl ->
                val y = yForRssi(lvl)
                drawLine(
                    color = palette.border.copy(alpha = 0.55f),
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + chartW, y),
                    strokeWidth = 1f,
                    pathEffect = if (lvl == -65 || lvl == -85) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
                )
            }

            // Curves & gradient area fills
            series.forEach { s ->
                val pts = s.samples
                if (pts.isEmpty()) return@forEach
                val stepX = if (pts.size <= 1) chartW else chartW / (pts.size - 1).toFloat()

                linePath.reset()
                areaPath.reset()
                pts.forEachIndexed { idx, rssi ->
                    val x = leftPad + idx * stepX
                    val y = yForRssi(rssi)
                    if (idx == 0) {
                        linePath.moveTo(x, y)
                        areaPath.moveTo(x, topPad + chartH)
                        areaPath.lineTo(x, y)
                    } else {
                        val prevX = leftPad + (idx - 1) * stepX
                        val prevY = yForRssi(pts[idx - 1])
                        val midX = (prevX + x) / 2f
                        linePath.cubicTo(midX, prevY, midX, y, x, y)
                        areaPath.cubicTo(midX, prevY, midX, y, x, y)
                    }
                }
                val lastX = leftPad + (pts.size - 1).coerceAtLeast(0) * stepX
                areaPath.lineTo(lastX, topPad + chartH)
                areaPath.close()

                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            s.composeColor.copy(alpha = 0.25f),
                            s.composeColor.copy(alpha = 0.02f),
                        ),
                        startY = topPad,
                        endY = topPad + chartH,
                    ),
                )
                drawPath(
                    path = linePath,
                    color = s.composeColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                val endY = yForRssi(pts.last())
                drawCircle(
                    color = s.composeColor.copy(alpha = 0.3f),
                    radius = 7.dp.toPx(),
                    center = Offset(lastX, endY),
                )
                drawCircle(
                    color = s.composeColor,
                    radius = 3.5.dp.toPx(),
                    center = Offset(lastX, endY),
                )
            }
        }

        // Y-Axis labels
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("-30", "-65", "-85", "-110").forEach { lbl ->
                Text(
                    text = lbl,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = palette.textMuted,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

private fun colorToHex(color: Color): String {
    val rgb = color.toArgb() and 0xFFFFFF
    return String.format("#%06X", rgb)
}

/**
 * Offline D3/Recharts-architecture SVG renderer:
 * Uses D3-style linear scales, monotone cubic Bezier curves, Recharts-style ResponsiveContainer SVG
 * grid, ReferenceLine threshold indicators (-65 / -85 dBm), animated gradient Area + Line series,
 * and interactive touch crosshair tooltip.
 */
private const val D3_RECHARTS_RSSI_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body { width: 100%; height: 100%; overflow: hidden; font-family: monospace; }
  #chart-wrap { position: relative; width: 100%; height: 100%; }
  svg { width: 100%; height: 100%; display: block; }
  #tooltip {
    position: absolute; top: 6px; right: 8px;
    padding: 4px 8px; border-radius: 5px; font-size: 10px;
    pointer-events: none; opacity: 0.92;
  }
</style>
</head>
<body>
<div id="chart-wrap">
  <svg id="rssi-svg" viewBox="0 0 600 220" preserveAspectRatio="none"></svg>
  <div id="tooltip"></div>
</div>
<script>
(function() {
  const svg = document.getElementById('rssi-svg');
  const tooltip = document.getElementById('tooltip');
  let hoverIdx = -1;
  let lastData = null;

  function scaleLinear(domainMin, domainMax, rangeMin, rangeMax) {
    return function(v) {
      const t = (v - domainMin) / (domainMax - domainMin);
      return rangeMin + Math.max(0, Math.min(1, t)) * (rangeMax - rangeMin);
    };
  }

  function monotonePath(points) {
    if (!points.length) return '';
    if (points.length === 1) return 'M' + points[0][0] + ',' + points[0][1];
    let d = 'M' + points[0][0].toFixed(1) + ',' + points[0][1].toFixed(1);
    for (let i = 1; i < points.length; i++) {
      const p0 = points[i - 1], p1 = points[i];
      const cx = ((p0[0] + p1[0]) / 2).toFixed(1);
      d += ' C' + cx + ',' + p0[1].toFixed(1) + ' ' + cx + ',' + p1[1].toFixed(1) + ' ' + p1[0].toFixed(1) + ',' + p1[1].toFixed(1);
    }
    return d;
  }

  function el(tag, attrs, text) {
    const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const k in attrs) node.setAttribute(k, attrs[k]);
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function render(data) {
    lastData = data;
    document.body.style.background = data.bg || '#0A0E14';
    while (svg.firstChild) svg.removeChild(svg.firstChild);

    const W = 600, H = 220;
    const pad = { l: 48, r: 16, t: 18, b: 24 };
    const cw = W - pad.l - pad.r;
    const ch = H - pad.t - pad.b;
    const yScale = scaleLinear(-110, -30, pad.t + ch, pad.t);

    const defs = el('defs', {});
    const series = data.series || [];
    series.forEach((s, idx) => {
      const grad = el('linearGradient', { id: 'grad_' + idx, x1: '0', y1: '0', x2: '0', y2: '1' });
      grad.appendChild(el('stop', { offset: '0%', 'stop-color': s.color, 'stop-opacity': '0.32' }));
      grad.appendChild(el('stop', { offset: '100%', 'stop-color': s.color, 'stop-opacity': '0.02' }));
      defs.appendChild(grad);
    });
    svg.appendChild(defs);

    // Recharts CartesianGrid & ReferenceLine thresholds
    [-30, -50, -65, -85, -100, -110].forEach(dbm => {
      const y = yScale(dbm);
      const isRef = (dbm === -65 || dbm === -85);
      svg.appendChild(el('line', {
        x1: pad.l, y1: y, x2: W - pad.r, y2: y,
        stroke: isRef ? (dbm === -65 ? '#00E676' : '#FF9100') : (data.grid || '#283850'),
        'stroke-opacity': isRef ? '0.45' : '0.45',
        'stroke-width': isRef ? '1.2' : '1',
        'stroke-dasharray': isRef ? '5,5' : '2,4'
      }));
      svg.appendChild(el('text', {
        x: pad.l - 6, y: y + 3, 'text-anchor': 'end',
        fill: data.text || '#94A3B8', 'font-size': '10'
      }, dbm + 'dB'));
    });

    // Draw each node Area + Line
    let maxLen = 1;
    series.forEach(s => { if (s.values && s.values.length > maxLen) maxLen = s.values.length; });
    const xScale = scaleLinear(0, Math.max(1, maxLen - 1), pad.l, pad.l + cw);

    series.forEach((s, idx) => {
      const vals = s.values || [];
      if (!vals.length) return;
      const pts = vals.map((v, i) => [xScale(i), yScale(Math.max(-110, Math.min(-30, v)))]);
      const lineD = monotonePath(pts);
      const areaD = lineD + ' L' + pts[pts.length - 1][0].toFixed(1) + ',' + (pad.t + ch) +
        ' L' + pts[0][0].toFixed(1) + ',' + (pad.t + ch) + ' Z';

      svg.appendChild(el('path', { d: areaD, fill: 'url(#grad_' + idx + ')' }));
      svg.appendChild(el('path', {
        d: lineD, fill: 'none', stroke: s.color, 'stroke-width': '2.6',
        'stroke-linecap': 'round', 'stroke-linejoin': 'round'
      }));

      const targetIdx = (hoverIdx >= 0 && hoverIdx < pts.length) ? hoverIdx : (pts.length - 1);
      const hp = pts[targetIdx];
      svg.appendChild(el('circle', { cx: hp[0], cy: hp[1], r: '6', fill: s.color, 'fill-opacity': '0.28' }));
      svg.appendChild(el('circle', { cx: hp[0], cy: hp[1], r: '3.2', fill: s.color }));
    });

    if (hoverIdx >= 0 && maxLen > 1) {
      const hx = xScale(Math.min(hoverIdx, maxLen - 1));
      svg.appendChild(el('line', {
        x1: hx, y1: pad.t, x2: hx, y2: pad.t + ch,
        stroke: data.label || '#F1F5F9', 'stroke-width': '1', 'stroke-dasharray': '3,3', 'stroke-opacity': '0.6'
      }));
    }

    // Tooltip summary
    tooltip.style.background = data.isLight ? 'rgba(255,255,255,0.9)' : 'rgba(16,22,32,0.9)';
    tooltip.style.border = '1px solid ' + (data.grid || '#283850');
    tooltip.style.color = data.label || '#F1F5F9';
    if (series.length) {
      tooltip.innerHTML = series.map(s => {
        const vals = s.values || [];
        const v = (hoverIdx >= 0 && hoverIdx < vals.length) ? vals[hoverIdx] : s.rssi;
        return '<span style="color:' + s.color + ';font-weight:bold;">● ' +
          s.name.slice(0, 10) + ': ' + v + ' dBm</span>';
      }).join('<br>');
    }
  }

  svg.addEventListener('pointermove', function(e) {
    if (!lastData || !lastData.series || !lastData.series.length) return;
    const rect = svg.getBoundingClientRect();
    const relX = (e.clientX - rect.left) / Math.max(1, rect.width);
    const maxLen = Math.max(...lastData.series.map(s => (s.values || []).length));
    hoverIdx = Math.max(0, Math.min(maxLen - 1, Math.round(relX * (maxLen - 1))));
    render(lastData);
  });
  svg.addEventListener('pointerleave', function() {
    hoverIdx = -1;
    if (lastData) render(lastData);
  });

  window.updateRssiChart = render;
})();
</script>
</body>
</html>
"""
