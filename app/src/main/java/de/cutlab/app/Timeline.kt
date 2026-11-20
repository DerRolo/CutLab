package de.cutlab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

data class TItem(val id: Long, val kind: Kind, val startMs: Long, val durMs: Long, val label: String)

private val rowH = 36.dp

@Composable
fun Timeline(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val dpPerMs = vm.zoom / 1000f
    val pxPerMs = with(density) { dpPerMs.dp.toPx() }
    val p = vm.project
    val scroll = rememberScrollState()
    val sel = vm.selection

    ScrollSync(vm, scroll, pxPerMs)

    val clipItems = remember(p.clips) {
        var acc = 0L
        p.clips.mapIndexed { i, c -> TItem(c.id, Kind.CLIP, acc, c.durMs, "Clip ${i + 1}").also { acc += c.durMs } }
    }
    val subItems = remember(p.subs) { p.subs.map { TItem(it.id, Kind.SUB, it.startMs, it.endMs - it.startMs, it.text) } }
    val textItems = remember(p.texts) { p.texts.map { TItem(it.id, Kind.TEXT, it.startMs, it.durMs, it.text) } }
    val imageItems = remember(p.images) { p.images.map { TItem(it.id, Kind.IMAGE, it.startMs, it.durMs, "Bild") } }
    val audioItems = remember(p.audios) { p.audios.map { TItem(it.id, Kind.AUDIO, it.startMs, it.durMs, it.name) } }

    BoxWithConstraints(modifier.background(Color(0xFF181820))) {
        val half = maxWidth / 2
        val contentW = half * 2 + (p.totalMs * dpPerMs).dp
        Column(
            Modifier.horizontalScroll(scroll).width(contentW).padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Ruler(p.totalMs, vm.zoom, pxPerMs, half)
            Track(clipItems, Color(0xFF3D6FD8), half, dpPerMs, pxPerMs, sel, vm)
            Track(subItems, Color(0xFFE0A526), half, dpPerMs, pxPerMs, sel, vm)
            Track(textItems, Color(0xFF8E55D6), half, dpPerMs, pxPerMs, sel, vm)
            Track(imageItems, Color(0xFF2FA36B), half, dpPerMs, pxPerMs, sel, vm)
            Track(audioItems, Color(0xFFD64F86), half, dpPerMs, pxPerMs, sel, vm)
        }
        // feststehender Playhead in der Mitte
        Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White))
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
    selected: Selection?, vm: EditorViewModel
) {
    Box(Modifier.fillMaxWidth().height(rowH).pointerInput(Unit) { detectTapGestures { vm.select(null) } }) {
        items.forEach { item ->
            key(item.id) { ItemBox(item, color, half, dpPerMs, pxPerMs, selected?.kind == item.kind && selected.id == item.id, vm) }
        }
    }
}

@Composable
private fun ItemBox(item: TItem, color: Color, half: Dp, dpPerMs: Float, pxPerMs: Float, sel: Boolean, vm: EditorViewModel) {
    val shape = RoundedCornerShape(6.dp)
    val px by rememberUpdatedState(pxPerMs)
    Box(
        Modifier
            .offset(x = half + (item.startMs * dpPerMs).dp)
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
        Text(
            item.label, color = Color.White, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = if (sel) 20.dp else 8.dp, end = 4.dp)
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
        Modifier.align(align).width(16.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.85f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { vm.beginDrag() },
                    onDragEnd = { vm.endDrag() },
                    onDragCancel = { vm.endDrag() }
                ) { ch, dx -> ch.consume(); cb(dx / px) }
            }
    )
}
