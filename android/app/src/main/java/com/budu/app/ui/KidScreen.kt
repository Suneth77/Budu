package com.budu.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.budu.app.UiState
import com.budu.app.face.BuduFace
import com.budu.app.face.Character

@Composable
fun KidScreen(
    ui: UiState,
    character: Character,
    pin: String,
    onFaceTap: () -> Unit,
    onMicGranted: () -> Unit,
    onPickFriend: (Character) -> Unit,
    onClosePicture: () -> Unit,
    onParentUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onMicGranted()
    }
    fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    // No mic button any more: ask for the microphone as soon as the screen opens.
    LaunchedEffect(Unit) {
        if (!micGranted()) askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    val haptic = LocalHapticFeedback.current
    val tapFace = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress) // instant "I felt that" buzz
        if (micGranted()) onFaceTap() else askMic.launch(Manifest.permission.RECORD_AUDIO)
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
                character = character,
                modifier = Modifier
                    .weight(1f, fill = false) // leaves room for the buttons below on any screen
                    .aspectRatio(1f)
                    .clickable(interactionSource = noRipple, indication = null) { tapFace() },
            )

            Spacer(Modifier.height(16.dp))

            // What's happening right now (fixed height, so nothing jumps around).
            Row(
                Modifier.height(96.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when {
                    ui.timeIsUp -> Text("💤", fontSize = 64.sp)
                    ui.isThinking -> Text("💭", fontSize = 56.sp)
                    ui.isListening -> ListeningIndicator(ready = ui.micReady)
                    !ui.conversationOn && !ui.isSpeaking -> TapMeHint()
                }
                if (ui.isDrawing) DrawingIndicator()
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

            Spacer(Modifier.height(20.dp))

            FriendPicker(
                selected = character,
                onPick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPickFriend(it)
                },
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
                        contentDescription = "${character.displayName}'s picture",
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

/**
 * Shown while the mic is open (always visible, so grown-ups can see when it's listening).
 *  orange = getting ready, red = listening now
 */
@Composable
private fun ListeningIndicator(ready: Boolean) {
    val pulse = rememberInfiniteTransition(label = "listen")
    val grow by pulse.animateFloat(
        initialValue = 1f, targetValue = if (ready) 1.15f else 1.04f,
        animationSpec = infiniteRepeatable(tween(if (ready) 400 else 900), RepeatMode.Reverse),
        label = "grow",
    )
    Box(
        Modifier
            .size(88.dp)
            .scale(grow)
            .clip(CircleShape)
            .background(if (ready) Color(0xFFEF5350) else Color(0xFFFFA726)),
        contentAlignment = Alignment.Center,
    ) {
        Text("👂", fontSize = 44.sp)
    }
}

/** Shown when the conversation has paused: a bouncing finger says "tap the face". */
@Composable
private fun TapMeHint() {
    val bounce = rememberInfiniteTransition(label = "tap")
    val dy by bounce.animateFloat(
        initialValue = 0f, targetValue = -14f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "dy",
    )
    Text("👆", fontSize = 56.sp, modifier = Modifier.offset(y = dy.dp))
}

/** A row of big round buttons, one per friend. Tap to switch anytime. */
@Composable
private fun FriendPicker(selected: Character, onPick: (Character) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Character.entries.forEach { c ->
            val isSelected = c == selected
            val grow by animateFloatAsState(if (isSelected) 1.15f else 1f, label = "pick")
            Box(
                Modifier
                    .size(64.dp)
                    .scale(grow)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.7f))
                    .then(if (isSelected) Modifier.border(3.dp, Color(0xFFFB8C00), CircleShape) else Modifier)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) {
                Text(c.emoji, fontSize = 34.sp)
            }
        }
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
