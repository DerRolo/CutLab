package de.cutlab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.zIndex
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

data class TItem(
    val id: Long, val kind: Kind, val startMs: Long, val durMs: Long, val label: String,
    val uri: String? = null, val inMs: Long = 0
)

private val rowH = 36.dp

@Composable
fun Timeline(vm: EditorViewModel, modifier: Modifier = Modifier, onEdit: (Selection) -> Unit = {}) {
    val density = LocalDensity.current
    val dpPerMs = vm.zoom / 1000f
    val pxPerMs = with(density) { dpPerMs.dp.toPx() }
    val p = vm.project
    val scroll = rememberScrollState()
    val sel = vm.selection

    ScrollSync(vm, scroll, pxPerMs)

    val clipItems = remember(p.clips) {
        var acc = 0L
        p.clips.mapIndexed { i, c -> TItem(c.id, Kind.CLIP, acc, c.durMs, "Clip ${i + 1}", c.uri, c.inMs).also { acc += c.durMs } }
    }
    val video2Items = remember(p.videos) { p.videos.filter { it.track == 0 }.map { TItem(it.id, Kind.VIDEO, it.startMs, it.durMs, "Video 2", it.uri, it.inMs) } }
    val video3Items = remember(p.videos) { p.videos.filter { it.track == 1 }.map { TItem(it.id, Kind.VIDEO, it.startMs, it.durMs, "Video 3", it.uri, it.inMs) } }
    val subItems = remember(p.subs) { p.subs.map { TItem(it.id, Kind.SUB, it.startMs, it.endMs - it.startMs, it.text) } }
    val textItems = remember(p.texts) { p.texts.map { TItem(it.id, Kind.TEXT, it.startMs, it.durMs, it.text) } }
    val imageItems = remember(p.images) { p.images.map { TItem(it.id, Kind.IMAGE, it.startMs, it.durMs, "Bild") } }
    val audioItems = remember(p.audios) { p.audios.map { TItem(it.id, Kind.AUDIO, it.startMs, it.durMs, it.name) } }

    BoxWithConstraints(
        modifier.background(Color(0xFF181820)).pointerInput(Unit) {
            // Zwei Finger = Zoom der Timeline (Playhead bleibt in der Mitte)
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.changes.count { it.pressed } >= 2) {
                        val z = ev.calculateZoom()
                        if (z != 1f) {
                            vm.zoom = (vm.zoom * z).coerceIn(15f, 400f)
                            ev.changes.forEach { it.consume() }
                        }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }
    ) {
        val half = maxWidth / 2
        val contentW = half * 2 + (p.totalMs * dpPerMs).dp
        val viewPx = with(density) { (half * 2).toPx() }
        Column(
            Modifier.verticalScroll(rememberScrollState()).horizontalScroll(scroll).width(contentW).padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Ruler(p.totalMs, vm.zoom, pxPerMs, half)
            Track(clipItems, Color(0xFF3D6FD8), half, dpPerMs, pxPerMs, sel, vm, scroll, viewPx)
            Track(video2Items, Color(0xFF2E9CCA), half, dpPerMs, pxPerMs, sel, vm, scroll, viewPx)
            Track(video3Items, Color(0xFF5C6BC0), half, dpPerMs, pxPerMs, sel, vm, scroll, viewPx)
            Track(subItems, Color(0xFFE0A526), half, dpPerMs, pxPerMs, sel, vm, scroll, viewPx)
            Track(textItems, Color(0xFF8E55D6), half, dpPerMs, pxPerMs, sel, vm, scroll, viewPx)
            Track(imageItems, Color(0xFF2FA36B), half, dpPerMs, pxPerMs, sel, vm, scroll, viewPx)
            Track(audioItems, Color(0xFFD64F86), half, dpPerMs, pxPerMs, sel, vm, scroll, viewPx)
        }
        // gelbe Einrast-Linie, wenn ein Element an etwas andockt
        Canvas(Modifier.fillMaxSize()) {
            val g = vm.snapGuideMs
            if (g != null) {
                val x = half.toPx() + g * pxPerMs - scroll.value
                drawLine(Color(0xFFFFEB3B), Offset(x, 0f), Offset(x, size.height), 3f)
            }
        }
        // feststehender Playhead in der Mitte
        Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White))
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
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val step = if (dpPerSec < 40f) 5000L else 1000L
        val h0 = half.toPx()
        var t = 0L
        while (t <= totalMs) {
            val x = h0 + t * pxPerMs
            drawLine(Color.Gray, Offset(x, size.height - 8.dp.toPx()), Offset(x, size.height), 2f)
            drawIntoCanvas { it.nativeCanvas.drawText("${t / 1000}s", x + 4f, 20f, paint) }
            t += step
        }
    }
}

@Composable
private fun Track(
    items: List<TItem>, color: Color, half: Dp, dpPerMs: Float, pxPerMs: Float,
    selected: Selection?, vm: EditorViewModel, scroll: ScrollState, viewPx: Float
) {
    Box(Modifier.fillMaxWidth().height(if (items.isEmpty()) 16.dp else rowH).pointerInput(Unit) { detectTapGestures { vm.select(null) } }) {
        items.forEach { item ->
            key(item.id) { ItemBox(item, color, half, dpPerMs, pxPerMs, selected?.kind == item.kind && selected.id == item.id, vm, scroll, viewPx) }
        }
    }
}

@Composable
private fun ItemBox(
    item: TItem, color: Color, half: Dp, dpPerMs: Float, pxPerMs: Float, sel: Boolean,
    vm: EditorViewModel, scroll: ScrollState, viewPx: Float
) {
    val shape = RoundedCornerShape(6.dp)
    val px by rememberUpdatedState(pxPerMs)
    val dragging = item.kind == Kind.CLIP && vm.dragClipId == item.id
    val dragDp = if (dragging) vm.dragClipMs * dpPerMs else 0f
    Box(
        Modifier
            .zIndex(if (dragging) 1f else 0f)
            .offset(x = half + (item.startMs * dpPerMs + dragDp).dp)
            .width((item.durMs * dpPerMs).dp.coerceAtLeast(12.dp))
            .fillMaxHeight()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(color)
            .then(if (sel) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .pointerInput(item.id) { detectTapGestures { vm.select(Selection(item.kind, item.id)) } }
            .pointerInput(item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { vm.select(Selection(item.kind, item.id)); vm.beginDrag() },
                    onDragEnd = { vm.endDrag() },
                    onDragCancel = { vm.endDrag() },
                    onDrag = { ch, d -> ch.consume(); vm.moveItem(item.kind, item.id, d.x / px) }
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
            Handle(Alignment.CenterStart, px, vm) { vm.trimStart(item.kind, item.id, it) }
            Handle(Alignment.CenterEnd, px, vm) { vm.trimEnd(item.kind, item.id, it) }
        }
    }
}

@Composable
private fun BoxScope.Handle(align: Alignment, pxPerMs: Float, vm: EditorViewModel, onDeltaMs: (Float) -> Unit) {
    val px by rememberUpdatedState(pxPerMs)
    val cb by rememberUpdatedState(onDeltaMs)
    Box(
        Modifier.align(align).width(18.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Text(if (align == Alignment.CenterStart) "\u2039" else "\u203A", color = Color(0xFF202020), fontSize = 18.sp)
        Box(
            Modifier.matchParentSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { vm.beginDrag() },
                    onDragEnd = { vm.endDrag() },
                    onDragCancel = { vm.endDrag() }
                ) { ch, dx -> ch.consume(); cb(dx / px) }
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
