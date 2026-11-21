package de.cutlab.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlin.math.hypot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import android.view.LayoutInflater
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

private val palette = listOf(0xFFFFFFFF, 0xFFFFD400, 0xFF00E5FF, 0xFFFF4081, 0xFF69F0AE, 0xFFFF5252).map { it.toInt() }

@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    var editing by remember { mutableStateOf<Selection?>(null) }
    var showLang by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var addedText by remember { mutableStateOf<Long?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.addVideos(it) }
    val pickOverlayVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::addOverlayVideo) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::addImage) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addAudio) }

    if (vm.showProjects) ProjectsScreen(vm)
    else Column(Modifier.fillMaxSize().background(Color(0xFF101014)).systemBarsPadding()) {
        TopBar(vm) { renaming = true }
        PreviewArea(vm, Modifier.weight(1f)) { editing = it }
        Transport(vm)
        Box(
            Modifier.fillMaxWidth().height(20.dp).background(Color(0xFF181820))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { ch, dy -> ch.consume(); vm.timelineDp = (vm.timelineDp - dy / density).coerceIn(80f, 360f) }
                },
            contentAlignment = Alignment.Center
        ) { Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Gray)) }
        Timeline(vm, Modifier.fillMaxWidth().height(vm.timelineDp.dp), onEdit = { editing = it })
        SelectionPanel(vm, onEditText = { editing = vm.selection }, onPresets = { showPresets = true })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xFF181820))) {
            Tool(Icons.Default.Movie, "Video") { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            Tool(Icons.Default.ContentCut, "Teilen") { vm.split() }
            Tool(Icons.Default.Title, "Text") { vm.addText()?.let { editing = Selection(Kind.TEXT, it); addedText = it } }
            Tool(Icons.Default.Layers, "Overlay") { pickOverlayVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            Tool(Icons.Default.Image, "Bild") { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            Tool(Icons.Default.MusicNote, "Musik") { pickAudio.launch(arrayOf("audio/*")) }
            Tool(Icons.Default.Subtitles, "Untertitel") { showLang = true }
            Tool(Icons.Default.Delete, "Loeschen") { vm.deleteSelected() }
        }
    }

    // Text bearbeiten
    editing?.let { sel ->
        val initial = when (sel.kind) {
            Kind.TEXT -> vm.project.texts.find { it.id == sel.id }?.text
            Kind.SUB -> vm.project.subs.find { it.id == sel.id }?.text
            else -> null
        }
        if (initial == null) editing = null
        else {
            var text by remember(sel) { mutableStateOf(initial) }
            fun cancelEdit() {
                if (sel.kind == Kind.TEXT && sel.id == addedText) { vm.select(sel); vm.deleteSelected() }
                addedText = null; editing = null
            }
            AlertDialog(
                onDismissRequest = { cancelEdit() },
                title = { Text("Text bearbeiten") },
                text = { OutlinedTextField(text, { text = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.setText(sel, text); addedText = null; editing = null }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { cancelEdit() }) { Text("Abbrechen") } }
            )
        }
    }

    // Untertitel erzeugen
    if (showLang) {
        var eng by remember { mutableStateOf(AsrEngine.WHISPER_SMALL) }
        var lang by remember { mutableStateOf(AsrLangs.list.first()) }
        var hint by remember { mutableStateOf("") }
        var langMenu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showLang = false },
            title = { Text("Untertitel automatisch erzeugen") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Genauigkeit", fontSize = 13.sp)
                    AsrEngine.values().forEach { e ->
                        Row(Modifier.fillMaxWidth().clickable { eng = e }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = eng == e, onClick = { eng = e })
                            Column {
                                Text(e.label)
                                Text(e.note, fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Sprache im Video", fontSize = 13.sp)
                    Box {
                        OutlinedButton(onClick = { langMenu = true }) { Text(lang.label) }
                        DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                            AsrLangs.list.forEach { l ->
                                DropdownMenuItem(text = { Text(l.label) }, onClick = { lang = l; langMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Namen / besondere Wörter (optional)", fontSize = 13.sp)
                    OutlinedTextField(
                        hint, { hint = it }, singleLine = true,
                        placeholder = { Text("z. B. Mainz, Wiesbaden, CutLab") },
                        enabled = eng != AsrEngine.VOSK
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Beim ersten Mal wird das Sprachmodell geladen, danach läuft alles offline. " +
                            "Das Erkennen kann bei langen Videos einige Minuten dauern.",
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLang = false; vm.generateSubtitles(eng, lang.code, hint) }) { Text("Starten") } },
            dismissButton = { TextButton(onClick = { showLang = false }) { Text("Abbrechen") } }
        )
    }

    // Fortschritt
    vm.busy?.let { title ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                if (vm.busyProgress >= 0f) LinearProgressIndicator(progress = { vm.busyProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { vm.cancelBusy() }) { Text("Abbrechen") } }
        )
    }

    if (showPresets) {
        var pname by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text("Untertitel-Vorlagen") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Aktuellen Stil speichern (Art, Größe, Position, Farben):", fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(pname, { pname = it }, singleLine = true, placeholder = { Text("Name") }, modifier = Modifier.weight(1f))
                        TextButton(enabled = pname.isNotBlank(), onClick = { vm.savePreset(pname.trim()); pname = "" }) { Text("Speichern") }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (vm.presets.isEmpty()) Text("Noch keine Vorlagen gespeichert.", fontSize = 12.sp, color = Color.Gray)
                    else Text("Antippen = anwenden.  Stern = Standard für neue Projekte.", fontSize = 11.sp, color = Color.Gray)
                    vm.presets.forEach { pr ->
                        Row(Modifier.fillMaxWidth().clickable { vm.applyPreset(pr); showPresets = false }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(pr.name)
                                Text(
                                    "${pr.style.kind.label} · Größe ${(pr.style.size * 100).roundToInt()} · Höhe ${(pr.style.y * 100).roundToInt()} %",
                                    fontSize = 11.sp, color = Color.Gray
                                )
                            }
                            IconButton(onClick = { vm.toggleDefaultPreset(pr.name) }) {
                                Icon(if (vm.defaultPreset == pr.name) Icons.Default.Star else Icons.Default.StarBorder, "Standard", tint = Color(0xFFFFD400))
                            }
                            IconButton(onClick = { vm.deletePreset(pr.name) }) { Icon(Icons.Default.Delete, "Löschen") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPresets = false }) { Text("Schließen") } }
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(vm.project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Projektname") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameProject(vm.currentId, name); renaming = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Abbrechen") } }
        )
    }

    vm.message?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun TopBar(vm: EditorViewModel, onRename: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.openList() }) { Icon(Icons.Default.FolderOpen, "Projekte", tint = Color.White) }
        Text(vm.project.name, color = Color.White, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f).clickable(onClick = onRename).padding(8.dp))
        IconButton(onClick = { vm.undo() }) { Icon(Icons.Default.Undo, "Rueckgaengig", tint = Color.White) }
        Button(onClick = { vm.export() }) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(4.dp)); Text("Export") }
    }
}

@Composable
private fun PreviewArea(vm: EditorViewModel, modifier: Modifier, onEdit: (Selection) -> Unit) {
    val p = vm.project
    val onEditNow by rememberUpdatedState(onEdit)
    val penPainter = rememberVectorPainter(Icons.Default.Edit)
    val trashPainter = rememberVectorPainter(Icons.Default.Delete)
    Box(modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        if (p.clips.isEmpty()) {
            Text("Tippe unten auf \"Video\", um zu starten", color = Color.Gray, textAlign = TextAlign.Center)
        } else {
            val touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.aspectRatio(p.aspect.ratio).clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures { off ->
                            val sel = vm.selection
                            val r = sel?.let { vm.painter.bounds[it.id] }
                            if (sel != null && r != null) {
                                // Tippposition in die (evtl. gezoomte) Vorschau umrechnen
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val x = (off.x - cx - vm.pox) / vm.pz + cx
                                val y = (off.y - cy - vm.poy) / vm.pz + cy
                                val rad = 14.dp.toPx()
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val hit = 22.dp.toPx()
                                val penC = Offset(r.right.coerceIn(rad, w - rad), r.top.coerceIn(rad, h - rad))
                                val delC = Offset(r.left.coerceIn(rad, w - rad), r.top.coerceIn(rad, h - rad))
                                if ((sel.kind == Kind.TEXT || sel.kind == Kind.SUB) && hypot(x - penC.x, y - penC.y) <= hit) {
                                    onEditNow(sel); return@detectTapGestures
                                }
                                if (hypot(x - delC.x, y - delC.y) <= hit) { vm.deleteSelected(); return@detectTapGestures }
                            }
                            vm.onPreviewTap()
                        }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val k = vm.selection?.kind
                            val overlay = k == Kind.TEXT || k == Kind.IMAGE || k == Kind.SUB || k == Kind.VIDEO
                            if (overlay) vm.beginTransform()
                            var moved = false
                            var acc = Offset.Zero
                            do {
                                val ev = awaitPointerEvent()
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                acc += pan
                                if (!moved && (acc.getDistance() > touchSlop || ev.changes.count { it.pressed } > 1)) moved = true
                                if (moved && (zoom != 1f || pan != Offset.Zero)) {
                                    if (overlay) vm.transform(pan.x / (size.width * vm.pz), pan.y / (size.height * vm.pz), zoom)
                                    else vm.zoomPreview(zoom, pan.x, pan.y, size.width.toFloat(), size.height.toFloat())
                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (ev.changes.any { it.pressed })
                            if (overlay) vm.endTransform()
                        }
                    }
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = vm.pz; scaleY = vm.pz; translationX = vm.pox; translationY = vm.poy
                    }
                ) {
                    AndroidView(
                        factory = { c ->
                            (LayoutInflater.from(c).inflate(R.layout.player_texture, null) as PlayerView).apply { player = vm.engine.player }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    OverlayVideos(vm)
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { vm.painter.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), vm.posMs, vm.project) }
                        val r = vm.selection?.let { vm.painter.bounds[it.id] }
                        if (r != null) drawRect(Color.White, Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2.dp.toPx()))
                        val selNow = vm.selection
                        if (r != null && selNow != null && !vm.dragging) {
                            val rad = 14.dp.toPx()
                            val ic = 18.dp.toPx()
                            if (selNow.kind == Kind.TEXT || selNow.kind == Kind.SUB) {
                                val c = Offset(r.right.coerceIn(rad, size.width - rad), r.top.coerceIn(rad, size.height - rad))
                                drawCircle(Color.White, rad, c)
                                translate(c.x - ic / 2, c.y - ic / 2) { with(penPainter) { draw(Size(ic, ic), colorFilter = ColorFilter.tint(Color.Black)) } }
                            }
                            val d = Offset(r.left.coerceIn(rad, size.width - rad), r.top.coerceIn(rad, size.height - rad))
                            drawCircle(Color(0xFFFF5252), rad, d)
                            translate(d.x - ic / 2, d.y - ic / 2) { with(trashPainter) { draw(Size(ic, ic), colorFilter = ColorFilter.tint(Color.White)) } }
                        }
                        if (vm.dragging) {
                            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))
                            val faint = Color.White.copy(alpha = 0.3f)
                            val kind = vm.selection?.kind
                            GUIDES_X.forEach { drawLine(faint, Offset(it * size.width, 0f), Offset(it * size.width, size.height), 2f, pathEffect = dash) }
                            GUIDES_Y.forEach { drawLine(faint, Offset(0f, it * size.height), Offset(size.width, it * size.height), 2f, pathEffect = dash) }
                            vm.snapX?.let { drawLine(Color(0xFF69F0AE), Offset(it * size.width, 0f), Offset(it * size.width, size.height), 3f) }
                            vm.snapY?.let { drawLine(Color(0xFF69F0AE), Offset(0f, it * size.height), Offset(size.width, it * size.height), 3f) }
                        }
                    }
                }
                if (vm.pz > 1.01f) {
                    Text(
                        "%.1fx  ✕".format(vm.pz), color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                            .clickable { vm.resetPreviewZoom() }.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Transport(vm: EditorViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.togglePlay() }) {
            Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White)
        }
        TimeLabel(vm)
        Spacer(Modifier.weight(1f))
        AspectPreset.values().forEach { a ->
            FilterChip(selected = vm.project.aspect == a, onClick = { vm.setAspect(a) }, label = { Text(a.label, fontSize = 11.sp) }, modifier = Modifier.padding(horizontal = 2.dp))
        }
        IconButton(onClick = { vm.zoom = (vm.zoom * 0.75f).coerceAtLeast(15f) }) { Icon(Icons.Default.ZoomOut, "Zoom -", tint = Color.White) }
        IconButton(onClick = { vm.zoom = (vm.zoom * 1.33f).coerceAtMost(400f) }) { Icon(Icons.Default.ZoomIn, "Zoom +", tint = Color.White) }
    }
}

@Composable
private fun TimeLabel(vm: EditorViewModel) {
    Text("${fmtTime(vm.posMs)} / ${fmtTime(vm.project.totalMs)}", color = Color.White, fontSize = 13.sp)
}

private val outlinePalette = listOf(0xFF000000, 0xFFFFFFFF, 0xFF1F2A44, 0x00000000).map { it.toInt() }

@Composable
private fun SelectionPanel(vm: EditorViewModel, onEditText: () -> Unit, onPresets: () -> Unit) {
    var subTab by remember { mutableIntStateOf(0) }
    val sel = vm.selection ?: return
    val p = vm.project
    Row(Modifier.fillMaxWidth().background(Color(0xFF20202A)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Column(Modifier.weight(1f)) {
            when (sel.kind) {
                Kind.TEXT -> p.texts.find { it.id == sel.id }?.let { t ->
                    TextButton(onClick = onEditText) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Text ändern") }
                    ColorRow("Schrift", t.color, palette) { vm.setTextColor(t.id, it) }
                    ColorRow("Kontur", t.outline, outlinePalette) { vm.setTextOutline(t.id, it) }
                    Slider(t.size, { vm.setTextSize(t.id, it) }, valueRange = 0.02f..0.2f)
                }
                Kind.VIDEO -> p.videos.find { it.id == sel.id }?.let { v ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = v.muted, onClick = { vm.setVideoMuted(v.id, !v.muted) }, label = { Text(if (v.muted) "Ton aus" else "Ton an", fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                        listOf(0L to "Ohne Fade", 300L to "Fade 0,3 s", 600L to "Fade 0,6 s", 1000L to "Fade 1 s").forEach { (ms, label) ->
                            FilterChip(selected = v.fadeMs == ms, onClick = { vm.setVideoFade(v.id, ms) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                        }
                        FilterChip(selected = v.track == 1, onClick = { vm.setVideoTrack(v.id, if (v.track == 0) 1 else 0) }, label = { Text(if (v.track == 0) "Nach oben (Spur 3)" else "Nach unten (Spur 2)", fontSize = 12.sp) })
                    }
                    Text("Größe (oder mit zwei Fingern in der Vorschau)", color = Color.Gray, fontSize = 11.sp)
                    Slider(v.scale, { vm.setVideoScale(v.id, it) }, valueRange = 0.1f..1.5f)
                }
                Kind.IMAGE -> p.images.find { it.id == sel.id }?.let { im ->
                    Text("Größe (oder mit zwei Fingern in der Vorschau)", color = Color.Gray, fontSize = 11.sp)
                    Slider(im.scale, { vm.setImageScale(im.id, it) }, valueRange = 0.05f..1.5f)
                }
                Kind.SUB -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        listOf("Stil", "Farbe", "Größe").forEachIndexed { i, label ->
                            FilterChip(selected = subTab == i, onClick = { subTab = i }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                        }
                        FilterChip(
                            selected = false, onClick = onPresets,
                            label = { Text("★ Vorlagen", fontSize = 12.sp) },
                            leadingIcon = null, modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    when (subTab) {
                        0 -> Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            SubKind.values().forEach { k ->
                                FilterChip(selected = p.subStyle.kind == k, onClick = { vm.setSubKind(k) }, label = { Text(k.label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                            }
                        }
                        1 -> Column {
                            ColorRow("Schrift", p.subStyle.color, palette) { vm.setSubColor(it) }
                            ColorRow("Aktives Wort", p.subStyle.highlight, palette) { vm.setSubHighlight(it) }
                            ColorRow("Kontur", p.subStyle.outline, outlinePalette) { vm.setSubOutline(it) }
                        }
                        else -> Slider(p.subStyle.size, { vm.setSubSize(it) }, valueRange = 0.025f..0.15f)
                    }
                }
                Kind.CLIP, Kind.AUDIO -> Text(
                    "Ränder ziehen = kürzen  |  lange drücken und ziehen = verschieben",
                    color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
        IconButton(onClick = { vm.deleteSelected() }) { Icon(Icons.Default.Delete, "Löschen", tint = Color(0xFFFF8A80)) }
    }
}

@Composable
private fun ColorRow(label: String, current: Int, colors: List<Int>, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(78.dp))
        ColorDots(current, colors, onPick)
    }
}

@Composable
private fun ColorDots(current: Int, colors: List<Int> = palette, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { c ->
            val none = (c ushr 24) == 0
            Box(
                Modifier.padding(3.dp).size(if (c == current) 26.dp else 20.dp).clip(CircleShape)
                    .background(if (none) Color.Transparent else Color(c))
                    .border(1.dp, Color.Gray, CircleShape)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center
            ) { if (none) Text("✕", fontSize = 10.sp, color = Color.Gray) }
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

/** Zeigt die Overlay-Videos als echte Player ueber dem Hauptvideo (Position, Groesse, Ueberblenden wie im Export). */
@Composable
private fun OverlayVideos(vm: EditorViewModel) {
    val pos = vm.posMs
    vm.project.videos.sortedBy { it.track }.filter { pos >= it.startMs && pos < it.startMs + it.durMs }.forEach { v ->
        key(v.id) {
            AndroidView(
                factory = { c ->
                    (LayoutInflater.from(c).inflate(R.layout.player_overlay, null) as PlayerView).apply { player = vm.engine.videoPlayer(v) }
                },
                modifier = Modifier
                    .layout { m, cons ->
                        val w = (v.scale * cons.maxWidth).roundToInt().coerceAtLeast(2)
                        val h = (w / v.aspect).roundToInt().coerceAtLeast(2)
                        val pl = m.measure(Constraints.fixed(w, h))
                        layout(cons.maxWidth, cons.maxHeight) {
                            pl.place((v.x * cons.maxWidth - w / 2f).roundToInt(), (v.y * cons.maxHeight - h / 2f).roundToInt())
                        }
                    }
                    .graphicsLayer { alpha = vm.videoAlpha(v.id) }
            )
        }
    }
}
