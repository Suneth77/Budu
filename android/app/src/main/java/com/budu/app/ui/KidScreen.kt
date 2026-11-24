package com.budu.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.budu.app.UiState
import com.budu.app.face.BuduFace

@Composable
fun KidScreen(
    ui: UiState,
    pin: String,
    onTalk: () -> Unit,
    onClosePicture: () -> Unit,
    onParentUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onTalk()
    }
    val talk = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onTalk() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    var showPin by remember { mutableStateOf(false) }
    val noRipple = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFFFF8E1), Color(0xFFFFE0B2))))
            .safeDrawingPadding(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BuduFace(
                emotion = ui.emotion,
                isTalking = ui.isSpeaking,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clickable(interactionSource = noRipple, indication = null) { talk() },
            )

            Spacer(Modifier.height(16.dp))

            when {
                ui.timeIsUp -> Text("💤", fontSize = 64.sp)
                ui.isThinking -> Text("💭", fontSize = 56.sp)
                ui.isDrawing -> DrawingIndicator()
                else -> TalkButton(listening = ui.isListening, onClick = talk)
            }

            Spacer(Modifier.height(16.dp))

            // Small caption, mostly for grown-ups watching along.
            Text(
                text = ui.caption,
                color = Color(0xFF6D4C41),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(0.8f),
            )
        }

        // Hidden parent door: long-press the faint lock, then enter the PIN.
        Text(
            text = "🔒",
            fontSize = 22.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .alpha(0.35f)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { showPin = true }) },
        )

        AnimatedVisibility(
            visible = ui.picture != null,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut(),
        ) {
            ui.picture?.let { bmp ->
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Budu's picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(32.dp)),
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(24.dp)
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { onClosePicture() },
                        contentAlignment = Alignment.Center,
                    ) { Text("✖", fontSize = 32.sp, color = Color(0xFF6D4C41)) }
                }
            }
        }
    }

    if (showPin) {
        PinDialog(
            correctPin = pin,
            onSuccess = { showPin = false; onParentUnlocked() },
            onDismiss = { showPin = false },
        )
    }
}

@Composable
private fun TalkButton(listening: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = if (listening) 1.15f else 1.04f,
        animationSpec = infiniteRepeatable(tween(if (listening) 400 else 1200), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        Modifier
            .size(112.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (listening) Color(0xFFEF5350) else Color(0xFF42A5F5))
            .clickable(enabled = !listening) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (listening) "👂" else "🎤", fontSize = 52.sp)
    }
}

@Composable
private fun DrawingIndicator() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(104.dp), strokeWidth = 8.dp, color = Color(0xFFAB47BC))
        Text("🎨", fontSize = 48.sp)
    }
}

@Composable
private fun PinDialog(correctPin: String, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grown-ups only") },
        text = {
            OutlinedTextField(
                value = entered,
                onValueChange = { entered = it.filter(Char::isDigit).take(8); wrong = false },
                label = { Text(if (wrong) "Wrong PIN" else "Enter PIN") },
                isError = wrong,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (entered == correctPin) onSuccess() else wrong = true }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
