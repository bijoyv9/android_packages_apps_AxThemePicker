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

import android.content.Context
import android.database.ContentObserver
import android.icu.util.TimeZone as IcuTimeZone
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.axion.themepicker.utils.math.scaleRatio
import com.android.systemui.plugins.keyguard.ui.clocks.*
import com.android.systemui.shared.clocks.AxClockProvider
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.view.AxClockView
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

val Context.previewScale: Float
    get() {
        val displayMetrics = resources.displayMetrics
        val sw =
            minOf(displayMetrics.widthPixels, displayMetrics.heightPixels) / displayMetrics.density

        val isTablet = sw >= 600f
        val baseMultiplier = if (isTablet) 0.24f else 0.42f
        val baseDp = if (isTablet) 600f else 420f

        val dpRatio = sw / baseDp
        val adjustedMultiplier =
            if (isTablet) {
                baseMultiplier * cbrt(dpRatio.toDouble()).toFloat()
            } else {
                baseMultiplier * sqrt(dpRatio)
            }

        val maxScale = if (isTablet) 0.32f else 0.55f
        return adjustedMultiplier.coerceIn(0.22f, maxScale)
    }

fun Modifier.scaledLayout(scale: Float, overrideWidth: Dp = Dp.Unspecified): Modifier =
    this.layout { measurable, constraints ->
        val expandedMaxW =
            if (overrideWidth != Dp.Unspecified) {
                overrideWidth.roundToPx()
            } else {
                (constraints.maxWidth / scale).roundToInt()
            }
        val expandedMaxH = (constraints.maxHeight / scale).roundToInt()
        val childConstraints =
            constraints.copy(
                minWidth = 0,
                maxWidth = expandedMaxW,
                minHeight = 0,
                maxHeight = expandedMaxH,
            )

        val placeable = measurable.measure(childConstraints)
        val scaledWidth = (placeable.width * scale).roundToInt()
        val scaledHeight = (placeable.height * scale).roundToInt()
        layout(scaledWidth, scaledHeight) {
            placeable.placeWithLayer(
                (scaledWidth - placeable.width) / 2,
                (scaledHeight - placeable.height) / 2,
            ) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin.Center
            }
        }
    }

@Composable
fun PreviewClock(isPreview: Boolean, isRegionDark: Boolean = true) {
    val context = LocalContext.current
    val scale = if (isPreview) context.previewScale else context.scaleRatio
    var settingsVersion by remember { mutableIntStateOf(0) }

    val clockProvider = remember {
        AxClockProvider(
            layoutInflater = LayoutInflater.from(context),
            resources = context.resources,
            isClockReactiveVariantsEnabled = true,
            vibrator = context.getSystemService(Vibrator::class.java),
        )
    }

    DisposableEffect(Unit) {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    settingsVersion++
                }
            }
        val uris =
            listOf(
                ClockSettingsRepository.clockFaceUri,
                ClockSettingsRepository.alignmentUri,
                ClockSettingsRepository.sizeUri,
                ClockSettingsRepository.heightOffsetUri,
            )
        uris.forEach { resolver.registerContentObserver(it, false, observer) }
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    var currentClockId by remember { mutableStateOf<String?>(null) }
    var currentTime by remember { mutableStateOf(Calendar.getInstance().time) }

    LaunchedEffect(settingsVersion) {
        withContext(Dispatchers.IO) {
            try {
                val json =
                    Settings.Secure.getString(
                        context.contentResolver,
                        ClockSettingsRepository.SETTING_CLOCK_FACE,
                    )
                val id =
                    if (!json.isNullOrEmpty()) {
                        JSONObject(json).optString("clockId", "DEFAULT")
                    } else {
                        "DEFAULT"
                    }
                withContext(Dispatchers.Main) { currentClockId = id }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) { currentClockId = "DEFAULT" }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Calendar.getInstance().time
            delay(1000L)
        }
    }

    val clockId = currentClockId ?: return

    val controller =
        remember(clockId, settingsVersion) {
            clockProvider.createClock(context, ClockSettings(clockId = clockId)).apply {
                initialize(isDarkTheme = true, dozeFraction = 0f, foldFraction = 0f)
                (smallClock.view as? AxClockView)?.onClockLayoutChanged(true, false)
                (largeClock.view as? AxClockView)?.onClockLayoutChanged(true, true)
                (smallClock.view as? AxClockView)?.apply {
                    depthEffectEnabled = false
                    touchEnabled = false
                }
                (largeClock.view as? AxClockView)?.apply {
                    depthEffectEnabled = false
                    touchEnabled = false
                }
                smallClock.events.onRegionDarknessChanged(isRegionDark)
                largeClock.events.onRegionDarknessChanged(isRegionDark)
                events.onLocaleChanged(Locale.getDefault())
                events.onTimeZoneChanged(IcuTimeZone.getDefault())
                events.onTimeFormatChanged(TimeFormatKind.getFromContext(context))
                events.onDateChanged()
                events.onClockDataChanged(
                    ClockData(
                        weather = ClockWeatherData(),
                        calendar =
                            CalendarSimpleData(
                                1L,
                                "",
                                System.currentTimeMillis() + 300000L,
                                System.currentTimeMillis() + 3600000L,
                                null,
                            ),
                    )
                )
                smallClock.events.onTimeTick()
                largeClock.events.onTimeTick()
            }
        }

    LaunchedEffect(isRegionDark) {
        controller.smallClock.events.onRegionDarknessChanged(isRegionDark)
        controller.largeClock.events.onRegionDarknessChanged(isRegionDark)
    }

    LaunchedEffect(currentTime) {
        controller.smallClock.events.onTimeTick()
        controller.largeClock.events.onTimeTick()
    }

    val configuration = LocalConfiguration.current
    val clockWidth = configuration.screenWidthDp.dp

    Column(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
        Box(
            modifier =
                Modifier.fillMaxWidth().wrapContentHeight().padding(vertical = 12.dp * scale),
            contentAlignment = Alignment.Center,
        ) {
            key(clockId, settingsVersion) {
                SystemUIClockView(
                    controller = controller,
                    modifier =
                        Modifier.scaledLayout(scale, if (isPreview) clockWidth else Dp.Unspecified),
                )
            }
        }
    }
}

@Composable
fun SystemUIClockView(controller: ClockController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            val clockView = controller.smallClock.view
            (clockView.parent as? ViewGroup)?.removeView(clockView)

            clockView.layoutParams =
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

            FrameLayout(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                addView(clockView)
            }
        },
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
        update = { controller.smallClock.events.onTimeTick() },
    )
}
