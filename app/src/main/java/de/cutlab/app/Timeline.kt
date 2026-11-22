package de.cutlab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

data class TItem(
    val id: Long, val kind: Kind, val lane: Int, val startMs: Long, val durMs: Long, val label: String,
    val uri: String? = null, val inMs: Long = 0
)

private data class RowSpec(val kind: Kind, val lane: Int, val label: String, val color: Color)

private val ROW = 30.dp          // Hoehe eines Elements
private val STRIDE = 32.dp       // Abstand von Spur zu Spur
private val RULER = 20.dp        // Zeitleiste oben
private val LABEL_W = 58.dp      // Spurnamen links
private const val MAX_LANE = 5

private fun colorFor(kind: Kind, lane: Int): Color = when (kind) {
    Kind.CLIP -> Color(0xFF3D6FD8)
    Kind.VIDEO -> if (lane % 2 == 0) Color(0xFF2E9CCA) else Color(0xFF5C6BC0)
    Kind.SUB -> Color(0xFFE0A526)
    Kind.TEXT -> Color(0xFF8E55D6)
    Kind.IMAGE -> Color(0xFF2FA36B)
    Kind.AUDIO -> Color(0xFFD64F86)
}

/** Nur benutzte Spuren werden gezeigt (Video 1 immer). Hoehere Videospuren oben, Ton ganz unten. */
private fun buildRows(p: Project): List<RowSpec> {
    val rows = ArrayList<RowSpec>()
    p.videos.map { it.track }.distinct().sortedDescending().forEach { rows.add(RowSpec(Kind.VIDEO, it, "Video ${it + 2}", colorFor(Kind.VIDEO, it))) }
    rows.add(RowSpec(Kind.CLIP, 0, "Video 1", colorFor(Kind.CLIP, 0)))
    if (p.subs.isNotEmpty()) rows.add(RowSpec(Kind.SUB, 0, "Untertitel", colorFor(Kind.SUB, 0)))
    p.texts.map { it.lane }.distinct().sortedDescending().forEach { rows.add(RowSpec(Kind.TEXT, it, "Text ${it + 1}", colorFor(Kind.TEXT, it))) }
    p.images.map { it.lane }.distinct().sortedDescending().forEach { rows.add(RowSpec(Kind.IMAGE, it, "Bild ${it + 1}", colorFor(Kind.IMAGE, it))) }
    p.audios.map { it.lane }.distinct().sorted().forEach { rows.add(RowSpec(Kind.AUDIO, it, "Ton ${it + 1}", colorFor(Kind.AUDIO, it))) }
    return rows
}

private fun buildItems(p: Project): List<TItem> {
    val out = ArrayList<TItem>()
    p.clips.sortedBy { it.startMs }.forEachIndexed { i, c -> out.add(TItem(c.id, Kind.CLIP, 0, c.startMs, c.durMs, "Clip ${i + 1}", c.uri, c.inMs)) }
    p.videos.forEach { out.add(TItem(it.id, Kind.VIDEO, it.track, it.startMs, it.durMs, "Overlay", it.uri, it.inMs)) }
    p.subs.forEach { out.add(TItem(it.id, Kind.SUB, 0, it.startMs, it.endMs - it.startMs, it.text)) }
    p.texts.forEach { out.add(TItem(it.id, Kind.TEXT, it.lane, it.startMs, it.durMs, it.text)) }
    p.images.forEach { out.add(TItem(it.id, Kind.IMAGE, it.lane, it.startMs, it.durMs, "Bild")) }
    p.audios.forEach { out.add(TItem(it.id, Kind.AUDIO, it.lane, it.startMs, it.durMs, it.name)) }
    return out
}

@Composable
fun Timeline(vm: EditorViewModel, modifier: Modifier = Modifier, onEdit: (Selection) -> Unit = {}) {
    val density = LocalDensity.current
    val dpPerMs = vm.zoom / 1000f
    val pxPerMs = with(density) { dpPerMs.dp.toPx() }
    val p = vm.project
    val scroll = rememberScrollState()       // waagerecht (Zeit)
    val vScroll = rememberScrollState()      // senkrecht (Spuren), gemeinsam mit den Spurnamen
    val sel = vm.selection

    ScrollSync(vm, scroll, pxPerMs)

    val rows = remember(p.videos, p.texts, p.images, p.audios, p.subs) { buildRows(p) }
    val items = remember(p) { buildItems(p) }
    val rowIndex = remember(rows) { rows.withIndex().associate { (it.value.kind to it.value.lane) to it.index } }

    BoxWithConstraints(modifier.background(Color(0xFF181820))) {
        val viewW = maxWidth - LABEL_W
        val half = viewW / 2
        val viewPx = with(density) { viewW.toPx() }
        val contentW = half * 2 + (p.totalMs * dpPerMs).dp
        val contentH = RULER + STRIDE * rows.size

        Row(Modifier.fillMaxSize()) {
            // Spurnamen: scrollen nur senkrecht mit
            Column(Modifier.width(LABEL_W).fillMaxHeight().background(Color(0xFF14141A)).verticalScroll(vScroll)) {
                Spacer(Modifier.height(RULER))
                rows.forEach { r ->
                    Box(Modifier.height(STRIDE).fillMaxWidth().padding(horizontal = 5.dp), contentAlignment = Alignment.CenterStart) {
                        Text(r.label, color = r.color, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }

            Box(
                Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    // Zwei Finger = Zoom der Timeline (Playhead bleibt in der Mitte)
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                val z = ev.calculateZoom()
                                if (z != 1f) {
                                    vm.zoom = (vm.zoom * z).coerceIn(2f, 400f)
                                    ev.changes.forEach { it.consume() }
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
            ) {
                Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(scroll)) {
                    Box(Modifier.width(contentW).height(contentH)) {
                        Ruler(p.totalMs, vm.zoom, pxPerMs, half)
                        rows.forEachIndexed { i, _ ->
                            Box(
                                Modifier.offset(y = RULER + STRIDE * i).fillMaxWidth().height(ROW)
                                    .background(Color(0x14FFFFFF))
                                    .pointerInput(Unit) { detectTapGestures { vm.select(null) } }
                            )
                        }
                        // Alle Elemente liegen in EINER Ebene, damit ein Element beim Spurwechsel nicht neu aufgebaut wird
                        items.forEach { item ->
                            val ri = rowIndex[item.kind to item.lane]
                            if (ri != null) {
                                key(item.id) {
                                    ItemBox(
                                        item, rows[ri].color, half, dpPerMs, pxPerMs, RULER + STRIDE * ri,
                                        sel?.kind == item.kind && sel.id == item.id, vm, scroll, viewPx
                                    )
                                }
                            }
                        }
                    }
                }

                // gelbe Einrast-Linie, wenn ein Element an etwas andockt
                Canvas(Modifier.fillMaxSize()) {
                    val g = vm.snapGuideMs
                    if (g != null) {
                        val x = half.toPx() + g * pxPerMs - scroll.value
                        drawLine(Color(0xFFFFEB3B), Offset(x, 0f), Offset(x, size.height), 3f)
                    }
                }
                // feststehender Playhead in der Mitte; beim Verschieben/Kuerzen rot mit Zeitanzeige
                val dragging = vm.dragInfo != null
                Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(if (dragging) Color(0xFFFF3B30) else Color.White))
                if (dragging) PlayheadTime(vm, Modifier.align(Alignment.TopCenter).padding(top = RULER))
                // Bearbeiten / Loeschen fuer das ausgewaehlte Element
                val s = vm.selection
                if (s != null) {
                    Row(
                        Modifier.align(Alignment.TopStart).padding(4.dp)
                            .background(Color(0xDD000000), RoundedCornerShape(20.dp)).padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (s.kind == Kind.TEXT || s.kind == Kind.SUB) {
                            IconButton(onClick = { onEdit(s) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Edit, "Text bearbeiten", tint = Color.White) }
                        }
                        IconButton(onClick = { vm.deleteSelected() }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Delete, "Loeschen", tint = Color(0xFFFF8A80)) }
                    }
                }
            }
        }
    }
}

/** Haelt Scrollposition und Abspielposition synchron (eigene Funktion, damit nur sie bei jedem Tick neu zusammengesetzt wird). */
@Composable
private fun ScrollSync(vm: EditorViewModel, scroll: ScrollState, pxPerMs: Float) {
    val pxState by rememberUpdatedState(pxPerMs)
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.collect { v ->
            if (scroll.isScrollInProgress) vm.seekTo((v / pxState).toLong())
        }
    }
    LaunchedEffect(vm.posMs, pxPerMs) {
        if (!scroll.isScrollInProgress) scroll.scrollTo((vm.posMs * pxPerMs).roundToInt())
    }
}

@Composable
private fun Ruler(totalMs: Long, dpPerSec: Float, pxPerMs: Float, half: Dp) {
    val paint = remember { android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f; isAntiAlias = true } }
    Canvas(Modifier.fillMaxWidth().height(RULER)) {
        val step = if (dpPerSec < 4f) 60000L else if (dpPerSec < 10f) 30000L else if (dpPerSec < 25f) 10000L else if (dpPerSec < 40f) 5000L else if (dpPerSec > 200f) 250L else 1000L
        val h0 = half.toPx()
        var t = 0L
        while (t <= totalMs) {
            val x = h0 + t * pxPerMs
            drawLine(Color.Gray, Offset(x, size.height - 8.dp.toPx()), Offset(x, size.height), 2f)
            if (t % 1000L == 0L) {
                val label = if (t >= 60000L) "%d:%02d".format(t / 60000L, (t / 1000L) % 60L) else "${t / 1000}s"
                drawIntoCanvas { it.nativeCanvas.drawText(label, x + 4f, 20f, paint) }
            }
            t += step
        }
    }
}

@Composable
private fun ItemBox(
    item: TItem, color: Color, half: Dp, dpPerMs: Float, pxPerMs: Float, rowY: Dp, sel: Boolean,
    vm: EditorViewModel, scroll: ScrollState, viewPx: Float
) {
    val shape = RoundedCornerShape(6.dp)
    val px by rememberUpdatedState(pxPerMs)
    val itemNow by rememberUpdatedState(item)
    val stridePx by rememberUpdatedState(with(LocalDensity.current) { STRIDE.toPx() })
    Box(
        Modifier
            .zIndex(if (sel) 1f else 0f)
            .offset(x = half + (item.startMs * dpPerMs).dp, y = rowY)
            .width((item.durMs * dpPerMs).dp.coerceAtLeast(12.dp))
            .height(ROW)
            .clip(shape)
            .background(color)
            .then(if (sel) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .pointerInput(item.id) { detectTapGestures { vm.select(Selection(itemNow.kind, itemNow.id)) } }
            .pointerInput(item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        vm.select(Selection(itemNow.kind, itemNow.id))
                        vm.beginDrag()
                        vm.beginFine(itemNow.startMs)     // Lange gedrueckt = Feinmodus
                    },
                    onDragEnd = { vm.endDrag() },
                    onDragCancel = { vm.endDrag() },
                    onDrag = { ch, d ->
                        ch.consume()
                        vm.moveItem(itemNow.kind, itemNow.id, d.x / px)
                        vm.dragLane(itemNow.kind, itemNow.id, d.y, stridePx)
                    }
                )
            }
    ) {
        if (item.uri != null) ThumbStrip(item, vm, scroll, half, px, viewPx)
        Text(
            item.label, color = Color.White, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart)
                .padding(start = if (sel) 20.dp else 4.dp, end = 4.dp)
                .then(if (item.uri != null) Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp) else Modifier)
        )
        if (sel) {
            Handle(Alignment.CenterStart, px, vm, { itemNow.startMs }) { vm.trimStart(itemNow.kind, itemNow.id, it) }
            Handle(Alignment.CenterEnd, px, vm, { itemNow.startMs + itemNow.durMs }) { vm.trimEnd(itemNow.kind, itemNow.id, it) }
        }
    }
}

@Composable
private fun BoxScope.Handle(align: Alignment, pxPerMs: Float, vm: EditorViewModel, edgeMs: () -> Long, onDeltaMs: (Float) -> Unit) {
    val px by rememberUpdatedState(pxPerMs)
    val cb by rememberUpdatedState(onDeltaMs)
    val edge by rememberUpdatedState(edgeMs)
    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        Modifier.align(align).width(24.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Text(if (align == Alignment.CenterStart) "\u2039" else "\u203A", color = Color(0xFF202020), fontSize = 20.sp)
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()                       // sofort beanspruchen, damit Scrollen nicht dazwischenfunkt
                    var started = false
                    var acc = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.positionChange().x
                        if (!started) {
                            acc += dx
                            if (kotlin.math.abs(acc) > slop) {
                                started = true
                                vm.beginDrag(); vm.beginFine(edge())      // Anfassen = Feinmodus (zoomt heran)
                            }
                        } else if (dx != 0f) cb(dx / px)
                        ch.consume()
                    }
                    if (started) vm.endDrag()
                }
            }
        )
    }
}

/** Zeichnet nur die gerade sichtbaren Vorschaubilder eines Clips. */
@Composable
private fun ThumbStrip(item: TItem, vm: EditorViewModel, scroll: ScrollState, half: Dp, pxPerMs: Float, viewPx: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val tileW = 48.dp.toPx()
        val itemLeft = half.toPx() + item.startMs * pxPerMs
        val lo = (scroll.value - itemLeft).coerceAtLeast(0f)
        val hi = minOf(size.width, scroll.value + viewPx - itemLeft)
        if (hi <= lo || size.height <= 0f) return@Canvas
        val tileAspect = tileW / size.height
        var i = (lo / tileW).toInt()
        while (i * tileW < hi) {
            val srcMs = item.inMs + ((i * tileW + tileW / 2) / pxPerMs).toLong()
            val bmp = vm.thumbs.get(item.uri!!, (srcMs / 1000).toInt())
            val dx = i * tileW
            val dw = minOf(tileW, size.width - dx)
            if (bmp != null && dw >= 1f) {
                val bAspect = bmp.width.toFloat() / bmp.height
                var sw: Int; var sh: Int
                if (bAspect > tileAspect) { sh = bmp.height; sw = (bmp.height * tileAspect).toInt() }
                else { sw = bmp.width; sh = (bmp.width / tileAspect).toInt() }
                sw = (sw * dw / tileW).toInt().coerceAtLeast(1)
                sh = sh.coerceAtLeast(1)
                drawImage(
                    bmp,
                    srcOffset = IntOffset((bmp.width - sw) / 2, (bmp.height - sh) / 2),
                    srcSize = IntSize(sw, sh),
                    dstOffset = IntOffset(dx.roundToInt(), 0),
                    dstSize = IntSize(dw.roundToInt(), size.height.roundToInt())
                )
            }
            i++
        }
    }
}

@Composable
private fun PlayheadTime(vm: EditorViewModel, modifier: Modifier) {
    Text(
        fmtTime(vm.posMs), color = Color.White, fontSize = 12.sp,
        modifier = modifier.background(Color(0xFFFF3B30), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
