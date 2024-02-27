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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
        Color(0x70777777)
    else
        Color(0x70ffffff)

    val lineColorBase = if (isLightMode)
        Color(0xffbbbbbb)
    else
        Color(0xffeeeeee)

    val gridColor = if (isLightMode)
        Color(0x40000000)
    else
        Color(0x40ffffff)

    val lineWidth = 3.dp.toPx()

    val maxScale = 10.0f
    val minScale = 1.0f

    var scale by remember { mutableStateOf(1.0f) }
    var offset by remember { mutableStateOf(0f) }
    var width = 0f
    val pTop = 4.dp.toPx()
    val pRight = 4.sp.toPx()
    val pLeft = 20.sp.toPx()
    val pBottom = 16.sp.toPx()
    val pLR = pLeft + pRight
    val pTB = pTop + pBottom

    var initiallyScrolled by remember { mutableStateOf(false) }

    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(width) {
        if (width != 0f) {
            initiallyScrolled = true
            scale = 5.0f
            offset = pLR + (width - pLR) * scale - width
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
                    } else if (pLR + (width - pLR) * scale - offset < width) {
                        offset = pLR + (width - pLR) * scale - width
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    println("drag! $dragAmount")
                    offset -= dragAmount
                    if (offset < 0) {
                        offset = 0f
                    } else if (pLR + (width - pLR) * scale - offset < width) {
                        offset = pLR + (width - pLR) * scale - width
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
                pLeft + ((lastSgOClockEpoch - minTime) / timeRange * (width - pLR)) * scale - offset
            var h = lastSgOClockDT.hour
            while (oClockX > pLeft) {
                if (oClockX < width - 0) {
                    drawLine(
                        color = gridColor,
                        start = Offset(oClockX, pTop),
                        end = Offset(oClockX, height - pBottom),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = h.toString(),
                        topLeft = Offset(oClockX - 5.sp.toPx(), height - 14.sp.toPx()),
                        style = TextStyle(color = gridColor, fontSize = 10.sp)
                    )
                }
                oClockX -= (60 * 60) / timeRange * (width - pLR) * scale
                h = (h + 23) % 24
            }
        }

        // draw horizontal line for each 50mg/dL
        run {
            var sg = (minSg / 50) * 50
            while (sg <= maxSg) {
                val y = pTop + (1 - (sg - minSg) / sgRange) * (height - pTB)
                if (y > 0 && y < height - 0) {
                    drawLine(
                        color = gridColor,
                        start = Offset(pLeft, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = sg.toString(),
                        topLeft = Offset(0f, y - 6.sp.toPx()),
                        style = TextStyle(color = gridColor, fontSize = 10.sp)
                    )
                }
                sg += 50
            }
        }

        var isLineBegun = false
        var prevX = 0f
        var prevY = 0f
        var prevSg: Int? = null

        val dots = mutableListOf<Offset>()

        for (sg in sgs) {
            val sgValue = sg.sgValue
            val time = sg.datetime?.parseISODateTime()?.toEpochSecond(ZoneOffset.UTC)

            if (sgValue == null || time == null) {
                isLineBegun = false
                continue
            }

            val x = pLeft + ((time - minTime) / timeRange * (width - pLR)) * scale - offset
            val y = pTop + (1 - (sgValue - minSg) / sgRange) * (height - pTB)

            val isInRange = x >= pLeft && x <= width - pRight

            val sgDiff = prevSg?.let { sgValue - it }
            // sgDiff が0の場合は白で、大きいほど赤く、小さいほど青くする
            val lineColor = if (sgDiff == null) {
                lineColorBase
            } else {
                val t = (sgDiff / 30f).coerceIn(-1f, 1f)
                if (t < 0) {
                    blendColor(lineColorBase, Color.Blue, -t)
                } else {
                    blendColor(lineColorBase, Color.Red, t)
                }
            }

            if (isLineBegun) {
                if (isInRange) {
                    val fromX: Float
                    val fromY: Float
                    if (prevX < pLeft) {
                        fromX = pLeft
                        fromY = prevY + (y - prevY) * (fromX - prevX) / (x - prevX)
                    } else {
                        fromX = prevX
                        fromY = prevY
                    }
                    drawLine(
                        color = lineColor,
                        start = Offset(fromX, fromY),
                        end = Offset(x, y),
                        strokeWidth = lineWidth
                    )
                }
            } else {
                isLineBegun = true
            }

            if (isInRange) {
                dots.add(Offset(x, y))
            }

            prevX = x
            prevY = y
            prevSg = sgValue
        }

        for (p in dots) {
            drawCircle(color = dotColor, radius = 2.5f.dp.toPx(), center = p)
        }
    }
}

private fun blendColor(c1: Color, c2: Color, t: Float): Color {
    val r = c1.red * (1 - t) + c2.red * t
    val g = c1.green * (1 - t) + c2.green * t
    val b = c1.blue * (1 - t) + c2.blue * t
    val a = c1.alpha * (1 - t) + c2.alpha * t
    return Color(r, g, b, a)
}

@Composable
private fun Dp.toPx() = with(LocalDensity.current) {
    this@toPx.toPx()
}

@Composable
private fun TextUnit.toPx() = with(LocalDensity.current) {
    this@toPx.toPx()
}