package com.example.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Full-screen Frutiger Aero sky, sunlit water bokeh, and aurora glass horizon backdrop.
 * Supports both Light Aero (sunlit aqua sky) and Dark Aero (midnight sapphire & teal aurora glass).
 * Falls back cleanly to flat [PulseMeshPalette.background] when another theme is selected.
 */
fun Modifier.aeroCanvasBackground(palette: PulseMeshPalette): Modifier {
    if (!palette.isAero) {
        return this.drawBehind {
            drawRect(color = palette.background)
        }
    }
    if (palette.isDarkAero) {
        return this.drawBehind {
            // 1. Deep midnight sapphire to abyssal teal aurora gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFF030D18),
                        0.36f to Color(0xFF072137),
                        0.72f to Color(0xFF0A334B),
                        1.00f to Color(0xFF052436),
                    )
                )
            )

            // 2. Luminous aurora & glass bokeh orbs
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x5238CFFF), Color(0x0038CFFF)),
                    center = Offset(size.width * 0.16f, size.height * 0.15f),
                    radius = size.minDimension * 0.56f,
                ),
                radius = size.minDimension * 0.56f,
                center = Offset(size.width * 0.16f, size.height * 0.15f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x4219E68C), Color(0x0019E68C)),
                    center = Offset(size.width * 0.85f, size.height * 0.30f),
                    radius = size.minDimension * 0.48f,
                ),
                radius = size.minDimension * 0.48f,
                center = Offset(size.width * 0.85f, size.height * 0.30f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x3800A8FF), Color(0x0000A8FF)),
                    center = Offset(size.width * 0.30f, size.height * 0.84f),
                    radius = size.minDimension * 0.58f,
                ),
                radius = size.minDimension * 0.58f,
                center = Offset(size.width * 0.30f, size.height * 0.84f),
            )

            // 3. Sweeping smoked-crystal aurora ribbon
            val wavePath = Path().apply {
                moveTo(0f, size.height * 0.60f)
                cubicTo(
                    size.width * 0.30f,
                    size.height * 0.50f,
                    size.width * 0.68f,
                    size.height * 0.70f,
                    size.width,
                    size.height * 0.56f,
                )
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                path = wavePath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x3638CFFF), Color(0x14082C44)),
                    startY = size.height * 0.50f,
                    endY = size.height,
                ),
            )
        }
    }
    return this.drawBehind {
        // 1. Sunlit sky-to-aqua-mint base gradient
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFFB3E5FF),
                    0.38f to Color(0xFFE5F7FF),
                    0.76f to Color(0xFFD6F7EE),
                    1.00f to Color(0xFFBDF0E3),
                )
            )
        )

        // 2. Soft sunlit bokeh lens orbs
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xBBFFFFFF), Color(0x00FFFFFF)),
                center = Offset(size.width * 0.18f, size.height * 0.14f),
                radius = size.minDimension * 0.55f,
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(size.width * 0.18f, size.height * 0.14f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x6640D9FF), Color(0x0040D9FF)),
                center = Offset(size.width * 0.86f, size.height * 0.28f),
                radius = size.minDimension * 0.48f,
            ),
            radius = size.minDimension * 0.48f,
            center = Offset(size.width * 0.86f, size.height * 0.28f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x5548F5A8), Color(0x0048F5A8)),
                center = Offset(size.width * 0.28f, size.height * 0.82f),
                radius = size.minDimension * 0.58f,
            ),
            radius = size.minDimension * 0.58f,
            center = Offset(size.width * 0.28f, size.height * 0.82f),
        )

        // 3. Sweeping translucent aqua glass wave across mid-lower canvas
        val wavePath = Path().apply {
            moveTo(0f, size.height * 0.62f)
            cubicTo(
                size.width * 0.32f,
                size.height * 0.52f,
                size.width * 0.68f,
                size.height * 0.72f,
                size.width,
                size.height * 0.58f,
            )
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = wavePath,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0x45FFFFFF), Color(0x1866D9FF)),
                startY = size.height * 0.52f,
                endY = size.height,
            ),
        )
    }
}

/**
 * Translucent Frutiger Aero glass panel with upper specular gloss dome and crisp inner rim.
 */
fun Modifier.aeroGlassPanel(
    palette: PulseMeshPalette,
    cornerRadius: Dp = 22.dp,
    tint: Color? = null,
    borderColor: Color = palette.border,
): Modifier {
    if (!palette.isAero) return this
    val resolvedTint = tint ?: if (palette.isDarkAero) Color(0xD40A2338) else Color(0xE8FFFFFF)
    return this
        .drawBehind {
            val r = cornerRadius.toPx()
            val cr = CornerRadius(r, r)

            if (palette.isDarkAero) {
                // Smoked Vista/Aqua dark glass body
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            lerp(resolvedTint, Color(0xFF1A4B70), 0.32f).copy(alpha = 0.88f),
                            resolvedTint.copy(alpha = 0.84f),
                            lerp(resolvedTint, Color(0xFF041422), 0.30f).copy(alpha = 0.90f),
                        )
                    ),
                    cornerRadius = cr,
                )

                // Crisp upper Vista glass reflection dome
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x42FFFFFF), Color(0x08FFFFFF)),
                        startY = 0f,
                        endY = size.height * 0.44f,
                    ),
                    size = Size(size.width, size.height * 0.44f),
                    cornerRadius = cr,
                )

                // Outer icy-cyan crystal border + inner pale cyan specular rim
                drawRoundRect(
                    color = borderColor.copy(alpha = 0.85f),
                    cornerRadius = cr,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
                val inset = 1.4.dp.toPx()
                drawRoundRect(
                    color = Color(0x52C7F0FF),
                    topLeft = Offset(inset, inset),
                    size = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f)),
                    style = Stroke(width = 0.9.dp.toPx()),
                )
            } else {
                // Frosted vertical light glass body
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            resolvedTint.copy(alpha = 0.94f),
                            lerp(resolvedTint, Color(0xFFCDEEFF), 0.35f).copy(alpha = 0.86f),
                            lerp(resolvedTint, Color(0xFFEBF9FF), 0.2f).copy(alpha = 0.90f),
                        )
                    ),
                    cornerRadius = cr,
                )

                // Upper 46% glossy reflection dome
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xCCFFFFFF), Color(0x18FFFFFF)),
                        startY = 0f,
                        endY = size.height * 0.46f,
                    ),
                    size = Size(size.width, size.height * 0.46f),
                    cornerRadius = cr,
                )

                // Crisp outer crystal border + inner specular rim
                drawRoundRect(
                    color = borderColor.copy(alpha = 0.85f),
                    cornerRadius = cr,
                    style = Stroke(width = 1.25.dp.toPx()),
                )
                val inset = 1.5.dp.toPx()
                drawRoundRect(
                    color = Color(0xB8FFFFFF),
                    topLeft = Offset(inset, inset),
                    size = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f)),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
}

/**
 * Glossy 2000s Aqua / Vista 3D Gel overlay for pills, buttons, and call controls when Aero is active.
 */
fun Modifier.aeroGlossOverlay(
    palette: PulseMeshPalette,
    baseColor: Color,
    shape: Shape,
    cornerRadius: Dp = 18.dp,
): Modifier {
    if (!palette.isAero) return this
    return this
        .clip(shape)
        .drawWithContent {
            val r = cornerRadius.toPx()
            val cr = CornerRadius(r, r)
            val topHighlight = lerp(baseColor, Color.White, if (palette.isDarkAero) 0.38f else 0.52f)
            val bottomGlow = lerp(
                baseColor,
                if (palette.isDarkAero) Color(0xFF00E5FF) else Color(0xFFB8F4FF),
                if (palette.isDarkAero) 0.22f else 0.28f
            )

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to topHighlight,
                        0.48f to baseColor,
                        0.52f to lerp(baseColor, Color.Black, if (palette.isDarkAero) 0.22f else 0.10f),
                        1.00f to bottomGlow,
                    )
                ),
                cornerRadius = cr,
            )
            // Specular upper glass crescent
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(if (palette.isDarkAero) 0x85FFFFFF else 0xB8FFFFFF),
                        Color(0x14FFFFFF)
                    ),
                    startY = 0f,
                    endY = size.height * 0.48f,
                ),
                topLeft = Offset(2.dp.toPx(), 1.5.dp.toPx()),
                size = Size((size.width - 4.dp.toPx()).coerceAtLeast(0f), (size.height * 0.46f).coerceAtLeast(0f)),
                cornerRadius = cr,
            )
            drawRoundRect(
                color = Color(if (palette.isDarkAero) 0x9AE0F7FF else 0xB3FFFFFF),
                cornerRadius = cr,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawContent()
        }
}
