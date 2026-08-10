package com.mediaplayer.plus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EQ_BAND_LABELS = listOf("60Hz", "250Hz", "1kHz", "4kHz", "16kHz")

@Composable
fun AudioEffectsView(
    onApply: (String) -> Unit = {},
    onPresetSelect: (Int) -> Unit = {},
    onBandLevelChange: (Int, Int) -> Unit = { _, _ -> },
    onReset: () -> Unit = {},
    currentPreset: Int = 0,
    presets: List<String> = listOf("默认"),
    bandLevels: List<Int> = List(5) { 0 },
    bandCount: Int = 5,
    levelMin: Int = -1500,
    levelMax: Int = 1500
) {
    val activeColor = Color(0xFF64B5F6)
    var showPresets by remember { mutableStateOf(false) }
    val availablePresets = if (presets.isNotEmpty()) presets else listOf("默认")
    val currentName = availablePresets.getOrNull(currentPreset) ?: "默认"

    // 预设弹窗返回手势
    androidx.activity.compose.BackHandler(enabled = showPresets) {
        showPresets = false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Equalizer, contentDescription = null, tint = activeColor, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "音效", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "原生均衡器 · 实时调节", color = Color.Gray.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(activeColor.copy(alpha = 0.12f))
                    .clickable { showPresets = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("预设", color = Color.Gray.copy(alpha = 0.6f), fontSize = 11.sp)
                    Text(text = currentName, color = activeColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Filled.Equalizer, contentDescription = null, tint = activeColor.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            }
        }

        // ===== 滑块已暂时禁用，仅保留预设选项 =====
        // item {
        //     Column(
        //         modifier = Modifier
        //             .fillMaxWidth()
        //             .padding(top = 20.dp)
        //             .clip(RoundedCornerShape(14.dp))
        //             .background(Color(0xFF1A1A24))
        //             .padding(20.dp)
        //     ) {
        //         Text(text = "均衡器", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 16.dp))
        //
        //         Row(
        //             modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        //             horizontalArrangement = Arrangement.SpaceBetween
        //         ) {
        //             val displayCount = minOf(bandCount, 5)
        //             repeat(displayCount) { bandIdx ->
        //                 BandColumn(
        //                     label = EQ_BAND_LABELS.getOrElse(bandIdx) { "—" },
        //                     level = bandLevels.getOrNull(bandIdx) ?: 0,
        //                     onLevelChange = { onBandLevelChange(bandIdx, it) },
        //                     color = activeColor,
        //                     levelMin = levelMin,
        //                     levelMax = levelMax
        //                 )
        //             }
        //         }
        //     }
        // }
        //
        // item {
        //     Spacer(modifier = Modifier.height(24.dp))
        //     OutlinedButton(
        //         onClick = onReset,
        //         modifier = Modifier.fillMaxWidth().height(40.dp),
        //         colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray, containerColor = Color.Transparent),
        //         border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)),
        //         shape = RoundedCornerShape(10.dp)
        //     ) {
        //         Text("重置均衡器", color = Color.Gray, fontSize = 13.sp)
        //     }
        //     Spacer(modifier = Modifier.height(16.dp))
        // }
    }

    // 预设弹窗（覆盖全屏半透明背景）
    if (showPresets) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { showPresets = false }
        ) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.85f)
                    .height(400.dp)
                    .padding(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2A)),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(4.dp)
                        .fillMaxSize()
                ) {
                    Text(
                        text = "选择预设",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    Spacer(modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.Gray.copy(alpha = 0.2f)))

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        availablePresets.forEachIndexed { index, name ->
                            item {
                                val isSelected = index == currentPreset
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onPresetSelect(index)
                                            onApply(name)
                                            showPresets = false
                                        }
                                        .background(if (isSelected) activeColor.copy(alpha = 0.15f) else Color.Transparent)
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = name, color = Color.White, fontSize = 15.sp)
                                    if (isSelected) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = activeColor, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun BandColumn(label: String, level: Int, onLevelChange: (Int) -> Unit, color: Color, levelMin: Int, levelMax: Int) {
    val fraction = ((level.toFloat() - levelMin) / (levelMax - levelMin)).coerceIn(0f, 1f)
    var displayLevel by remember { mutableIntStateOf(level) }

    LaunchedEffect(level) {
        displayLevel = level
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
        Text(
            text = if (displayLevel > 0) "+${displayLevel / 100}dB" else "${displayLevel / 100}dB",
            color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        VerticalEnergyBar(
            fraction = fraction,
            trackHeight = 140.dp,
            levelMin = levelMin,
            levelMax = levelMax,
            onLevelDisplay = { newLevel -> displayLevel = newLevel },
            onLevelChange = { newFraction ->
                val newLevel = (levelMin + (newFraction * (levelMax - levelMin))).toInt().coerceIn(levelMin, levelMax)
                onLevelChange(newLevel)
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, color = Color.Gray.copy(alpha = 0.6f), fontSize = 10.sp)
    }
}

@Composable
private fun VerticalEnergyBar(
    fraction: Float,
    trackHeight: Dp,
    levelMin: Int,
    levelMax: Int,
    onLevelDisplay: (Int) -> Unit,
    onLevelChange: (Float) -> Unit
) {
    val density = LocalDensity.current
    val heightPx = with(density) { trackHeight.toPx() }
    var animFraction by remember { mutableFloatStateOf(fraction) }

    fun updateDisplay() {
        val currentLevel = (levelMin + (animFraction * (levelMax - levelMin))).toInt().coerceIn(levelMin, levelMax)
        onLevelDisplay(currentLevel)
    }

    LaunchedEffect(fraction) {
        animFraction = fraction
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Gray.copy(alpha = 0.08f))
            .pointerInput(Unit) {
                var lastY = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        lastY = offset.y
                        // 触摸位置：y越小（越靠上）→ fraction越大 → 值越高
                        animFraction = (1f - (offset.y / heightPx)).coerceIn(0f, 1f)
                        updateDisplay()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val deltaY = change.position.y - lastY
                        lastY = change.position.y
                        // deltaY>0表示手指向下移动 → fraction减小 → 值降低
                        animFraction = (animFraction - deltaY / heightPx).coerceIn(0f, 1f)
                        updateDisplay()
                    },
                    onDragEnd = {
                        onLevelChange(animFraction)
                    },
                    onDragCancel = {
                        onLevelChange(animFraction)
                    }
                )
            }
    ) {
        val barWidth = with(density) { 6.dp.toPx() }
        val leftX = (size.width - barWidth) / 2
        val activeHeight = size.height * animFraction.coerceIn(0f, 1f)

        drawRoundRect(
            color = Color.White.copy(alpha = 0.15f),
            topLeft = Offset(leftX, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = CornerRadius(barWidth / 2)
        )

        if (activeHeight > 0f) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFF4081), Color(0xFF7C4DFF), Color(0xFF00E5FF))
                ),
                topLeft = Offset(leftX, size.height - activeHeight),
                size = Size(barWidth, activeHeight),
                cornerRadius = CornerRadius(barWidth / 2)
            )

            val glowR = with(density) { 8.dp.toPx() }
            val coreR = with(density) { 5.dp.toPx() }
            drawCircle(
                color = Color(0xFF00E5FF).copy(alpha = 0.5f),
                radius = glowR,
                center = Offset(size.width / 2, size.height - activeHeight)
            )
            drawCircle(
                color = Color.White,
                radius = coreR,
                center = Offset(size.width / 2, size.height - activeHeight)
            )
        }
    }
}