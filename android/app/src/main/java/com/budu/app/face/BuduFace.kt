package com.budu.app.face

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Budu and friends, drawn entirely in code (no image files needed).
 *
 * Every emotion is a set of numbers ([targetFor]); Compose animates smoothly between them.
 * Every character draws its own body, then uses the SAME animated eyes, brows and mouth
 * ([drawFeatures]) placed where they fit that character, so all of them can show all emotions.
 */
private data class FaceTarget(
    val eyeOpen: Float,     // 0 = closed, 1 = wide open
    val pupilScale: Float,  // bigger pupils look cuter / more attentive
    val lookX: Float,       // -1 left .. 1 right
    val lookY: Float,       // -1 up .. 1 down
    val browHeight: Float,  // -1 low .. 1 raised
    val browTilt: Float,    // + worried (inner ends up), - grumpy
    val smile: Float,       // -1 frown .. 1 big smile
    val mouthOpen: Float,   // 0 closed .. 1 wide open
    val mouthWidth: Float,  // 0.3 small "o" .. 1 wide
    val blush: Float,       // 0 .. 1
    val color: Color,       // mood color (Budu's body; a light tint on the others)
)

private fun targetFor(e: Emotion): FaceTarget = when (e) {
    Emotion.HAPPY -> FaceTarget(0.8f, 1.0f, 0f, 0f, 0.2f, 0f, 0.8f, 0.10f, 0.8f, 0.6f, Color(0xFFFFB74D))
    Emotion.EXCITED -> FaceTarget(1.0f, 1.15f, 0f, 0f, 0.6f, 0f, 1.0f, 0.50f, 0.9f, 0.9f, Color(0xFFFFD54F))
    Emotion.SURPRISED -> FaceTarget(1.0f, 0.7f, 0f, 0f, 1.0f, 0f, 0f, 0.80f, 0.35f, 0.3f, Color(0xFF81D4FA))
    Emotion.SAD -> FaceTarget(0.6f, 1.1f, 0f, 0.4f, -0.2f, 0.8f, -0.7f, 0f, 0.6f, 0f, Color(0xFF90A4AE))
    Emotion.CALM -> FaceTarget(0.55f, 1.0f, 0f, 0f, 0f, 0f, 0.4f, 0f, 0.6f, 0.3f, Color(0xFFA5D6A7))
    Emotion.CURIOUS -> FaceTarget(0.9f, 1.1f, 0.4f, -0.2f, 0.5f, -0.3f, 0.3f, 0.05f, 0.5f, 0.2f, Color(0xFFCE93D8))
    Emotion.LISTENING -> FaceTarget(1.0f, 1.2f, 0f, 0f, 0.4f, 0.2f, 0.3f, 0f, 0.5f, 0.4f, Color(0xFF80CBC4))
    Emotion.THINKING -> FaceTarget(0.75f, 1.0f, 0.6f, -0.6f, 0.3f, -0.2f, 0.1f, 0f, 0.4f, 0.1f, Color(0xFFB39DDB))
    Emotion.SLEEPY -> FaceTarget(0.05f, 1.0f, 0f, 0.3f, -0.3f, 0.3f, 0.2f, 0.15f, 0.4f, 0.4f, Color(0xFF9FA8DA))
}

/** The current animated values, handed to the drawing code. */
private class FaceState(
    val eyeOpen: Float, val pupilScale: Float, val lookX: Float, val lookY: Float,
    val browHeight: Float, val browTilt: Float, val smile: Float, val mouthOpen: Float,
    val mouthWidth: Float, val blush: Float, val mood: Color,
)

/** Where a character's eyes, brows, mouth and cheeks go. */
private class Features(
    val cx: Float, val eyeY: Float, val eyeDx: Float, val eyeW: Float, val eyeH: Float,
    val mouthY: Float, val mouthW: Float, val unit: Float,
    val cheekDx: Float, val cheekY: Float,
    val ink: Color = Color(0xFF4E342E),
    val mouthFill: Color = Color(0xFF6D2E3A),
)

private val SIDES = listOf(-1f, 1f)

@Composable
fun BuduFace(
    emotion: Emotion,
    isTalking: Boolean,
    modifier: Modifier = Modifier,
    character: Character = Character.BUDU,
) {
    val t = targetFor(emotion)
    val spec = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)

    val eyeOpen by animateFloatAsState(t.eyeOpen, spec, label = "eyeOpen")
    val pupilScale by animateFloatAsState(t.pupilScale, spec, label = "pupil")
    val lookX by animateFloatAsState(t.lookX, spec, label = "lookX")
    val lookY by animateFloatAsState(t.lookY, spec, label = "lookY")
    val browHeight by animateFloatAsState(t.browHeight, spec, label = "browH")
    val browTilt by animateFloatAsState(t.browTilt, spec, label = "browT")
    val smile by animateFloatAsState(t.smile, spec, label = "smile")
    val mouthOpen by animateFloatAsState(t.mouthOpen, spec, label = "mouthOpen")
    val mouthWidth by animateFloatAsState(t.mouthWidth, spec, label = "mouthW")
    val blush by animateFloatAsState(t.blush, spec, label = "blush")
    val color by animateColorAsState(t.color, tween(600), label = "color")

    // Natural blinking every few seconds (not while sleepy).
    val blink = remember { Animatable(1f) }
    LaunchedEffect(emotion) {
        blink.snapTo(1f)
        if (emotion == Emotion.SLEEPY) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(2_000, 5_000))
            blink.animateTo(0f, tween(80))
            blink.animateTo(1f, tween(120))
        }
    }

    // Gentle floating + talking mouth movement.
    val idle = rememberInfiniteTransition(label = "idle")
    val bob by idle.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val talk by idle.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(170), RepeatMode.Reverse),
        label = "talk",
    )
    val openNow = if (isTalking) max(mouthOpen, 0.2f + 0.5f * talk) else mouthOpen

    Canvas(modifier) {
        val s = min(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f - s * 0.03f + bob * s * 0.015f
        val f = FaceState(
            eyeOpen = eyeOpen * blink.value, pupilScale = pupilScale, lookX = lookX, lookY = lookY,
            browHeight = browHeight, browTilt = browTilt, smile = smile, mouthOpen = openNow,
            mouthWidth = mouthWidth, blush = blush, mood = color,
        )

        // Shadow on the "floor" (shrinks when the character floats up)
        val shadowW = s * 0.40f * (1.25f + 0.1f * bob)
        drawOval(
            Color.Black.copy(alpha = 0.08f),
            topLeft = Offset(cx - shadowW / 2f, size.height / 2f + s * 0.43f),
            size = Size(shadowW, s * 0.056f),
        )

        when (character) {
            Character.BUDU -> drawBudu(f, cx, cy, s * 0.40f)
            Character.DINO -> drawDino(f, cx, cy + s * 0.03f, s * 0.36f)
            Character.ROBOT -> drawRobot(f, cx, cy + s * 0.04f, s * 0.36f)
            Character.MONKEY -> drawMonkey(f, cx, cy, s * 0.36f)
            Character.CAR -> drawCar(f, cx, cy, s * 0.40f)
        }
    }
}

// ---------------------------------------------------------------- characters

private fun DrawScope.drawBudu(f: FaceState, cx: Float, cy: Float, r: Float) {
    drawCircle(f.mood, r, Offset(cx, cy))
    drawCircle(Color.White.copy(alpha = 0.25f), r * 0.22f, Offset(cx - r * 0.45f, cy - r * 0.52f))
    drawFeatures(
        f, Features(
            cx = cx, eyeY = cy - r * 0.12f, eyeDx = r * 0.36f, eyeW = r * 0.30f, eyeH = r * 0.38f,
            mouthY = cy + r * 0.35f, mouthW = r * 0.55f, unit = r,
            cheekDx = r * 0.57f, cheekY = cy + r * 0.185f,
        ),
    )
}

private fun DrawScope.drawDino(f: FaceState, cx: Float, cy: Float, r: Float) {
    val skin = lerp(Color(0xFF81C784), f.mood, 0.2f)
    val spikeColor = Color(0xFF43A047)
    fun pt(angleDeg: Float, radius: Float): Offset {
        val a = Math.toRadians(angleDeg.toDouble())
        return Offset(cx + (cos(a) * radius).toFloat(), cy + (sin(a) * radius).toFloat())
    }
    // Back spikes peeking over the head
    for (deg in listOf(-140f, -115f, -90f, -65f, -40f)) {
        val spike = Path().apply {
            val p1 = pt(deg - 10f, r * 0.85f)
            val tip = pt(deg, r * 1.28f)
            val p2 = pt(deg + 10f, r * 0.85f)
            moveTo(p1.x, p1.y)
            lineTo(tip.x, tip.y)
            lineTo(p2.x, p2.y)
            close()
        }
        drawPath(spike, spikeColor)
        drawPath(spike, spikeColor, style = Stroke(width = r * 0.08f, join = StrokeJoin.Round))
    }
    // Head
    drawOval(skin, Offset(cx - r * 1.05f, cy - r * 0.95f), Size(r * 2.1f, r * 1.9f))
    drawCircle(Color.White.copy(alpha = 0.22f), r * 0.18f, Offset(cx - r * 0.5f, cy - r * 0.55f))
    // Spots
    val spot = Color(0xFF66BB6A)
    drawCircle(spot, r * 0.08f, Offset(cx + r * 0.62f, cy - r * 0.48f))
    drawCircle(spot, r * 0.05f, Offset(cx + r * 0.78f, cy - r * 0.26f))
    drawCircle(spot, r * 0.06f, Offset(cx - r * 0.72f, cy - r * 0.38f))
    // Snout with nostrils
    drawOval(Color(0xFFC5E1A5), Offset(cx - r * 0.6f, cy + r * 0.1f), Size(r * 1.2f, r * 0.74f))
    val nostril = Color(0xFF33691E)
    drawOval(nostril, Offset(cx - r * 0.2f, cy + r * 0.18f), Size(r * 0.08f, r * 0.05f))
    drawOval(nostril, Offset(cx + r * 0.12f, cy + r * 0.18f), Size(r * 0.08f, r * 0.05f))
    drawFeatures(
        f, Features(
            cx = cx, eyeY = cy - r * 0.24f, eyeDx = r * 0.38f, eyeW = r * 0.32f, eyeH = r * 0.40f,
            mouthY = cy + r * 0.42f, mouthW = r * 0.6f, unit = r,
            cheekDx = r * 0.74f, cheekY = cy + r * 0.12f,
            ink = Color(0xFF1B5E20),
        ),
    )
}

private fun DrawScope.drawRobot(f: FaceState, cx: Float, cy: Float, r: Float) {
    val metal = lerp(Color(0xFF90A4AE), f.mood, 0.25f)
    val dark = Color(0xFF546E7A)
    // Antenna with a light that glows in the mood color
    drawLine(dark, Offset(cx, cy - r * 0.8f), Offset(cx, cy - r * 1.12f), strokeWidth = r * 0.06f, cap = StrokeCap.Round)
    drawCircle(f.mood.copy(alpha = 0.35f), r * 0.2f, Offset(cx, cy - r * 1.18f))
    drawCircle(f.mood, r * 0.11f, Offset(cx, cy - r * 1.18f))
    // Ear bolts
    for (side in SIDES) {
        drawRoundRect(
            dark, Offset(cx + side * r * 0.95f - r * 0.12f, cy - r * 0.25f), Size(r * 0.24f, r * 0.5f),
            CornerRadius(r * 0.08f),
        )
    }
    // Head + rivets
    drawRoundRect(metal, Offset(cx - r * 0.95f, cy - r * 0.85f), Size(r * 1.9f, r * 1.7f), CornerRadius(r * 0.32f))
    for (sx in SIDES) for (sy in SIDES) drawCircle(dark, r * 0.04f, Offset(cx + sx * r * 0.82f, cy + sy * r * 0.72f))
    // Face screen
    drawRoundRect(
        Color(0xFF263238), Offset(cx - r * 0.75f, cy - r * 0.62f), Size(r * 1.5f, r * 1.2f),
        CornerRadius(r * 0.22f),
    )
    drawFeatures(
        f, Features(
            cx = cx, eyeY = cy - r * 0.2f, eyeDx = r * 0.34f, eyeW = r * 0.28f, eyeH = r * 0.34f,
            mouthY = cy + r * 0.2f, mouthW = r * 0.5f, unit = r * 0.8f,
            cheekDx = r * 0.55f, cheekY = cy + r * 0.08f,
            ink = Color(0xFF4DD0E1), mouthFill = Color(0xFF00838F),
        ),
    )
}

private fun DrawScope.drawMonkey(f: FaceState, cx: Float, cy: Float, r: Float) {
    val fur = lerp(Color(0xFF8D6E63), f.mood, 0.15f)
    val face = Color(0xFFFFE0B2)
    // Ears
    for (side in SIDES) {
        val c = Offset(cx + side * r * 0.98f, cy - r * 0.05f)
        drawCircle(fur, r * 0.32f, c)
        drawCircle(Color(0xFFFFCCBC), r * 0.19f, c)
    }
    // Head + little hair tuft
    drawCircle(fur, r, Offset(cx, cy))
    drawCircle(fur, r * 0.13f, Offset(cx - r * 0.12f, cy - r * 0.97f))
    drawCircle(fur, r * 0.15f, Offset(cx, cy - r * 1.02f))
    drawCircle(fur, r * 0.13f, Offset(cx + r * 0.12f, cy - r * 0.97f))
    // Light face area: two eye patches + muzzle
    drawCircle(face, r * 0.36f, Offset(cx - r * 0.3f, cy - r * 0.15f))
    drawCircle(face, r * 0.36f, Offset(cx + r * 0.3f, cy - r * 0.15f))
    drawOval(face, Offset(cx - r * 0.62f, cy - r * 0.02f), Size(r * 1.24f, r * 0.84f))
    val nose = Color(0xFF5D4037)
    drawCircle(nose, r * 0.035f, Offset(cx - r * 0.08f, cy + r * 0.13f))
    drawCircle(nose, r * 0.035f, Offset(cx + r * 0.08f, cy + r * 0.13f))
    drawFeatures(
        f, Features(
            cx = cx, eyeY = cy - r * 0.16f, eyeDx = r * 0.3f, eyeW = r * 0.26f, eyeH = r * 0.34f,
            mouthY = cy + r * 0.38f, mouthW = r * 0.55f, unit = r * 0.9f,
            cheekDx = r * 0.48f, cheekY = cy + r * 0.2f,
            ink = Color(0xFF3E2723),
        ),
    )
}

private fun DrawScope.drawCar(f: FaceState, cx: Float, cy: Float, r: Float) {
    val paint = lerp(Color(0xFFEF5350), f.mood, 0.2f)
    // Wheels
    for (side in SIDES) {
        drawRoundRect(
            Color(0xFF37474F), Offset(cx + side * r * 0.66f - r * 0.19f, cy + r * 0.38f), Size(r * 0.38f, r * 0.5f),
            CornerRadius(r * 0.12f),
        )
    }
    // Cabin + window with a shine
    drawRoundRect(paint, Offset(cx - r * 0.62f, cy - r * 0.95f), Size(r * 1.24f, r * 0.8f), CornerRadius(r * 0.28f))
    drawRoundRect(
        Color(0xFFB3E5FC), Offset(cx - r * 0.5f, cy - r * 0.84f), Size(r * 1.0f, r * 0.48f),
        CornerRadius(r * 0.16f),
    )
    drawLine(
        Color.White.copy(alpha = 0.7f), Offset(cx - r * 0.28f, cy - r * 0.78f), Offset(cx - r * 0.4f, cy - r * 0.46f),
        strokeWidth = r * 0.05f, cap = StrokeCap.Round,
    )
    // Body
    drawRoundRect(paint, Offset(cx - r * 1.0f, cy - r * 0.38f), Size(r * 2.0f, r * 0.95f), CornerRadius(r * 0.36f))
    drawCircle(Color.White.copy(alpha = 0.2f), r * 0.1f, Offset(cx - r * 0.82f, cy - r * 0.2f))
    // Headlights: the eyes sit inside them
    for (side in SIDES) drawCircle(Color(0xFFFFF59D), r * 0.29f, Offset(cx + side * r * 0.5f, cy - r * 0.05f))
    // Bumper
    drawRoundRect(
        Color(0xFFB0BEC5), Offset(cx - r * 0.92f, cy + r * 0.47f), Size(r * 1.84f, r * 0.14f),
        CornerRadius(r * 0.07f),
    )
    drawFeatures(
        f, Features(
            cx = cx, eyeY = cy - r * 0.05f, eyeDx = r * 0.5f, eyeW = r * 0.38f, eyeH = r * 0.42f,
            mouthY = cy + r * 0.24f, mouthW = r * 0.6f, unit = r * 0.75f,
            cheekDx = r * 0.84f, cheekY = cy + r * 0.24f,
        ),
    )
}

// ---------------------------------------------------------------- shared face

private fun DrawScope.drawFeatures(f: FaceState, p: Features) {
    val u = p.unit

    // Cheeks
    if (f.blush > 0.01f) {
        val cheek = Color(0xFFFF6F91).copy(alpha = 0.35f * f.blush.coerceIn(0f, 1f))
        val cw = u * 0.30f
        val ch = u * 0.17f
        for (side in SIDES) drawOval(cheek, Offset(p.cx + side * p.cheekDx - cw / 2f, p.cheekY - ch / 2f), Size(cw, ch))
    }

    // Eyes + brows
    val eyeH = p.eyeH * f.eyeOpen.coerceIn(0f, 1.2f)
    for (side in SIDES) {
        val ex = p.cx + side * p.eyeDx
        if (eyeH > u * 0.07f) {
            val eyeRect = Rect(Offset(ex - p.eyeW / 2f, p.eyeY - eyeH / 2f), Size(p.eyeW, eyeH))
            drawOval(Color.White, eyeRect.topLeft, eyeRect.size)
            val pr = p.eyeW * 0.28f * f.pupilScale
            val px = ex + f.lookX * p.eyeW * 0.2f
            val py = p.eyeY + f.lookY * eyeH * 0.2f
            clipPath(Path().apply { addOval(eyeRect) }) {
                drawCircle(Color(0xFF2D2A32), pr, Offset(px, py))
                drawCircle(Color.White, pr * 0.35f, Offset(px - pr * 0.35f, py - pr * 0.35f))
            }
        } else {
            // Closed eye: a soft curved line
            val closed = Path().apply {
                moveTo(ex - p.eyeW / 2f, p.eyeY)
                quadraticBezierTo(ex, p.eyeY + u * 0.06f, ex + p.eyeW / 2f, p.eyeY)
            }
            drawPath(closed, p.ink, style = Stroke(width = u * 0.04f, cap = StrokeCap.Round))
        }

        val by = p.eyeY - p.eyeH * 0.79f - f.browHeight * u * 0.08f
        val inner = Offset(p.cx + side * (p.eyeDx - p.eyeW * 0.55f), by - f.browTilt * u * 0.07f)
        val outer = Offset(p.cx + side * (p.eyeDx + p.eyeW * 0.55f), by + f.browTilt * u * 0.07f)
        drawLine(p.ink, inner, outer, strokeWidth = u * 0.05f, cap = StrokeCap.Round)
    }

    // Mouth
    val mw = p.mouthW * f.mouthWidth
    val my = p.mouthY
    val curve = f.smile * u * 0.22f
    val open = f.mouthOpen * u * 0.35f
    if (open < u * 0.02f) {
        val m = Path().apply {
            moveTo(p.cx - mw / 2f, my)
            quadraticBezierTo(p.cx, my + curve, p.cx + mw / 2f, my)
        }
        drawPath(m, p.ink, style = Stroke(width = u * 0.05f, cap = StrokeCap.Round))
    } else {
        val m = Path().apply {
            moveTo(p.cx - mw / 2f, my)
            quadraticBezierTo(p.cx, my + curve * 0.5f - open * 0.15f, p.cx + mw / 2f, my) // upper lip
            quadraticBezierTo(p.cx, my + curve + open, p.cx - mw / 2f, my)               // lower lip
            close()
        }
        drawPath(m, p.mouthFill)
        clipPath(m) {
            drawCircle(Color(0xFFFF8A80), u * 0.15f, Offset(p.cx, my + curve * 0.75f + open))
        }
        drawPath(m, p.ink, style = Stroke(width = u * 0.035f, join = StrokeJoin.Round))
    }
}
