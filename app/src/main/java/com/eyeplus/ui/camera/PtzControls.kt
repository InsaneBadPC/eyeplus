package com.eyeplus.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/**
 * Virtual joystick for PTZ camera control.
 * Supports 8 directional movement plus stop center.
 */
@Composable
fun PtzJoystick(
    modifier: Modifier = Modifier,
    isMoving: Boolean = false,
    onMove: (Float, Float) -> Unit,  // panX (-1..1), tiltY (-1..1)
    onStop: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .size(200.dp)
            .clip(CircleShape)
            .background(surfaceColor.copy(alpha = 0.5f))
            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragOffset = Offset(
                            (offset.x - size.width / 2) / (size.width / 2),
                            (offset.y - size.height / 2) / (size.height / 2)
                        ).coerceIn(-1f, 1f)
                        onMove(dragOffset.x, dragOffset.y)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragOffset = Offset(
                            (change.position.x - size.width / 2) / (size.width / 2),
                            (change.position.y - size.height / 2) / (size.height / 2)
                        ).coerceIn(-1f, 1f)
                        onMove(dragOffset.x, dragOffset.y)
                    },
                    onDragEnd = {
                        isDragging = false
                        dragOffset = Offset.Zero
                        onStop()
                    },
                    onDragCancel = {
                        isDragging = false
                        dragOffset = Offset.Zero
                        onStop()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Crosshair guides
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val crossColor = primaryColor.copy(alpha = 0.3f)
            val dashLen = 8f

            // Horizontal crosshair
            drawLine(
                color = crossColor,
                start = Offset(centerX - 80f, centerY),
                end = Offset(centerX + 80f, centerY),
                strokeWidth = 1f
            )
            // Vertical crosshair
            drawLine(
                color = crossColor,
                start = Offset(centerX, centerY - 80f),
                end = Offset(centerX, centerY + 80f),
                strokeWidth = 1f
            )
            // Outer ring
            drawCircle(
                color = crossColor,
                radius = 80f,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1f)
            )
        }

        // Joystick knob
        val knobOffsetX = dragOffset.x * 60f
        val knobOffsetY = dragOffset.y * 60f

        Box(
            modifier = Modifier
                .offset(
                    x = with(androidx.compose.ui.unit.Dp) { knobOffsetX.dp },
                    y = with(androidx.compose.ui.unit.Dp) { knobOffsetY.dp }
                )
                .size(if (isDragging) 56.dp else 48.dp)
                .clip(CircleShape)
                .background(
                    if (isDragging) primaryColor else primaryColor.copy(alpha = 0.7f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!isDragging) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = "PTZ",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Alternative D-pad style PTZ controls for when joystick
 * is impractical (voice users, accessibility).
 */
@Composable
fun PtzDPad(
    modifier: Modifier = Modifier,
    onMove: (String) -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onMove("UP_LEFT") },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.ArrowBack, "Vlevo nahoru", modifier = Modifier.size(18.dp))
            }
            FilledIconButton(
                onClick = { onMove("UP") },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.ArrowUpward, "Nahoru")
            }
            IconButton(
                onClick = { onMove("UP_RIGHT") },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.ArrowForward, "Vpravo nahoru", modifier = Modifier.size(18.dp))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { onMove("LEFT") },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.ArrowBack, "Doleva")
            }
            FilledTonalIconButton(
                onClick = onStop,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Stop, "Stop")
            }
            FilledIconButton(
                onClick = { onMove("RIGHT") },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.ArrowForward, "Doprava")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onMove("DOWN_LEFT") },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.ArrowBack, "Doleva dolů", modifier = Modifier.size(18.dp))
            }
            FilledIconButton(
                onClick = { onMove("DOWN") },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, "Dolů")
            }
            IconButton(
                onClick = { onMove("DOWN_RIGHT") },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.ArrowForward, "Doprava dolů", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Tab selector for PTZ control mode (Joystick vs D-Pad).
 */
@Composable
fun PtzControlModeSelector(
    selectedMode: PtzMode,
    onModeSelected: (PtzMode) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selectedMode == PtzMode.JOYSTICK,
            onClick = { onModeSelected(PtzMode.JOYSTICK) },
            label = { Text("Joystick") },
            leadingIcon = {
                Icon(Icons.Default.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )
        FilterChip(
            selected = selectedMode == PtzMode.DPAD,
            onClick = { onModeSelected(PtzMode.DPAD) },
            label = { Text("D-Pad") },
            leadingIcon = {
                Icon(Icons.Default.GridOn, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )
    }
}

enum class PtzMode {
    JOYSTICK, DPAD
}
