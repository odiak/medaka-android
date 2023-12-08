package net.odiak.medaka

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.odiak.medaka.common.SensorGlucose
import net.odiak.medaka.common.minMaxOrNull
import net.odiak.medaka.common.parseISODateTime
import java.time.ZoneOffset

@OptIn(ExperimentalTextApi::class)
@Composable
fun Plot(sgs: List<SensorGlucose>) {

    val (minSg, maxSg) = sgs.mapNotNull { it.sgValue }.minMaxOrNull()
    val (minTime, maxTime) = sgs
        .map { it.datetime?.parseISODateTime()?.toEpochSecond(ZoneOffset.UTC) ?: 0 }
        .minMaxOrNull()
    if (minSg == null || maxSg == null || minTime == null || maxTime == null) return

    val sgRange = (maxSg - minSg).toFloat()
    val timeRange = (maxTime - minTime).toFloat()

    val isLightMode = !isSystemInDarkTheme()

    val bgColor = if (isLightMode)
        Color(0xfff0f0f0)
    else
        Color(0xff303030)

    val dotColor = if (isLightMode)
        Color(0xff000000)
    else
        Color(0xffffffff)

    val gridColor = if (isLightMode)
        Color(0x40000000)
    else
        Color(0x40ffffff)

    val maxScale = 10.0f
    val minScale = 1.0f

    var scale by remember { mutableStateOf(1.0f) }
    var offset by remember { mutableStateOf(0f) }
    var width = 0f
    val p = with(LocalDensity.current) { 4.dp.toPx() }

    var initiallyScrolled by remember { mutableStateOf(false) }

    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(width) {
        if (width != 0f) {
            initiallyScrolled = true
            scale = 5.0f
            offset = p * 2 + (width - p * 2) * scale - width
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .height(200.dp)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, _, zoom, _ ->
                    val oldScale = scale
                    scale *= zoom
                    if (scale < minScale) {
                        scale = minScale
                    } else if (scale > maxScale) {
                        scale = maxScale
                    }

                    val actualZoom = scale / oldScale
                    val cx = centroid.x
                    val dx = (cx + offset) * (actualZoom - 1)
                    offset += dx

                    if (offset < 0) {
                        offset = 0f
                    } else if (p * 2 + (width - p * 2) * scale - offset < width) {
                        offset = p * 2 + (width - p * 2) * scale - width
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    println("drag! $dragAmount")
                    offset -= dragAmount
                    if (offset < 0) {
                        offset = 0f
                    } else if (p * 2 + (width - p * 2) * scale - offset < width) {
                        offset = p * 2 + (width - p * 2) * scale - width
                    }
                }
            }
    ) {
        width = size.width
        val height = size.height

        // draw vertical line for each hour
        val lastSgDateTime = sgs.findLast { it.datetime != null }?.datetime
        if (lastSgDateTime != null) {
            val lastSgOClock = lastSgDateTime.slice(0..12) + ":00:00Z"
            val lastSgOClockDT = lastSgOClock.parseISODateTime()
            val lastSgOClockEpoch = lastSgOClockDT.toEpochSecond(ZoneOffset.UTC)
            var oClockX =
                p + ((lastSgOClockEpoch - minTime) / timeRange * (width - p * 2)) * scale - offset
            var h = lastSgOClockDT.hour
            while (oClockX > 0) {
                if (oClockX < width - 0) {
                    drawLine(
                        color = gridColor,
                        start = Offset(oClockX, 0f),
                        end = Offset(oClockX, height - 16.sp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = h.toString(),
                        topLeft = Offset(oClockX - 5.sp.toPx(), height - 14.sp.toPx()),
                        style = TextStyle(color = gridColor, fontSize = 10.sp)
                    )
                }
                oClockX -= (60 * 60) / timeRange * (width - p * 2) * scale
                h = (h + 23) % 24
            }
        }

        var isLineBegun = false
        var prevX = 0f
        var prevY = 0f

        for (sg in sgs) {
            val sgValue = sg.sgValue
            val time = sg.datetime?.parseISODateTime()?.toEpochSecond(ZoneOffset.UTC)

            if (sgValue == null || time == null) {
                isLineBegun = false
                continue
            }

            val x = p + ((time - minTime) / timeRange * (width - p * 2)) * scale - offset
            val y = p + (1 - (sgValue - minSg) / sgRange) * (height - p * 2)

            if (isLineBegun) {
                drawLine(
                    color = dotColor,
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 2.dp.toPx()
                )
            } else {
                isLineBegun = true
            }

            drawCircle(color = dotColor, radius = 2.dp.toPx(), center = Offset(x, y))

            prevX = x
            prevY = y
        }
    }
}