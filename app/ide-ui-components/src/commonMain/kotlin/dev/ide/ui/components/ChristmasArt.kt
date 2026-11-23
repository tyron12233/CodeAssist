package dev.ide.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Christmas drawings, as plain [DrawScope] functions so the cards, the nav glyphs, the build moments and
 * the editor share one fir, one gift and one snowflake. Each takes a center (or a base) and a size in pixels and
 * draws nothing else, so a caller decides about layers and animation.
 *
 * The palette is deliberately quiet: a deep fir, a muted cranberry and candle gold, chosen to sit on the
 * Evergreen night and on an ordinary theme alike.
 */

/** The fir of the tree glyph and the cards. */
val ChristmasFir = Color(0xFF2E7D55)
/** A darker fir, for the shaded side of each tier. */
val ChristmasFirShade = Color(0xFF1F5E3F)
/** Cranberry: the gift wrap, the ornaments' first color. */
val ChristmasCranberry = Color(0xFFB8394A)
/** Candle gold: the star, ribbons, the lights. */
val ChristmasGold = Color(0xFFE8C872)
/** Snow, a touch warm so it does not glare on a light theme. */
val ChristmasSnow = Color(0xFFF7F5F0)

/** The warm colors a string of lights or a set of ornaments cycles through. */
val ChristmasLightColors = listOf(Color(0xFFE8C872), Color(0xFFE88A8F), Color(0xFF9FD4B0), Color(0xFFF2B880), Color(0xFFB8C8F0))

/**
 * A six-armed snowflake of [radius] at [center], each arm with one pair of short branches, turned by
 * [rotation] degrees. Drawn as strokes so it stays crisp from a 3 dp flake to a 40 dp one.
 */
fun DrawScope.drawSnowflake(center: Offset, radius: Float, color: Color, rotation: Float = 0f) {
    val stroke = (radius * 0.16f).coerceAtLeast(1f)
    rotate(rotation, pivot = center) {
        for (i in 0 until 6) {
            val a = (i * PI / 3).toFloat()
            val dir = Offset(cos(a), sin(a))
            val tip = center + dir * radius
            drawLine(color, center, tip, stroke, StrokeCap.Round)
            // One pair of branches, two thirds of the way out, swept back toward the center.
            val at = center + dir * (radius * 0.6f)
            for (side in listOf(-1f, 1f)) {
                val b = a + side * (PI / 4).toFloat()
                val end = at + Offset(cos(b), sin(b)) * (radius * 0.32f)
                drawLine(color, at, end, stroke * 0.85f, StrokeCap.Round)
            }
        }
    }
}

/** A soft round flake: the falling snow, where the arms of [drawSnowflake] would be too small to read. */
fun DrawScope.drawSnowDot(center: Offset, radius: Float, color: Color) {
    drawCircle(
        Brush.radialGradient(listOf(color, color.copy(alpha = color.alpha * 0.5f), color.copy(alpha = 0f)), center, radius * 1.7f),
        radius * 1.7f,
        center,
    )
}

/**
 * A five-pointed star of [radius] at [center]: the tree's topper and the "built" glyph. [glow] (0..1) adds a
 * soft halo behind it.
 */
fun DrawScope.drawStar(center: Offset, radius: Float, color: Color, glow: Float = 0f, rotation: Float = 0f) {
    if (glow > 0f) {
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.45f * glow), color.copy(alpha = 0f)), center, radius * 2.2f),
            radius * 2.2f,
            center,
        )
    }
    val path = Path()
    val inner = radius * 0.45f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else inner
        val a = (-PI / 2 + i * PI / 5).toFloat() + rotation * (PI / 180f).toFloat()
        val p = Offset(center.x + cos(a) * r, center.y + sin(a) * r)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color)
}

/**
 * A fir tree standing on [base] (the bottom of its trunk), [height] tall: three tiers, each a little wider than
 * the one above, with a shaded right half so it reads as rounded without outlines, a trunk, and a gold star.
 * [lights] (0..1) lights the bulbs along the tiers; 0 draws the tree bare. [lightPhase] turns which bulbs are
 * brightest, for a twinkle.
 */
fun DrawScope.drawFirTree(
    base: Offset,
    height: Float,
    fir: Color = ChristmasFir,
    shade: Color = ChristmasFirShade,
    star: Color = ChristmasGold,
    lights: Float = 0f,
    lightPhase: Float = 0f,
) {
    val trunkH = height * 0.12f
    val trunkW = height * 0.12f
    drawRect(Color(0xFF6B4A32), Offset(base.x - trunkW / 2f, base.y - trunkH), Size(trunkW, trunkH))
    val crownBottom = base.y - trunkH * 0.6f
    val crownTop = base.y - height * 0.86f
    val tiers = 3
    val tierH = (crownBottom - crownTop) / (tiers - 0.8f)
    for (t in 0 until tiers) {
        // Bottom tier first, so the upper ones overlap it the way branches do.
        val k = tiers - 1 - t
        val bottom = crownBottom - k * tierH * 0.6f
        val top = bottom - tierH
        val halfW = height * (0.36f - k * 0.08f)
        val left = Path().apply {
            moveTo(base.x, top)
            lineTo(base.x - halfW, bottom)
            quadraticTo(base.x - halfW * 0.5f, bottom + tierH * 0.1f, base.x, bottom)
            close()
        }
        val right = Path().apply {
            moveTo(base.x, top)
            lineTo(base.x + halfW, bottom)
            quadraticTo(base.x + halfW * 0.5f, bottom + tierH * 0.1f, base.x, bottom)
            close()
        }
        drawPath(left, fir)
        drawPath(right, shade)
        if (lights > 0f) {
            // A short swag of bulbs along the bottom of each tier.
            val bulbs = 3 + k
            for (b in 0 until bulbs) {
                val f = (b + 0.5f) / bulbs
                val x = base.x - halfW * 0.8f + halfW * 1.6f * f
                val y = bottom - tierH * 0.12f + sin(f * PI.toFloat()) * tierH * 0.06f
                val color = ChristmasLightColors[(b + k * 2) % ChristmasLightColors.size]
                val twinkle = 0.65f + 0.35f * sin(lightPhase + b * 1.7f + k * 2.3f)
                val a = lights * twinkle
                drawCircle(color.copy(alpha = 0.35f * a), height * 0.035f, Offset(x, y))
                drawCircle(color.copy(alpha = a), height * 0.017f, Offset(x, y))
            }
        }
    }
    drawStar(Offset(base.x, crownTop - height * 0.02f), height * 0.085f, star, glow = if (lights > 0f) lights * 0.8f else 0f)
}

/**
 * A wrapped gift, [width] wide and [height] tall, centered on [center]: a box in [wrap], a lid a touch wider,
 * a ribbon cross in [ribbon], and a bow. [lift] (0..1) raises the lid off the box, for the "opened" moment.
 */
fun DrawScope.drawGift(
    center: Offset,
    width: Float,
    height: Float = width,
    wrap: Color = ChristmasCranberry,
    ribbon: Color = ChristmasGold,
    lift: Float = 0f,
) {
    val lidH = height * 0.24f
    val boxTop = center.y - height / 2f + lidH
    val boxH = height - lidH
    val corner = CornerRadius(width * 0.06f)
    drawRoundRect(wrap, Offset(center.x - width * 0.44f, boxTop), Size(width * 0.88f, boxH), corner)
    // Shade the right side a little so it reads as a box rather than a flat tile.
    drawRect(Color.Black.copy(alpha = 0.12f), Offset(center.x + width * 0.14f, boxTop), Size(width * 0.3f, boxH))
    drawRect(ribbon, Offset(center.x - width * 0.07f, boxTop), Size(width * 0.14f, boxH))
    val lidY = boxTop - lidH - lift * height * 0.35f
    drawRoundRect(wrap, Offset(center.x - width / 2f, lidY), Size(width, lidH), corner)
    drawRect(Color.Black.copy(alpha = 0.1f), Offset(center.x + width * 0.17f, lidY), Size(width * 0.33f, lidH))
    drawRect(ribbon, Offset(center.x - width * 0.07f, lidY), Size(width * 0.14f, lidH))
    // The bow: two loops and a knot on top of the lid.
    val knot = Offset(center.x, lidY)
    for (side in listOf(-1f, 1f)) {
        val loop = Path().apply {
            moveTo(knot.x, knot.y)
            cubicTo(
                knot.x + side * width * 0.02f, knot.y - height * 0.34f,
                knot.x + side * width * 0.46f, knot.y - height * 0.3f,
                knot.x, knot.y,
            )
            close()
        }
        drawPath(loop, ribbon)
    }
    drawCircle(ribbon, width * 0.06f, knot)
}

/**
 * A glass ornament of [radius] at [center]: a ball in [color] with a gold cap and a soft highlight. Used for
 * the tree on Home, where each one stands for something done.
 */
fun DrawScope.drawOrnament(center: Offset, radius: Float, color: Color, shine: Float = 1f) {
    val capW = radius * 0.55f
    val capH = radius * 0.32f
    drawRoundRect(
        ChristmasGold,
        Offset(center.x - capW / 2f, center.y - radius - capH * 0.7f),
        Size(capW, capH),
        CornerRadius(capH * 0.25f),
    )
    drawCircle(color, radius, center)
    // Darker toward the lower right, like light from the upper left.
    drawCircle(
        Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)),
            center - Offset(radius * 0.35f, radius * 0.35f),
            radius * 1.7f,
        ),
        radius,
        center,
    )
    drawCircle(Color.White.copy(alpha = 0.55f * shine), radius * 0.22f, center - Offset(radius * 0.38f, radius * 0.38f))
}

/**
 * A small snowman standing on [base], [height] tall: two snowballs, coal eyes and buttons, a carrot nose and a
 * cranberry scarf. Drawn with a faint outline so it shows on a light background.
 */
fun DrawScope.drawSnowman(base: Offset, height: Float, snow: Color = ChristmasSnow, outline: Color = Color(0x22000000), tilt: Float = 0f) {
    rotate(tilt, pivot = base) {
        val bodyR = height * 0.28f
        val headR = height * 0.19f
        val body = Offset(base.x, base.y - bodyR)
        val head = Offset(base.x, body.y - bodyR - headR * 0.8f)
        drawCircle(outline, bodyR + height * 0.012f, body)
        drawCircle(snow, bodyR, body)
        drawCircle(outline, headR + height * 0.012f, head)
        drawCircle(snow, headR, head)
        val coal = Color(0xFF2B2A2E)
        drawCircle(coal, headR * 0.11f, head + Offset(-headR * 0.36f, -headR * 0.15f))
        drawCircle(coal, headR * 0.11f, head + Offset(headR * 0.36f, -headR * 0.15f))
        val nose = Path().apply {
            moveTo(head.x, head.y + headR * 0.02f)
            lineTo(head.x + headR * 0.75f, head.y + headR * 0.16f)
            lineTo(head.x, head.y + headR * 0.24f)
            close()
        }
        drawPath(nose, Color(0xFFF08A3C))
        for (i in 0 until 3) drawCircle(coal, bodyR * 0.08f, body + Offset(0f, -bodyR * 0.45f + i * bodyR * 0.4f))
        // The scarf: a band at the neck and one tail hanging down the side.
        val neckY = head.y + headR * 0.82f
        drawRoundRect(
            ChristmasCranberry,
            Offset(head.x - headR * 0.95f, neckY - headR * 0.16f),
            Size(headR * 1.9f, headR * 0.34f),
            CornerRadius(headR * 0.15f),
        )
        drawRoundRect(
            ChristmasCranberry,
            Offset(head.x + headR * 0.35f, neckY),
            Size(headR * 0.34f, headR * 0.95f),
            CornerRadius(headR * 0.12f),
        )
    }
}

/**
 * Rolling snowy hills across the bottom of this canvas, [rise] of its height at the tallest, in [color]: two
 * overlapping bands, the back one paler, for the Home backdrop and the Advent card.
 */
fun DrawScope.drawSnowHills(rise: Float, color: Color, back: Color = color.copy(alpha = color.alpha * 0.55f)) {
    val w = size.width
    val h = size.height
    val top = h - h * rise
    val far = Path().apply {
        moveTo(0f, top + h * rise * 0.35f)
        cubicTo(w * 0.25f, top - h * rise * 0.05f, w * 0.45f, top + h * rise * 0.45f, w * 0.7f, top + h * rise * 0.15f)
        cubicTo(w * 0.85f, top - h * rise * 0.02f, w * 0.95f, top + h * rise * 0.2f, w, top + h * rise * 0.1f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(far, back)
    val near = Path().apply {
        moveTo(0f, top + h * rise * 0.65f)
        cubicTo(w * 0.3f, top + h * rise * 0.35f, w * 0.55f, top + h * rise * 0.85f, w, top + h * rise * 0.55f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(near, color)
}

/** One bulb of a string of lights: a socket and a glowing glass drop pointing along [angle] degrees. */
fun DrawScope.drawBulb(at: Offset, size: Float, color: Color, glow: Float = 1f, angle: Float = 90f) {
    rotate(angle - 90f, pivot = at) {
        drawRect(Color(0xFF3B4A40), Offset(at.x - size * 0.22f, at.y), Size(size * 0.44f, size * 0.3f))
        val c = Offset(at.x, at.y + size * 0.72f)
        if (glow > 0f) drawCircle(color.copy(alpha = 0.28f * glow), size * 0.95f, c)
        drawOval(color, Offset(c.x - size * 0.32f, c.y - size * 0.45f), Size(size * 0.64f, size * 0.9f))
    }
}
