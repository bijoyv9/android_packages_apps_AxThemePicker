/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.themepicker.ui.lockscreen

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.axion.themepicker.R
import com.android.axion.themepicker.ui.components.CommonBottomSheet
import com.android.axion.themepicker.ui.components.SheetDimens
import com.android.axion.themepicker.ui.dialogs.ColorPickerDialog
import com.android.systemui.shared.clocks.AxClockType
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.view.AxClockView
import com.android.systemui.shared.clocks.view.BitmapDigitComposeClockView
import com.android.systemui.shared.clocks.view.BitmapFaceConfigs
import com.android.systemui.shared.clocks.view.RenderMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val TileCorner = 20.dp
private val TileBorder = 2.dp
private val StyleTileWidth = 150.dp
private val StyleTileHeight = 100.dp
private val FaceTileWidth = 200.dp
private val FaceTileHeight = 150.dp
private const val STYLE_PREVIEW_SCALE = 0.35f
private const val FACE_PREVIEW_SCALE = 0.45f
private const val DEPTH_SETTINGS_KEY = "ax_depth_clock_enabled"
private const val DEPTH_ON = "on"
private const val DEPTH_OFF = "off"

@Composable
fun ClockFaceSheet(visible: Boolean, heightFraction: Float = 0.65f, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allTypes = remember { AxClockType.entries }
    var currentClockId by remember { mutableStateOf("DEFAULT") }
    var currentAlignment by remember { mutableStateOf(ClockSettingsRepository.ALIGNMENT_CENTER) }
    var currentSize by remember { mutableStateOf(ClockSettingsRepository.SIZE_DEFAULT) }
    var depthEnabled by remember { mutableStateOf(false) }
    var currentDatePosition by remember {
        mutableStateOf(ClockSettingsRepository.DATE_POSITION_ABOVE)
    }
    var currentClockColor by remember { mutableStateOf(ClockSettingsRepository.COLOR_AUTO) }
    var showColorPicker by remember { mutableStateOf(false) }
    var isLiveWallpaper by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val id = readCurrentClockId(context)
            val align = readAlignment(context)
            val size = readSize(context)
            val depth = readDepthEnabled(context)
            val datePos = readDatePosition(context)
            val clockColor = readClockColor(context)
            val liveWp = WallpaperManager.getInstance(context).wallpaperInfo != null
            withContext(Dispatchers.Main) {
                currentClockId = id
                currentAlignment = align
                currentSize = size
                depthEnabled = depth
                currentDatePosition = datePos
                currentClockColor = clockColor
                isLiveWallpaper = liveWp
            }
        }
    }

    val selectedType =
        remember(currentClockId) {
            allTypes.firstOrNull { context.resources.getString(it.clockId) == currentClockId }
                ?: AxClockType.NTYPE
        }

    val isDigitFamily =
        remember(selectedType) {
            val style = selectedType.bitmapFaceStyle ?: return@remember false
            val config = BitmapFaceConfigs.getConfig(style) ?: return@remember false
            config.renderMode !is RenderMode.AnalogClock
        }
    val hasDateSupport = selectedType.bitmapFaceStyle != null
    val supportsColorOverride = selectedType != AxClockType.CYBERPUNK
    val digitFaceTypes = remember {
        allTypes.filter { type ->
            val style = type.bitmapFaceStyle ?: return@filter false
            val config = BitmapFaceConfigs.getConfig(style) ?: return@filter false
            config.renderMode !is RenderMode.AnalogClock
        }
    }
    val primaryTypes = remember {
        buildList {
            add(AxClockType.NTYPE)
            addAll(allTypes.filter { it.bitmapFaceStyle == null || it == AxClockType.GRAPHIC })
        }
    }

    fun writeClockId(type: AxClockType) {
        val clockId = context.resources.getString(type.clockId)
        currentClockId = clockId
        val json =
            JSONObject()
                .apply {
                    put("clockId", clockId)
                    put(
                        "metadata",
                        JSONObject().apply { put("appliedTimestamp", System.currentTimeMillis()) },
                    )
                    put("axes", JSONArray())
                }
                .toString()
        scope.launch {
            Settings.Secure.putString(
                context.contentResolver,
                ClockSettingsRepository.SETTING_CLOCK_FACE,
                json,
            )
        }
    }

    fun writeAlignment(value: String) {
        currentAlignment = value
        scope.launch {
            Settings.Secure.putString(
                context.contentResolver,
                ClockSettingsRepository.SETTING_ALIGNMENT,
                value,
            )
        }
    }

    fun writeSize(value: String) {
        currentSize = value
        scope.launch {
            Settings.Secure.putString(
                context.contentResolver,
                ClockSettingsRepository.SETTING_SIZE,
                value,
            )
        }
    }

    fun writeDepth(enabled: Boolean) {
        depthEnabled = enabled
        scope.launch {
            Settings.Secure.putInt(
                context.contentResolver,
                DEPTH_SETTINGS_KEY,
                if (enabled) 1 else 0,
            )
        }
    }

    fun writeDatePosition(value: String) {
        currentDatePosition = value
        scope.launch {
            Settings.Secure.putString(
                context.contentResolver,
                ClockSettingsRepository.SETTING_DATE_POSITION,
                value,
            )
        }
    }

    fun writeClockColor(value: String) {
        currentClockColor = value
        scope.launch {
            Settings.Secure.putString(
                context.contentResolver,
                ClockSettingsRepository.SETTING_CLOCK_COLOR,
                value,
            )
        }
    }

    CommonBottomSheet(
        visible = visible,
        title = stringResource(R.string.clock_face),
        heightFraction = heightFraction,
        surfaceColor = MaterialTheme.colorScheme.surfaceContainer,
        scrimAlpha = 0f,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SheetDimens.SheetPagerPadding)
        ) {
            SectionTitle(stringResource(R.string.clock_style))
            Spacer(modifier = Modifier.height(8.dp))
            ClockStyleGrid(
                context = context,
                primaryTypes = primaryTypes,
                selectedType = selectedType,
                tileWidth = StyleTileWidth,
                tileHeight = StyleTileHeight,
                previewScale = STYLE_PREVIEW_SCALE,
                settingsKey = "$currentAlignment:$currentSize:$currentClockColor",
                onSelect = { writeClockId(it) },
                isSelectedOverride = { type ->
                    if (type == AxClockType.NTYPE) isDigitFamily else type == selectedType
                },
            )

            if (isDigitFamily && digitFaceTypes.size > 1) {
                Spacer(modifier = Modifier.height(20.dp))
                SectionTitle(stringResource(R.string.clock_face_style))
                Spacer(modifier = Modifier.height(8.dp))
                ClockStyleGrid(
                    context = context,
                    primaryTypes = digitFaceTypes,
                    selectedType = selectedType,
                    tileWidth = FaceTileWidth,
                    tileHeight = FaceTileHeight,
                    previewScale = FACE_PREVIEW_SCALE,
                    settingsKey = "$currentAlignment:$currentSize:$currentClockColor",
                    onSelect = { writeClockId(it) },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.clock_size))
            Spacer(modifier = Modifier.height(8.dp))
            OptionRow(
                options =
                    listOf(
                        OptionItem(
                            ClockSettingsRepository.SIZE_DEFAULT,
                            stringResource(R.string.clock_size_default),
                        ) {
                            SizeDefaultIcon(it)
                        },
                        OptionItem(
                            ClockSettingsRepository.SIZE_LARGE,
                            stringResource(R.string.clock_size_large),
                        ) {
                            SizeLargeIcon(it)
                        },
                    ),
                selected = currentSize,
                onSelect = { writeSize(it) },
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.clock_alignment))
            Spacer(modifier = Modifier.height(8.dp))
            OptionRow(
                options =
                    listOf(
                        OptionItem(
                            ClockSettingsRepository.ALIGNMENT_LEFT,
                            stringResource(R.string.clock_align_left),
                        ) {
                            AlignLeftIcon(it)
                        },
                        OptionItem(
                            ClockSettingsRepository.ALIGNMENT_CENTER,
                            stringResource(R.string.clock_align_center),
                        ) {
                            AlignCenterIcon(it)
                        },
                        OptionItem(
                            ClockSettingsRepository.ALIGNMENT_RIGHT,
                            stringResource(R.string.clock_align_right),
                        ) {
                            AlignRightIcon(it)
                        },
                    ),
                selected = currentAlignment,
                onSelect = { writeAlignment(it) },
            )

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        ClockSettingsRepository.saveHeightOffset(context, 0f)
                    }
                }
            ) {
                Text(stringResource(R.string.reset_to_default))
            }

            if (hasDateSupport) {
                Spacer(modifier = Modifier.height(20.dp))
                SectionTitle(stringResource(R.string.clock_date_position))
                Spacer(modifier = Modifier.height(8.dp))
                OptionRow(
                    options =
                        listOf(
                            OptionItem(
                                ClockSettingsRepository.DATE_POSITION_ABOVE,
                                stringResource(R.string.clock_date_above),
                            ) {
                                DateAboveIcon(it)
                            },
                            OptionItem(
                                ClockSettingsRepository.DATE_POSITION_BELOW,
                                stringResource(R.string.clock_date_below),
                            ) {
                                DateBelowIcon(it)
                            },
                        ),
                    selected = currentDatePosition,
                    onSelect = { writeDatePosition(it) },
                )
            }

            if (supportsColorOverride) {
                Spacer(modifier = Modifier.height(20.dp))
                SectionTitle(stringResource(R.string.clock_color))
                Spacer(modifier = Modifier.height(8.dp))
                ClockColorRow(
                    selected = currentClockColor,
                    onSelect = { writeClockColor(it) },
                    onCustom = { showColorPicker = true },
                )
            }

            if (!isLiveWallpaper) {
                Spacer(modifier = Modifier.height(20.dp))
                SectionTitle(stringResource(R.string.depth_effect))
                Spacer(modifier = Modifier.height(8.dp))
                OptionRow(
                    options =
                        listOf(
                            OptionItem(DEPTH_OFF, stringResource(R.string.off)) {
                                DepthOffIcon(it)
                            },
                            OptionItem(DEPTH_ON, stringResource(R.string.on)) { DepthOnIcon(it) },
                        ),
                    selected = if (depthEnabled) DEPTH_ON else DEPTH_OFF,
                    onSelect = { writeDepth(it == DEPTH_ON) },
                )
            }

            Spacer(modifier = Modifier.height(SheetDimens.SheetPagerSpacingNav))
        }
    }

    if (showColorPicker) {
        val initialColor =
            try {
                if (currentClockColor != ClockSettingsRepository.COLOR_AUTO) {
                    Color(AndroidColor.parseColor(currentClockColor))
                } else Color.White
            } catch (_: Exception) {
                Color.White
            }

        ColorPickerDialog(
            initialColor = initialColor,
            onDismiss = { showColorPicker = false },
            onColorSelected = { color ->
                val hex = "#%08X".format(color.toArgb())
                writeClockColor(hex)
                showColorPicker = false
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ClockStyleGrid(
    context: Context,
    primaryTypes: List<AxClockType>,
    selectedType: AxClockType,
    tileWidth: Dp,
    tileHeight: Dp,
    previewScale: Float,
    settingsKey: String,
    onSelect: (AxClockType) -> Unit,
    isSelectedOverride: ((AxClockType) -> Boolean)? = null,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val tileWidthPx = with(density) { tileWidth.toPx() }
    val spacingPx = with(density) { 12.dp.toPx() }

    val selectedIndex =
        remember(selectedType, primaryTypes) {
            primaryTypes
                .indexOfFirst { type -> isSelectedOverride?.invoke(type) ?: (type == selectedType) }
                .coerceAtLeast(0)
        }

    LaunchedEffect(selectedIndex) {
        val targetPx = (selectedIndex * (tileWidthPx + spacingPx)).toInt()
        scrollState.animateScrollTo(targetPx)
    }

    Row(
        modifier = Modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        primaryTypes.forEach { type ->
            val isSelected = isSelectedOverride?.invoke(type) ?: (type == selectedType)
            ClockTile(
                context = context,
                type = type,
                isSelected = isSelected,
                previewScale = previewScale,
                settingsKey = settingsKey,
                onClick = { onSelect(type) },
                modifier = Modifier.width(tileWidth).height(tileHeight),
            )
        }
    }
}

@Composable
private fun ClockTile(
    context: Context,
    type: AxClockType,
    isSelected: Boolean,
    previewScale: Float,
    settingsKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isDarkTheme = isSystemInDarkTheme()

    val borderColor = if (isSelected) colors.primary else Color.Transparent
    val bgColor = colors.surfaceBright

    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(TileCorner))
                .background(bgColor)
                .border(TileBorder, borderColor, RoundedCornerShape(TileCorner))
                .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        key(type, settingsKey) {
            val clockView = remember {
                val inflater = LayoutInflater.from(context)
                val view = inflater.inflate(type.viewId, null) as AxClockView
                (view as? BitmapDigitComposeClockView)?.let { bitmapView ->
                    type.bitmapFaceStyle?.let { bitmapView.faceStyle = it }
                }
                view.setupPreview()
                view.onRegionDarknessChanged(isDarkTheme)
                view
            }

            DisposableEffect(Unit) {
                onDispose { (clockView.parent as? ViewGroup)?.removeView(clockView) }
            }

            AndroidView(
                factory = {
                    (clockView.parent as? ViewGroup)?.removeView(clockView)
                    clockView.layoutParams =
                        FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT,
                        )
                    FrameLayout(it).apply {
                        layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                        addView(clockView)
                    }
                },
                modifier = Modifier.fillMaxWidth().wrapContentHeight().scaledLayout(previewScale),
            )
        }
    }
}

private class OptionItem(
    val value: String,
    val label: String,
    val icon: @Composable (Color) -> Unit,
)

@Composable
private fun OptionRow(options: List<OptionItem>, selected: String, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { option ->
            val isSelected = option.value == selected
            val borderColor = if (isSelected) colors.primary else Color.Transparent
            val bgColor = colors.surfaceBright
            val iconTint = if (isSelected) colors.primary else colors.onSurfaceVariant

            Box(
                modifier =
                    Modifier.weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(TileCorner))
                        .background(bgColor)
                        .border(TileBorder, borderColor, RoundedCornerShape(TileCorner))
                        .clickable { onSelect(option.value) },
                contentAlignment = Alignment.Center,
            ) {
                option.icon(iconTint)
            }
        }
    }
}

private data class ClockColorOption(val value: String, val label: String, val color: Color?)

private val CLOCK_COLOR_PRESETS =
    listOf(
        ClockColorOption(ClockSettingsRepository.COLOR_AUTO, "Auto", null),
        ClockColorOption("#FFFFFFFF", "White", Color.White),
        ClockColorOption("#FF000000", "Black", Color.Black),
        ClockColorOption("#FFFF453A", "Red", Color(0xFFFF453A)),
        ClockColorOption("#FFFF9F0A", "Orange", Color(0xFFFF9F0A)),
        ClockColorOption("#FFFFD60A", "Yellow", Color(0xFFFFD60A)),
        ClockColorOption("#FF34C759", "Green", Color(0xFF34C759)),
        ClockColorOption("#FF0A84FF", "Blue", Color(0xFF0A84FF)),
        ClockColorOption("#FF5856D6", "Indigo", Color(0xFF5856D6)),
        ClockColorOption("#FFBF5AF2", "Purple", Color(0xFFBF5AF2)),
    )

@Composable
private fun ClockColorRow(selected: String, onSelect: (String) -> Unit, onCustom: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val isCustom =
        selected != ClockSettingsRepository.COLOR_AUTO &&
            CLOCK_COLOR_PRESETS.none { it.value.equals(selected, ignoreCase = true) }

    Row(
        modifier = Modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CLOCK_COLOR_PRESETS.forEach { option ->
            val isSelected =
                option.value.equals(selected, ignoreCase = true) ||
                    (option.value == ClockSettingsRepository.COLOR_AUTO &&
                        selected == ClockSettingsRepository.COLOR_AUTO)

            ColorSwatch(
                color = option.color,
                isSelected = isSelected,
                isAuto = option.color == null,
                onClick = { onSelect(option.value) },
            )
        }

        ColorSwatch(
            color =
                if (isCustom) {
                    try {
                        Color(AndroidColor.parseColor(selected))
                    } catch (_: Exception) {
                        null
                    }
                } else null,
            isSelected = isCustom,
            isAuto = false,
            isCustomSwatch = true,
            onClick = onCustom,
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color?,
    isSelected: Boolean,
    isAuto: Boolean,
    isCustomSwatch: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val swatchSize = 40.dp
    val shape = CircleShape

    Box(
        modifier =
            Modifier.size(swatchSize)
                .clip(shape)
                .then(if (isSelected) Modifier.border(2.5.dp, colors.primary, shape) else Modifier)
                .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val innerSize = if (isSelected) swatchSize - 6.dp else swatchSize

        when {
            isAuto -> {
                Canvas(modifier = Modifier.size(innerSize).clip(shape)) {
                    val half = size.width / 2f
                    drawCircle(Color.White, radius = half)
                    drawArc(Color.Black, startAngle = 90f, sweepAngle = 180f, useCenter = true)
                }
            }
            isCustomSwatch && color == null -> {
                Canvas(modifier = Modifier.size(innerSize).clip(shape)) {
                    val r = size.width / 2f
                    val segments = 6
                    val hueColors =
                        listOf(
                            Color.Red,
                            Color.Yellow,
                            Color.Green,
                            Color.Cyan,
                            Color.Blue,
                            Color.Magenta,
                            Color.Red,
                        )
                    val sweep = 360f / segments
                    hueColors.dropLast(1).forEachIndexed { i, c ->
                        drawArc(
                            c,
                            startAngle = i * sweep - 90f,
                            sweepAngle = sweep + 1f,
                            useCenter = true,
                        )
                    }
                }
            }
            color != null -> {
                val needsBorder = color == Color.White || color == Color.Black
                Canvas(
                    modifier =
                        Modifier.size(innerSize)
                            .clip(shape)
                            .then(
                                if (needsBorder && !isSelected)
                                    Modifier.border(1.dp, colors.outlineVariant, shape)
                                else Modifier
                            )
                ) {
                    drawCircle(color)
                }
            }
        }
    }
}

@Composable
private fun OptionIcon(tint: Color, draw: DrawScope.(Color) -> Unit) {
    Canvas(modifier = Modifier.size(28.dp)) { draw(tint) }
}

@Composable
private fun SizeDefaultIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 2.5f.dp.toPx()
        val cx = size.width / 2f
        val inset = 5.dp.toPx()
        val maxW = size.width - inset * 2
        val gap = 4.dp.toPx()
        val startY = (size.height - sw * 2 - gap) / 2f

        drawLine(
            color,
            Offset(cx - maxW * 0.3f, startY + sw / 2f),
            Offset(cx + maxW * 0.3f, startY + sw / 2f),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(cx - maxW * 0.22f, startY + sw + gap + sw / 2f),
            Offset(cx + maxW * 0.22f, startY + sw + gap + sw / 2f),
            sw,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun SizeLargeIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 3.5f.dp.toPx()
        val cx = size.width / 2f
        val inset = 3.dp.toPx()
        val maxW = size.width - inset * 2
        val gap = 4.dp.toPx()
        val startY = (size.height - sw * 2 - gap) / 2f

        drawLine(
            color,
            Offset(cx - maxW * 0.42f, startY + sw / 2f),
            Offset(cx + maxW * 0.42f, startY + sw / 2f),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(cx - maxW * 0.32f, startY + sw + gap + sw / 2f),
            Offset(cx + maxW * 0.32f, startY + sw + gap + sw / 2f),
            sw,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun AlignLeftIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 2.5f.dp.toPx()
        val x = 4.dp.toPx()
        val gap = 4.dp.toPx()
        val maxW = size.width - x * 2
        val startY = (size.height - sw * 2 - gap) / 2f

        drawLine(
            color,
            Offset(x, startY + sw / 2f),
            Offset(x + maxW * 0.75f, startY + sw / 2f),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(x, startY + sw + gap + sw / 2f),
            Offset(x + maxW * 0.5f, startY + sw + gap + sw / 2f),
            sw,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun AlignRightIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 2.5f.dp.toPx()
        val x = 4.dp.toPx()
        val gap = 4.dp.toPx()
        val maxW = size.width - x * 2
        val startY = (size.height - sw * 2 - gap) / 2f
        val endX = size.width - x

        drawLine(
            color,
            Offset(endX - maxW * 0.75f, startY + sw / 2f),
            Offset(endX, startY + sw / 2f),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(endX - maxW * 0.5f, startY + sw + gap + sw / 2f),
            Offset(endX, startY + sw + gap + sw / 2f),
            sw,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun AlignCenterIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 2.5f.dp.toPx()
        val cx = size.width / 2f
        val inset = 4.dp.toPx()
        val maxW = size.width - inset * 2
        val gap = 4.dp.toPx()
        val startY = (size.height - sw * 2 - gap) / 2f

        val w1 = maxW * 0.75f
        drawLine(
            color,
            Offset(cx - w1 / 2, startY + sw / 2f),
            Offset(cx + w1 / 2, startY + sw / 2f),
            sw,
            StrokeCap.Round,
        )
        val w2 = maxW * 0.5f
        drawLine(
            color,
            Offset(cx - w2 / 2, startY + sw + gap + sw / 2f),
            Offset(cx + w2 / 2, startY + sw + gap + sw / 2f),
            sw,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun DepthOnIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 2.dp.toPx()
        val cx = size.width / 2f
        val inset = 3.dp.toPx()
        val maxW = size.width - inset * 2

        val lineY1 = size.height * 0.3f
        val lineY2 = size.height * 0.5f
        drawLine(
            color.copy(alpha = 0.4f),
            Offset(cx - maxW * 0.35f, lineY1),
            Offset(cx + maxW * 0.35f, lineY1),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color.copy(alpha = 0.4f),
            Offset(cx - maxW * 0.25f, lineY2),
            Offset(cx + maxW * 0.25f, lineY2),
            sw,
            StrokeCap.Round,
        )

        val peakX = size.width * 0.55f
        val peakY = size.height * 0.25f
        val baseY = size.height * 0.78f
        val halfW = 6.dp.toPx()
        val peakSw = 1.8f.dp.toPx()
        drawLine(color, Offset(peakX - halfW, baseY), Offset(peakX, peakY), peakSw, StrokeCap.Round)
        drawLine(color, Offset(peakX, peakY), Offset(peakX + halfW, baseY), peakSw, StrokeCap.Round)
        drawLine(
            color,
            Offset(peakX - halfW, baseY),
            Offset(peakX + halfW, baseY),
            peakSw,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun DepthOffIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 2.dp.toPx()
        val cx = size.width / 2f
        val inset = 3.dp.toPx()
        val maxW = size.width - inset * 2

        val lineY1 = size.height * 0.3f
        val lineY2 = size.height * 0.5f
        val lineY3 = size.height * 0.7f
        drawLine(
            color,
            Offset(cx - maxW * 0.35f, lineY1),
            Offset(cx + maxW * 0.35f, lineY1),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(cx - maxW * 0.25f, lineY2),
            Offset(cx + maxW * 0.25f, lineY2),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color.copy(alpha = 0.5f),
            Offset(cx - maxW * 0.2f, lineY3),
            Offset(cx + maxW * 0.2f, lineY3),
            sw * 0.8f,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun DateAboveIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 2.5f.dp.toPx()
        val thinSw = 1.5f.dp.toPx()
        val cx = size.width / 2f
        val inset = 3.dp.toPx()
        val maxW = size.width - inset * 2

        val dateY = size.height * 0.28f
        drawLine(
            color.copy(alpha = 0.5f),
            Offset(cx - maxW * 0.25f, dateY),
            Offset(cx + maxW * 0.25f, dateY),
            thinSw,
            StrokeCap.Round,
        )

        val lineY1 = size.height * 0.52f
        val lineY2 = size.height * 0.72f
        drawLine(
            color,
            Offset(cx - maxW * 0.38f, lineY1),
            Offset(cx + maxW * 0.38f, lineY1),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(cx - maxW * 0.28f, lineY2),
            Offset(cx + maxW * 0.28f, lineY2),
            sw,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun DateBelowIcon(tint: Color) {
    OptionIcon(tint = tint) { color ->
        val sw = 2.5f.dp.toPx()
        val thinSw = 1.5f.dp.toPx()
        val cx = size.width / 2f
        val inset = 3.dp.toPx()
        val maxW = size.width - inset * 2

        val lineY1 = size.height * 0.28f
        val lineY2 = size.height * 0.48f
        drawLine(
            color,
            Offset(cx - maxW * 0.38f, lineY1),
            Offset(cx + maxW * 0.38f, lineY1),
            sw,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(cx - maxW * 0.28f, lineY2),
            Offset(cx + maxW * 0.28f, lineY2),
            sw,
            StrokeCap.Round,
        )

        val dateY = size.height * 0.72f
        drawLine(
            color.copy(alpha = 0.5f),
            Offset(cx - maxW * 0.25f, dateY),
            Offset(cx + maxW * 0.25f, dateY),
            thinSw,
            StrokeCap.Round,
        )
    }
}

private fun readCurrentClockId(context: Context): String {
    return try {
        val json =
            Settings.Secure.getString(
                context.contentResolver,
                ClockSettingsRepository.SETTING_CLOCK_FACE,
            )
        if (!json.isNullOrEmpty()) JSONObject(json).optString("clockId", "DEFAULT") else "DEFAULT"
    } catch (_: Exception) {
        "DEFAULT"
    }
}

private fun readAlignment(context: Context): String {
    return Settings.Secure.getString(
        context.contentResolver,
        ClockSettingsRepository.SETTING_ALIGNMENT,
    ) ?: ClockSettingsRepository.ALIGNMENT_CENTER
}

private fun readSize(context: Context): String {
    return Settings.Secure.getString(context.contentResolver, ClockSettingsRepository.SETTING_SIZE)
        ?: ClockSettingsRepository.SIZE_DEFAULT
}

private fun readDatePosition(context: Context): String {
    return Settings.Secure.getString(
        context.contentResolver,
        ClockSettingsRepository.SETTING_DATE_POSITION,
    ) ?: ClockSettingsRepository.DATE_POSITION_ABOVE
}

private fun readDepthEnabled(context: Context): Boolean {
    return Settings.Secure.getInt(context.contentResolver, DEPTH_SETTINGS_KEY, 0) == 1
}

private fun readClockColor(context: Context): String {
    return Settings.Secure.getString(
        context.contentResolver,
        ClockSettingsRepository.SETTING_CLOCK_COLOR,
    ) ?: ClockSettingsRepository.COLOR_AUTO
}
