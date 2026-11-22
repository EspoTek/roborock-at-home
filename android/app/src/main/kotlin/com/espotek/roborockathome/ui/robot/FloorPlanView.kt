package com.espotek.roborockathome.ui.robot

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.map.FloorPlan
import com.espotek.roborockathome.core.map.LabelSpec
import com.espotek.roborockathome.core.map.RoomBox
import com.espotek.roborockathome.core.map.ScreenRect
import com.espotek.roborockathome.core.map.layoutLabels
import com.espotek.roborockathome.core.map.paintFloorPlan
import com.espotek.roborockathome.core.map.roomAtOrNull
import com.espotek.roborockathome.core.model.Activity
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.model.RobotMap
import com.espotek.roborockathome.core.model.RobotPath
import com.espotek.roborockathome.core.state.Plan
import com.espotek.roborockathome.ui.common.LoadingLine
import com.espotek.roborockathome.ui.common.RoomColours
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.RahColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * [robot]'s floor plan, edge to edge in its own window: the plan in its room colours with the
 * [selected] rooms picked out, the job's [path], no-go zones and virtual walls, the room names that
 * fit, the dock, and the robot with its heading, or the last-seen pin while its position is old.
 * A tap on a room hands it to [onPick]; drags and pinches are [planGestures]'s. [plan] is the plan
 * for the robot's current map, or null while it is on its way; [colours] is null only then.
 */
@Composable
internal fun FloorPlanArea(
    robot: Robot,
    plan: Plan?,
    colours: RoomColours?,
    selected: Set<Int>,
    path: RobotPath?,
    state: PlanState,
    page: NestedScrollDispatcher,
    words: TimeWords,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Rah.colors.mapBackground).clipToBounds()) {
        val map = robot.map
        when {
            map == null -> PlanMessage("No map yet", "The map appears after ${robot.name} has cleaned once.")
            plan == null -> LoadingLine("Loading the map", Modifier.fillMaxSize().padding(Rah.space.s24))
            else -> PlanCanvas(
                robot, map, plan, checkNotNull(colours) { "A delivered plan always has its room colours" },
                selected, path, state, page, words, onPick,
            )
        }
    }
}

@Composable
private fun PlanMessage(title: String, detail: String) {
    val c = Rah.colors
    Column(
        Modifier.fillMaxSize().padding(Rah.space.s24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Rah.space.s8, Alignment.CenterVertically),
    ) {
        GlyphIcon(Glyph.Map, contentDescription = null, tint = c.textQuiet, size = 40.dp)
        Text(title, style = Rah.type.sectionHeading, color = c.text, textAlign = TextAlign.Center)
        Text(detail, style = Rah.type.supporting, color = c.textSupporting, textAlign = TextAlign.Center)
    }
}

// A room name measured for the plan, with its room's box in map pixels.
private class MeasuredLabel(val room: Int, val box: RoomBox, val text: TextLayoutResult)

// How far a room name's halo reaches out from its letters, the same at any zoom.
private val NAME_HALO = 2.dp

@Composable
private fun PlanCanvas(
    robot: Robot,
    map: RobotMap,
    plan: Plan,
    colours: RoomColours,
    selected: Set<Int>,
    path: RobotPath?,
    state: PlanState,
    page: NestedScrollDispatcher,
    words: TimeWords,
    onPick: (Int) -> Unit,
) {
    val c = Rah.colors
    // The page's path: the accent, or in dark a pale neutral, which reads on every room tone.
    val trailInk = if (Rah.dark) c.text.copy(alpha = 0.7f) else c.accent.copy(alpha = 0.6f)
    val density = LocalDensity.current.density
    val floor = remember(plan) { FloorPlan(plan.w, plan.h, plan.bytes) }
    val image = remember(floor, colours, selected) {
        val pixels = paintFloorPlan(floor, colours.tones, colours.slots, selected)
        Bitmap.createBitmap(pixels, plan.w, plan.h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    SideEffect { state.map = IntSize(plan.w, plan.h) }

    val measurer = rememberTextMeasurer()
    val labelStyle = Rah.type.navigation.copy(color = c.text)
    val labels = remember(robot.rooms, map.rooms, selected, labelStyle, measurer) {
        robot.rooms.mapNotNull { room ->
            // A room the robot lists can be missing from this plan; it has no name on it then.
            val g = map.rooms[room.id] ?: return@mapNotNull null
            val style = if (room.id in selected) labelStyle.copy(fontWeight = FontWeight.Bold) else labelStyle
            MeasuredLabel(
                room.id,
                RoomBox(g.cx, g.cy, g.x0.toDouble(), g.y0.toDouble(), g.x1.toDouble(), g.y1.toDouble()),
                measurer.measure(room.name, style, softWrap = false, maxLines = 1),
            )
        }
    }
    val line = remember(path) { path?.let { trail(it.points) } }
    val pos = robot.pos
    val stale = pos != null && !pos.live
    val moving = pos != null && pos.live && (robot.activity == Activity.Cleaning || robot.activity == Activity.Returning)
    val caption = if (pos != null && stale) {
        val seen = words.lastSeen(pos.ts)
        val style = labelStyle.copy(color = c.onAttention, fontWeight = FontWeight.Bold)
        remember(seen, style, measurer) { measurer.measure(seen, style, softWrap = false, maxLines = 1) }
    } else {
        null
    }
    val pulse = if (moving || stale) rememberInfiniteTransition(label = "marker") else null
    val halo = pulse?.wave(2200)
    val ring = pulse?.wave(2400)
    val bolt = painterResource(Glyph.Bolt.drawable)
    val pin = remember { PathParser().parsePathString(PIN).toPath() }
    val heading = remember { Path().apply { moveTo(13f, 0f); lineTo(7.5f, -4.2f); lineTo(7.5f, 4.2f); close() } }

    val hostView = LocalView.current
    val ids = remember(robot.rooms) { robot.rooms.mapTo(HashSet()) { it.id } }
    val tap by rememberUpdatedState { at: Offset ->
        val v = checkNotNull(state.viewOrNull) { "A tap on the plan means it is on screen" }
        val room = roomAtOrNull(floor, ids, v.mapX(at.x / density.toDouble()), v.mapY(at.y / density.toDouble()), v.reach)
        if (room != null) {
            hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onPick(room)
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { state.window = Size(it.width / density, it.height / density) }
            .planGestures(state, page) { tap(it) }
            .semantics { contentDescription = "Floor plan. Tap a room to select it." },
    ) {
        val v = state.viewOrNull ?: return@Canvas
        val s = (v.scale * density).toFloat()
        val tx = (v.tx * density).toFloat()
        val ty = (v.ty * density).toFloat()
        fun onScreen(x: Double, y: Double) = Offset(x.toFloat() * s + tx, y.toFloat() * s + ty)

        withTransform({
            translate(tx, ty)
            scale(s, s, Offset.Zero)
        }) {
            drawImage(image, dstSize = IntSize(plan.w, plan.h), filterQuality = FilterQuality.None)
            for (zone in map.noGo) noGoZone(zone, c.attention, s)
            for (w in map.walls) {
                drawLine(
                    c.attention, Offset(w[0].toFloat(), w[1].toFloat()), Offset(w[2].toFloat(), w[3].toFloat()),
                    strokeWidth = 2.5.dp.toPx() / s, cap = StrokeCap.Round,
                )
            }
            if (line != null) drawPath(line, trailInk, style = Stroke(1.5.dp.toPx() / s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // The caption is kept inside the window, so a long one at large text is not cut off by its
        // edge, and the pin and caption take their room from the names for as long as the robot is lost.
        val at = pos?.let { onScreen(it.x, it.y) }
        val captionLeft = if (at != null && caption != null) {
            val w = caption.size.width + 18.dp.toPx()
            max(4.dp.toPx(), min(at.x - w / 2, size.width - 4.dp.toPx() - w))
        } else {
            0f
        }
        val pinRect = if (at != null && caption != null) {
            val left = min(at.x - 17.dp.toPx(), captionLeft)
            val right = max(at.x + 17.dp.toPx(), captionLeft + caption.size.width + 18.dp.toPx())
            val top = at.y - 44.dp.toPx()
            val bottom = at.y + 16.dp.toPx() + caption.size.height
            ScreenRect(left / density.toDouble(), top / density.toDouble(), (right - left) / density.toDouble(), (bottom - top) / density.toDouble())
        } else {
            null
        }
        // Each name has a halo in its room's colour as drawn, which takes part in the fit and the overlaps.
        val outline = NAME_HALO.toPx()
        val placed = layoutLabels(
            labels.map {
                val w = (it.text.size.width + 2 * outline) / density.toDouble()
                val h = (it.text.size.height + 2 * outline) / density.toDouble()
                LabelSpec(it.room, it.box, w, h)
            },
            v.scale, v.tx, v.ty, pinRect,
        )
        val byRoom = labels.associateBy { it.room }
        for (p in placed) {
            val text = byRoom.getValue(p.room).text
            val tone = colours.of(p.room)
            val corner = Offset(p.x.toFloat() * density + outline, p.y.toFloat() * density + outline)
            drawText(text, Color(if (p.room in selected) tone.selected else tone.normal), corner, drawStyle = Stroke(2 * outline, join = StrokeJoin.Round))
            // The stroke stays on the layout's paint until another style is given.
            drawText(text, topLeft = corner, drawStyle = Fill)
        }

        map.charger?.let { dock(onScreen(it.x, it.y), bolt, c) }
        if (pos != null && at != null) {
            if (caption != null) {
                lastSeenPin(at, pin, caption, captionLeft, ring?.value ?: 0f, c)
            } else {
                if (halo != null) {
                    val t = halo.value
                    drawCircle(c.accent.copy(alpha = 0.35f * (1 - t)), radius = 26.dp.toPx() * (0.45f + 0.75f * t), center = at)
                }
                robotMarker(at, pos.angle, heading, c)
            }
        }
    }
}

// The page's pulses (the moving robot's halo, the lost robot's ring): 0 to 1, easing out, over and over.
@Composable
private fun InfiniteTransition.wave(millis: Int): State<Float> =
    animateFloat(0f, 1f, infiniteRepeatable(tween(millis, easing = EaseOut)), label = "pulse")

// The page's pin, in its own 34 x 46 grid with the point at (17, 44.5).
private const val PIN = "M17 44.5S3 29.5 3 17a14 14 0 0 1 28 0c0 12.5-14 27.5-14 27.5z"

private fun trail(points: FloatArray): Path = Path().apply {
    var i = 0
    while (i + 1 < points.size) {
        if (i == 0) moveTo(points[0], points[1]) else lineTo(points[i], points[i + 1])
        i += 2
    }
}

// A no-go zone in map pixels: hatched in the attention colour, outlined. [s] is px per map pixel.
private fun DrawScope.noGoZone(corners: List<Double>, attention: Color, s: Float) {
    val zone = Path().apply {
        moveTo(corners[0].toFloat(), corners[1].toFloat())
        var i = 2
        while (i + 1 < corners.size) {
            lineTo(corners[i].toFloat(), corners[i + 1].toFloat())
            i += 2
        }
        close()
    }
    val xs = corners.filterIndexed { i, _ -> i % 2 == 0 }
    val ys = corners.filterIndexed { i, _ -> i % 2 == 1 }
    val x0 = xs.min().toFloat()
    val x1 = xs.max().toFloat()
    val y0 = ys.min().toFloat()
    val y1 = ys.max().toFloat()
    // Lines x + y = k, 3 map pixels apart, as the page's hatch pattern turned 45 degrees.
    val step = 3f * sqrt(2f)
    clipPath(zone) {
        var k = floor((x0 + y0) / step) * step
        val end = ceil((x1 + y1) / step) * step
        while (k <= end) {
            drawLine(attention.copy(alpha = 0.4f), Offset(k - y0, y0), Offset(k - y1, y1), strokeWidth = 1f)
            k += step
        }
    }
    drawPath(zone, attention.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx() / s))
}

// The dock: a small tile with the bolt, ringed so it shows on any room colour.
private fun DrawScope.dock(at: Offset, bolt: Painter, c: RahColors) {
    val half = 12.dp.toPx()
    val ring = 1.5.dp.toPx()
    drawRoundRect(c.textSupporting, at - Offset(half + ring, half + ring), Size(2 * (half + ring), 2 * (half + ring)), CornerRadius(7.dp.toPx() + ring))
    drawRoundRect(c.surface, at - Offset(half, half), Size(2 * half, 2 * half), CornerRadius(7.dp.toPx()))
    val icon = 15.dp.toPx()
    translate(at.x - icon / 2, at.y - icon / 2) {
        with(bolt) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(c.text)) }
    }
}

// The robot as the page draws it, in its 34 dp grid: a disc in the accent with a pointer at its
// heading. The robot reports its angle with y up; the screen has y down.
private fun DrawScope.robotMarker(at: Offset, angle: Int?, heading: Path, c: RahColors) {
    val d = density
    withTransform({
        translate(at.x, at.y)
        rotate(-(angle ?: 0).toFloat(), Offset.Zero)
        scale(d, d, Offset.Zero)
    }) {
        drawCircle(c.accent, 13f, Offset.Zero)
        drawCircle(c.surface, 13f, Offset.Zero, style = Stroke(2.5f))
        drawCircle(c.surface.copy(alpha = 0.9f), 4.6f, Offset(-1.5f, 0f))
        if (angle != null) drawPath(heading, c.surface)
    }
}

// Where a robot that no longer answers was last seen: a widening ring, the pin, and its caption.
private fun DrawScope.lastSeenPin(at: Offset, pin: Path, caption: TextLayoutResult, captionLeft: Float, ring: Float, c: RahColors) {
    val k = 0.3f + 1.3f * ring
    drawCircle(c.attention.copy(alpha = 0.9f * (1 - ring)), radius = 20.75.dp.toPx() * k, center = at, style = Stroke(2.5.dp.toPx() * k))
    val d = density
    withTransform({
        translate(at.x - 17 * d, at.y - 44 * d)
        scale(d, d, Offset.Zero)
    }) {
        drawPath(pin, c.attention)
        drawPath(pin, c.surface, style = Stroke(2.2f))
        drawCircle(c.surface, 5.5f, Offset(17f, 17f))
    }
    val w = caption.size.width + 18.dp.toPx()
    val h = caption.size.height + 6.dp.toPx()
    val top = at.y + 10.dp.toPx()
    drawRoundRect(c.attention, Offset(captionLeft, top), Size(w, h), CornerRadius(h / 2))
    drawText(caption, topLeft = Offset(captionLeft + 9.dp.toPx(), top + 3.dp.toPx()))
}
