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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Budu's face, drawn entirely in code (no image files needed).
 * Every emotion is a set of numbers; Compose smoothly animates between them.
 * Tweak the numbers in [targetFor] to change how each emotion looks.
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
    val color: Color,
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

@Composable
fun BuduFace(
    emotion: Emotion,
    isTalking: Boolean,
    modifier: Modifier = Modifier,
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
        val r = s * 0.40f
        val cx = size.width / 2f
        val cy = size.height / 2f - s * 0.03f + bob * s * 0.015f
        val ink = Color(0xFF4E342E)

        // Shadow on the "floor" (shrinks when Budu floats up)
        val shadowW = r * (1.25f + 0.1f * bob)
        drawOval(
            Color.Black.copy(alpha = 0.08f),
            topLeft = Offset(cx - shadowW / 2f, size.height / 2f + r * 1.08f),
            size = Size(shadowW, r * 0.14f),
        )

        // Body + shine
        drawCircle(color, r, Offset(cx, cy))
        drawCircle(Color.White.copy(alpha = 0.25f), r * 0.22f, Offset(cx - r * 0.45f, cy - r * 0.52f))

        // Cheeks
        if (blush > 0.01f) {
            val cheek = Color(0xFFFF6F91).copy(alpha = 0.35f * blush.coerceIn(0f, 1f))
            val cw = r * 0.30f
            val ch = r * 0.17f
            drawOval(cheek, Offset(cx - r * 0.72f, cy + r * 0.10f), Size(cw, ch))
            drawOval(cheek, Offset(cx + r * 0.42f, cy + r * 0.10f), Size(cw, ch))
        }

        // Eyes + brows
        val eyeDx = r * 0.36f
        val eyeY = cy - r * 0.12f
        val eyeW = r * 0.30f
        val eyeH = r * 0.38f * (eyeOpen * blink.value).coerceIn(0f, 1.2f)

        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * eyeDx
            if (eyeH > r * 0.07f) {
                val eyeRect = Rect(Offset(ex - eyeW / 2f, eyeY - eyeH / 2f), Size(eyeW, eyeH))
                drawOval(Color.White, eyeRect.topLeft, eyeRect.size)
                val pr = eyeW * 0.28f * pupilScale
                val px = ex + lookX * eyeW * 0.2f
                val py = eyeY + lookY * eyeH * 0.2f
                clipPath(Path().apply { addOval(eyeRect) }) {
                    drawCircle(Color(0xFF2D2A32), pr, Offset(px, py))
                    drawCircle(Color.White, pr * 0.35f, Offset(px - pr * 0.35f, py - pr * 0.35f))
                }
            } else {
                // Closed eye: a soft curved line
                val closed = Path().apply {
                    moveTo(ex - eyeW / 2f, eyeY)
                    quadraticBezierTo(ex, eyeY + r * 0.06f, ex + eyeW / 2f, eyeY)
                }
                drawPath(closed, ink, style = Stroke(width = r * 0.04f, cap = StrokeCap.Round))
            }

            val by = eyeY - r * 0.30f - browHeight * r * 0.08f
            val inner = Offset(cx + side * (eyeDx - eyeW * 0.55f), by - browTilt * r * 0.07f)
            val outer = Offset(cx + side * (eyeDx + eyeW * 0.55f), by + browTilt * r * 0.07f)
            drawLine(ink, inner, outer, strokeWidth = r * 0.05f, cap = StrokeCap.Round)
        }

        // Mouth
        val mw = r * 0.55f * mouthWidth
        val my = cy + r * 0.35f
        val curve = smile * r * 0.22f
        val open = openNow * r * 0.35f
        if (open < r * 0.02f) {
            val p = Path().apply {
                moveTo(cx - mw / 2f, my)
                quadraticBezierTo(cx, my + curve, cx + mw / 2f, my)
            }
            drawPath(p, ink, style = Stroke(width = r * 0.05f, cap = StrokeCap.Round))
        } else {
            val p = Path().apply {
                moveTo(cx - mw / 2f, my)
                quadraticBezierTo(cx, my + curve * 0.5f - open * 0.15f, cx + mw / 2f, my) // upper lip
                quadraticBezierTo(cx, my + curve + open, cx - mw / 2f, my)               // lower lip
                close()
            }
            drawPath(p, Color(0xFF6D2E3A))
            clipPath(p) {
                drawCircle(Color(0xFFFF8A80), r * 0.15f, Offset(cx, my + curve * 0.75f + open))
            }
            drawPath(p, ink, style = Stroke(width = r * 0.035f, join = StrokeJoin.Round))
        }
    }
}
