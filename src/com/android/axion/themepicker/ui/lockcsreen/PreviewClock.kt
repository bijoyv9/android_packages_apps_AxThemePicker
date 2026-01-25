/*
 * Copyright (C) 2025 AxionOS
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

import android.content.Context
import android.graphics.Typeface
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.graphics.drawable.toBitmap
import com.android.axion.themepicker.R
import com.android.axion.themepicker.utils.math.scaleRatio
import com.android.systemui.customization.R as customR
import kotlinx.coroutines.*
import kotlin.math.*
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

val Context.previewScale: Float
    get() {
        val displayMetrics = resources.displayMetrics
        val sw = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels) / displayMetrics.density
        
        val isTablet = sw >= 600f
        val baseMultiplier = if (isTablet) 0.24f else 0.42f
        val baseDp = if (isTablet) 600f else 420f
        
        val dpRatio = sw / baseDp
        val adjustedMultiplier = if (isTablet) {
            baseMultiplier * kotlin.math.cbrt(dpRatio.toDouble()).toFloat()
        } else {
            baseMultiplier * kotlin.math.sqrt(dpRatio)
        }
        
        val maxScale = if (isTablet) 0.32f else 0.55f
        return adjustedMultiplier.coerceIn(0.22f, maxScale)
    }

val Context.maxClockWidthFraction: Float
    get() {
        val displayMetrics = resources.displayMetrics
        val sw = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels) / displayMetrics.density
        return if (sw >= 600f) 0.45f else 0.70f
    }

enum class DateAlignment {
    START, CENTER, END
}

private fun Context.getDigitDrawables(prefix: String): Array<Int> {
    return (0..9).map { digit ->
        val resourceName = "${prefix}_$digit"
        val resourceId = resources.getIdentifier(resourceName, "drawable", packageName)
        if (resourceId == 0) {
            throw IllegalArgumentException("Drawable not found: $resourceName")
        }
        resourceId
    }.toTypedArray()
}

@Composable
fun PreviewClock(isPreview: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scale = if (isPreview) context.previewScale else context.scaleRatio
    var isLoadedFromSettings by remember { mutableStateOf(false) }

    val clocks = listOf(
        NtypeClock(scale, isPreview),
        NDotClock(scale, isPreview),
        GraphicClock(scale, isPreview),
        GeneralClock(scale, isPreview),
        LondonUGClock(scale, isPreview),
        QuickLookClock(scale, isPreview),
        SpaceAgeClock(scale, isPreview),
        PolylineClock(scale, isPreview),
        BlankClock(scale, isPreview)
    )

    val pagerState = rememberPagerState { clocks.size }
    var currentTime by remember { mutableStateOf(Calendar.getInstance().time) }

    var clockType by remember { mutableStateOf(0) }

    if (isPreview) {
        DisposableEffect(Unit) {
            val uri = Settings.Secure.getUriFor("lock_screen_custom_clock_face")
            val resolver = context.contentResolver
            val observer = object : android.database.ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    clockType++
                }
            }
            resolver.registerContentObserver(uri, false, observer)

            onDispose {
                resolver.unregisterContentObserver(observer)
            }
        }
    }

    LaunchedEffect(clockType) {
        if (isPreview) {
            withContext(Dispatchers.IO) {
                try {
                    val json = Settings.Secure.getString(
                        context.contentResolver,
                        "lock_screen_custom_clock_face"
                    )
                    if (!json.isNullOrEmpty()) {
                        val clockId = JSONObject(json).optString("clockId")
                        val index = clocks.indexOfFirst { it.name == clockId }
                        if (index >= 0) {
                            withContext(Dispatchers.Main) {
                                pagerState.scrollToPage(index)
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Calendar.getInstance().time
            delay(1000L)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val json = Settings.Secure.getString(
                    context.contentResolver,
                    "lock_screen_custom_clock_face"
                )

                if (!json.isNullOrEmpty()) {
                    val clockId = JSONObject(json).optString("clockId")
                    val index = clocks.indexOfFirst { it.name == clockId }
                    if (index >= 0) {
                        withContext(Dispatchers.Main) {
                            pagerState.scrollToPage(index)
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                isLoadedFromSettings = true
            }
        }
    }

    LaunchedEffect(pagerState.currentPage, isLoadedFromSettings) {
        if (isLoadedFromSettings) {
            val selectedClock = clocks[pagerState.currentPage]
            val timestamp = System.currentTimeMillis()
            val json = JSONObject().apply {
                put("clockId", selectedClock.name)
                put("metadata", JSONObject().apply {
                    put("appliedTimestamp", timestamp)
                })
                put("axes", org.json.JSONArray())
            }.toString()

            withContext(Dispatchers.IO) {
                try {
                    Settings.Secure.putString(
                        context.contentResolver,
                        "lock_screen_custom_clock_face",
                        json
                    )
                } catch (e: Exception) {
                }
            }
        }
    }

    val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .animateContentSize()
    ) {
        val currentClock = clocks[pagerState.currentPage]
        val targetAlignment = currentClock.dateAlignment

        AnimatedVisibility(
            visible = targetAlignment != null,
            enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)) + shrinkVertically(animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp * scale, vertical = 8.dp * scale),
                contentAlignment = when (targetAlignment) {
                    DateAlignment.START -> Alignment.CenterStart
                    DateAlignment.CENTER -> Alignment.Center
                    DateAlignment.END -> Alignment.CenterEnd
                    null -> Alignment.Center
                }
            ) {
                Text(
                    text = dateFormat.format(currentTime),
                    fontSize = with(density) { (20.dp * scale).toSp() },
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false
                        )
                    ),
                    color = Color.White
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp),
            userScrollEnabled = !isPreview
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .padding(vertical = 8.dp * scale),
                contentAlignment = Alignment.Center
            ) {
                clocks[page].Render(currentTime)
            }
        }

        if (!isPreview) {
            Spacer(modifier = Modifier.height(16.dp * scale))
            ClockIndicator(pageCount = clocks.size, currentPage = pagerState.currentPage)
            Spacer(modifier = Modifier.height(16.dp * scale))
        }
    }
}

@Composable
private fun ClockIndicator(pageCount: Int, currentPage: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        animateColorAsState(
                            targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.3f),
                            animationSpec = tween(durationMillis = 300)
                        ).value
                    )
            )
        }
    }
}

sealed class ClockItem {
    abstract val name: String
    abstract val dateAlignment: DateAlignment?
    abstract val scale: Float
    
    @Composable
    abstract fun Render(currentTime: Date)

    class GeneralClock(
        override val name: String,
        val showDate: Boolean = true,
        override val scale: Float,
        val isPreview: Boolean = false
    ) : ClockItem() {
        override val dateAlignment: DateAlignment? = null

        @Composable
        override fun Render(currentTime: Date) {
            val context = LocalContext.current
            val density = LocalDensity.current
            val is24Hour = DateFormat.is24HourFormat(context)
            val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

            val previewMultiplier = if (isPreview) context.previewScale else 1f
            val renderScale = scale * previewMultiplier
            
            val digitSpacing = 4.dp * renderScale
            val dotSize = 6.dp * renderScale
            val dotMargin = 4.dp * renderScale

            val digitResIds = context.getDigitDrawables("intervar")

            val bitmaps = remember {
                digitResIds.map { context.getDrawable(it)!!.toBitmap().asImageBitmap() }
            }

            val dotRadiusPx = with(density) { (dotSize / 2).toPx() }
            val dotMarginPx = with(density) { dotMargin.toPx() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(start = Dimens.ClockSidePadding * renderScale),
                horizontalAlignment = Alignment.Start
            ) {
                if (showDate) {
                    Text(
                        text = dateFormat.format(currentTime),
                        fontSize = with(density) { (Dimens.ClockDateFont * renderScale).toSp() },
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(Dimens.ClockSpacer * renderScale))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth(context.maxClockWidthFraction)
                        .height(100.dp * renderScale),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val calendar = Calendar.getInstance()
                        calendar.time = currentTime
                        val hour = calendar.get(if (is24Hour) Calendar.HOUR_OF_DAY else Calendar.HOUR)
                            .let { if (it == 0) 12 else it }
                        val minute = calendar.get(Calendar.MINUTE)

                        val hourStr = hour.toString()
                        val minuteStr = String.format("%02d", minute)
                        val timeDigits = (hourStr + minuteStr).toCharArray()
                        
                        val digitSpacingPx = with(density) { digitSpacing.toPx() }
                        val dotExtraWidth = with(density) { (digitSpacing.toPx() * 2) + dotRadiusPx }
                        
                        var totalNaturalWidth = 0f
                        timeDigits.forEach { char ->
                            val bmp = bitmaps.getOrNull(char.digitToIntOrNull() ?: 0)
                            if (bmp != null) {
                                totalNaturalWidth += bmp.width * renderScale + digitSpacingPx
                            }
                        }
                        totalNaturalWidth += dotExtraWidth 
                        
                        val availableWidth = size.width * 0.95f
                        val widthScaleFactor = if (totalNaturalWidth > availableWidth) {
                            availableWidth / totalNaturalWidth
                        } else {
                            1f
                        }
                        
                        val uniformScale = renderScale * widthScaleFactor
                        val uniformDigitSpacing = digitSpacingPx * widthScaleFactor
                        val uniformDotRadius = dotRadiusPx * widthScaleFactor

                        var xOffset = 0f

                        timeDigits.forEachIndexed { index, char ->
                            val bmp = bitmaps.getOrNull(char.digitToIntOrNull() ?: 0) ?: return@forEachIndexed
                            val scaledW = bmp.width * uniformScale
                            val scaledH = bmp.height * uniformScale
                            val yOffset = (size.height - scaledH) / 2f

                            drawImage(
                                image = bmp,
                                dstOffset = IntOffset(xOffset.toInt(), yOffset.toInt()),
                                dstSize = IntSize(scaledW.toInt(), scaledH.toInt()),
                                colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn)
                            )

                            xOffset += scaledW + uniformDigitSpacing

                            if (index == hourStr.lastIndex) {
                                val centerX = xOffset
                                val inwardOffset = 24.dp * uniformScale
                                val inwardOffsetPx = with(density) { inwardOffset.toPx() }

                                val topDotY = inwardOffsetPx + uniformDotRadius
                                val bottomDotY = size.height - inwardOffsetPx - uniformDotRadius

                                drawCircle(
                                    color = Color.White,
                                    radius = uniformDotRadius,
                                    center = Offset(centerX, topDotY)
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = uniformDotRadius,
                                    center = Offset(centerX, bottomDotY)
                                )

                                xOffset += with(density) { (digitSpacing.toPx() * 2) + dotRadiusPx }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.ClockSpacer * renderScale))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = stringResource(R.string.temperature_prev),
                        fontSize = with(density) { (Dimens.ClockDateFont * renderScale).toSp() },
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.width(Dimens.WeatherSpacerSmall * renderScale))

                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = stringResource(R.string.weather_prev),
                        modifier = Modifier.size(Dimens.WeatherIcon * renderScale),
                        tint = Color.White
                    )

                    Spacer(modifier = Modifier.width(Dimens.WeatherSpacerLarge * renderScale))

                    Text(
                        text = stringResource(R.string.weather_prev),
                        fontSize = with(density) { (Dimens.ClockDateFont * renderScale).toSp() },
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        color = Color.White
                    )
                }
            }
        }
    }

    class QuickLookClock(
        override val name: String,
        override val scale: Float,
        val showDate: Boolean = true,
        val isPreview: Boolean = false
    ) : ClockItem() {
        override val dateAlignment: DateAlignment? = null
        
        @Composable
        override fun Render(currentTime: Date) {
            val context = LocalContext.current
            val density = LocalDensity.current
            val is24Hour = DateFormat.is24HourFormat(context)
            val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
            val timeFormat = SimpleDateFormat(if (is24Hour) "H:mm" else "h:mm", Locale.getDefault())
            val ndot = FontFamily(Typeface.create("nothingdot57", Typeface.NORMAL))

            val previewMultiplier = if (isPreview) context.previewScale else 1f
            val renderScale = scale * previewMultiplier

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(start = Dimens.ClockSidePadding * renderScale),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = timeFormat.format(currentTime),
                    fontFamily = ndot,
                    fontSize = with(density) { (Dimens.ClockTimeFont * renderScale).toSp() },
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false
                        )
                    ),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(Dimens.ClockSpacer * renderScale))

                Text(
                    text = dateFormat.format(currentTime),
                    fontSize = with(density) { (Dimens.ClockDateFont * renderScale).toSp() },
                    fontWeight = FontWeight.Thin,
                    fontFamily = ndot,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false
                        )
                    ),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(Dimens.ClockSpacer * renderScale))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = stringResource(R.string.weather_prev),
                        modifier = Modifier.size(Dimens.WeatherIcon * renderScale),
                        tint = Color.White
                    )
                    
                    Spacer(modifier = Modifier.width(Dimens.WeatherSpacerLarge * renderScale))

                    Text(
                        text = stringResource(R.string.temperature_prev),
                        fontSize = with(density) { (Dimens.ClockDateFont * renderScale).toSp() },
                        fontWeight = FontWeight.Thin,
                        fontFamily = ndot,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.width(Dimens.WeatherSpacerSmall * renderScale))

                    Text(
                        text = stringResource(R.string.weather_prev),
                        fontSize = with(density) { (Dimens.ClockDateFont * renderScale).toSp() },
                        fontWeight = FontWeight.Thin,
                        fontFamily = ndot,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        color = Color.White
                    )
                }
            }
        }
    }

    class BitmapDigitClock(
        override val name: String,
        val digitResIds: Array<Int>,
        val digitSpacing: Dp,
        override val scale: Float,
        val horizontalOffset: Dp = 0.dp,
        override val dateAlignment: DateAlignment? = DateAlignment.CENTER,
        val isPreview: Boolean = false
    ) : ClockItem() {
        @Composable
        override fun Render(currentTime: Date) {
            val context = LocalContext.current
            val is24Hour = DateFormat.is24HourFormat(context)
            val timeStr = SimpleDateFormat(if (is24Hour) "Hmm" else "hmm", Locale.getDefault())
                .format(currentTime)

            val bitmaps = remember(digitResIds) {
                digitResIds.map { resId ->
                    context.getDrawable(resId)!!.toBitmap().asImageBitmap()
                }
            }
            
            val s = if (isPreview) scale + 0.2f else scale 

            val maxWidth = remember { bitmaps.maxOf { it.width } }
            val maxHeight = remember { bitmaps.maxOf { it.height } }
            val maxAspectRatio = remember { maxWidth.toFloat() / maxHeight.toFloat() }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 32.dp * s)
            ) {
                val availableWidth = this.maxWidth
                val numDigits = timeStr.length
                val totalSpacing = digitSpacing * (numDigits - 1)

                val digitWidth = (availableWidth - totalSpacing) / numDigits
                val digitHeight = digitWidth / maxAspectRatio

                val finalDigitWidth = digitWidth * s
                val finalDigitHeight = digitHeight * s
                val finalSpacing = digitSpacing * s

                val totalWidth = (finalDigitWidth * numDigits) + (finalSpacing * (numDigits - 1))
                val startOffset = (availableWidth - totalWidth) / 2 + horizontalOffset

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = startOffset)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(finalSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        timeStr.forEach { char ->
                            val digitIndex = char.digitToIntOrNull() ?: 0
                            val resId = digitResIds.getOrElse(digitIndex) { digitResIds.first() }

                            Box(
                                modifier = Modifier
                                    .width(finalDigitWidth)
                                    .height(finalDigitHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(resId),
                                    contentDescription = char.toString(),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    class BlankClock(
        override val name: String,
        override val scale: Float,
        val isPreview: Boolean = false
    ) : ClockItem() {
        override val dateAlignment: DateAlignment? = null

        @Composable
        override fun Render(currentTime: Date) {
            val context = LocalContext.current
            val density = LocalDensity.current

            val previewMultiplier = if (isPreview) context.previewScale else 1f
            val renderScale = scale * previewMultiplier

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp * renderScale)
                    .padding(horizontal = 32.dp * renderScale),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = "No clock",
                        modifier = Modifier.size(32.dp * renderScale),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp * renderScale))
                    Text(
                        text = "No clock",
                        fontSize = with(density) { (14.dp * renderScale).toSp() },
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    class GraphicClock(
        override val name: String,
        override val dateAlignment: DateAlignment? = DateAlignment.CENTER,
        override val scale: Float,
        val isPreview: Boolean = false
    ) : ClockItem() {
        @Composable
        override fun Render(currentTime: Date) {
            val context = LocalContext.current
            
            val paddingMultiplier = if (isPreview) context.previewScale else 1f
            val paddingScale = scale * paddingMultiplier
            
            val clockW = scale * 200.dp
            val clockH = scale * 100.dp
            
            val tickBitmap = remember {
                context.getDrawable(R.drawable.graphic_tick)!!.toBitmap().asImageBitmap()
            }
            val tickLightBitmap = remember {
                context.getDrawable(R.drawable.graphic_tick_light)!!.toBitmap().asImageBitmap()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 32.dp * paddingScale, vertical = 16.dp * paddingScale),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(width = clockW, height = clockH)) {
                    val w = size.width
                    val h = size.height
                    val centerX = w / 2
                    val centerY = h / 2
                    
                    val tickWidth = tickBitmap.width * scale
                    val tickHeight = tickBitmap.height * scale
                    
                    val tickLeft = (w - tickWidth) / 2f
                    val tickTop = (h - tickHeight) / 2f
                    
                    drawImage(
                        image = tickLightBitmap,
                        dstOffset = IntOffset(tickLeft.toInt(), tickTop.toInt()),
                        dstSize = IntSize(tickWidth.toInt(), tickHeight.toInt()),
                        colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn)
                    )
                    
                    drawImage(
                        image = tickBitmap,
                        dstOffset = IntOffset(tickLeft.toInt(), tickTop.toInt()),
                        dstSize = IntSize(tickWidth.toInt(), tickHeight.toInt()),
                        colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn)
                    )

                    val calendar = Calendar.getInstance()
                    calendar.time = currentTime
                    val hours = calendar.get(Calendar.HOUR)
                    val minutes = calendar.get(Calendar.MINUTE)
                    val seconds = calendar.get(Calendar.SECOND)

                    val hourAngle = (hours + minutes / 60f) * 5f
                    val minuteAngle = minutes + seconds / 60f
                    val secondAngle = seconds.toFloat()

                    val handSize = 4.dp.toPx() * scale
                    
                    drawHand(centerX, centerY, h, hourAngle, handSize * 2, 0.22f to 0.38f, Color.White)
                    drawHand(centerX, centerY, h, minuteAngle, handSize, 0.22f to 0.42f, Color.White)
                    drawHand(centerX, centerY, h, secondAngle, handSize / 2, 0.19f to 0.42f, Color(0xFFD71921))

                    val dotSize = 6.dp.toPx() * scale
                    drawCircle(
                        color = Color.White,
                        radius = dotSize * 1.5f,
                        center = Offset(centerX, centerY)
                    )
                    drawCircle(
                        color = Color(0xFFD71921),
                        radius = dotSize,
                        center = Offset(centerX, centerY)
                    )
                }
            }
        }

        private fun DrawScope.drawHand(
            centerX: Float,
            centerY: Float,
            height: Float,
            position: Float,
            thickness: Float,
            multipliers: Pair<Float, Float>,
            color: Color
        ) {
            val angleRad = Math.toRadians((position * 6 - 90).toDouble())
            val startLength = multipliers.first * height
            val endLength = multipliers.second * height
            val startX = centerX - cos(angleRad).toFloat() * startLength
            val startY = centerY - sin(angleRad).toFloat() * startLength
            val endX = centerX + cos(angleRad).toFloat() * endLength
            val endY = centerY + sin(angleRad).toFloat() * endLength
            drawLine(
                color = color,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = thickness,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun QuickLookClock(scale: Float, isPreview: Boolean = false): ClockItem =
    ClockItem.QuickLookClock("OLD_QUICKLOOK", scale = scale, showDate = true, isPreview = isPreview)

@Composable
fun GeneralClock(scale: Float, isPreview: Boolean = false): ClockItem =
    ClockItem.GeneralClock("GENERAL", showDate = true, scale = scale, isPreview = isPreview)

@Composable
fun NtypeClock(scale: Float, isPreview: Boolean = false): ClockItem {
    val context = LocalContext.current
    return ClockItem.BitmapDigitClock(
        "DEFAULT",
        digitResIds = context.getDigitDrawables("ntype"),
        digitSpacing = 5.dp * scale,
        scale = scale * 0.75f,
        horizontalOffset = 0.dp,
        dateAlignment = DateAlignment.CENTER,
        isPreview = isPreview
    )
}

@Composable
fun SpaceAgeClock(scale: Float, isPreview: Boolean = false): ClockItem {
    val context = LocalContext.current
    return ClockItem.BitmapDigitClock(
        "SPACE_AGE",
        digitResIds = context.getDigitDrawables("space_age"),
        digitSpacing = (-0.3334).dp * scale,
        scale = scale * 0.75f,
        horizontalOffset = 0.dp,
        dateAlignment = DateAlignment.CENTER,
        isPreview = isPreview
    )
}

@Composable
fun PolylineClock(scale: Float, isPreview: Boolean = false): ClockItem {
    val context = LocalContext.current
    return ClockItem.BitmapDigitClock(
        "POLYLINE",
        digitResIds = context.getDigitDrawables("polyline"),
        digitSpacing = 5.dp * scale,
        scale = scale * 0.75f,
        horizontalOffset = 0.dp,
        dateAlignment = DateAlignment.CENTER,
        isPreview = isPreview
    )
}

@Composable
fun LondonUGClock(scale: Float, isPreview: Boolean = false): ClockItem {
    val context = LocalContext.current
    return ClockItem.BitmapDigitClock(
        "LONDON_UG",
        digitResIds = context.getDigitDrawables("london_ug"),
        digitSpacing = 8.dp * scale,
        scale = scale * 0.45f,
        horizontalOffset = 0.dp,
        dateAlignment = DateAlignment.CENTER,
        isPreview = isPreview
    )
}

@Composable
fun NDotClock(scale: Float, isPreview: Boolean = false): ClockItem {
    val context = LocalContext.current
    return ClockItem.BitmapDigitClock(
        "NDOT",
        digitResIds = context.getDigitDrawables("ndot"),
        digitSpacing = 5.dp * scale,
        scale = scale * 0.75f,
        horizontalOffset = 0.dp,
        dateAlignment = DateAlignment.CENTER,
        isPreview = isPreview
    )
}

@Composable
fun GraphicClock(scale: Float, isPreview: Boolean = false): ClockItem =
    ClockItem.GraphicClock("GRAPHIC", dateAlignment = null, scale = scale, isPreview = isPreview)

@Composable
fun BlankClock(scale: Float, isPreview: Boolean = false): ClockItem =
    ClockItem.BlankClock("BLANK", scale = scale, isPreview = isPreview)
