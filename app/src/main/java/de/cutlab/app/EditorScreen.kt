package de.cutlab.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.PathEffect
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
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::addImage) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addAudio) }

    if (vm.showProjects) ProjectsScreen(vm)
    else Column(Modifier.fillMaxSize().background(Color(0xFF101014)).systemBarsPadding()) {
        TopBar(vm) { renaming = true }
        PreviewArea(vm, Modifier.weight(1f))
        Transport(vm)
        Timeline(vm, Modifier.fillMaxWidth().height(215.dp))
        SelectionPanel(vm, onEditText = { editing = vm.selection }, onPresets = { showPresets = true })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xFF181820))) {
            Tool(Icons.Default.Movie, "Video") { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            Tool(Icons.Default.ContentCut, "Teilen") { vm.split() }
            Tool(Icons.Default.Title, "Text") { vm.addText()?.let { editing = Selection(Kind.TEXT, it); addedText = it } }
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

    // Sprache waehlen
    if (showLang) {
        var chosen by remember { mutableStateOf(VoskModels.langs.first()) }
        AlertDialog(
            onDismissRequest = { showLang = false },
            title = { Text("Untertitel automatisch erzeugen") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Sprache im Video:", fontSize = 13.sp)
                    VoskModels.langs.forEach { l ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = l }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen == l, onClick = { chosen = l })
                            Text(l.label)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Beim ersten Mal wird das Sprachmodell (ca. 40-50 MB) geladen. Danach laeuft alles offline.", fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(onClick = { showLang = false; vm.generateSubtitles(chosen) }) { Text("Starten") } },
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
private fun PreviewArea(vm: EditorViewModel, modifier: Modifier) {
    val p = vm.project
    Box(modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        if (p.clips.isEmpty()) {
            Text("Tippe unten auf \"Video\", um zu starten", color = Color.Gray, textAlign = TextAlign.Center)
        } else {
            val touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier.aspectRatio(p.aspect.ratio).clipToBounds()
                    .pointerInput(Unit) { detectTapGestures { vm.onPreviewTap() } }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val k = vm.selection?.kind
                            val overlay = k == Kind.TEXT || k == Kind.IMAGE || k == Kind.SUB
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
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { vm.painter.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), vm.posMs, vm.project) }
                        val r = vm.selection?.let { vm.painter.bounds[it.id] }
                        if (r != null) drawRect(Color.White, Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2.dp.toPx()))
                        if (vm.dragging) {
                            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))
                            val faint = Color.White.copy(alpha = 0.3f)
                            val kind = vm.selection?.kind
                            if (kind != Kind.SUB) GUIDES_X.forEach { drawLine(faint, Offset(it * size.width, 0f), Offset(it * size.width, size.height), 2f, pathEffect = dash) }
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

@Composable
private fun SelectionPanel(vm: EditorViewModel, onEditText: () -> Unit, onPresets: () -> Unit) {
    val sel = vm.selection ?: return
    val p = vm.project
    Row(Modifier.fillMaxWidth().background(Color(0xFF20202A)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
    Column(Modifier.weight(1f)) {
        when (sel.kind) {
            Kind.TEXT -> p.texts.find { it.id == sel.id }?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onEditText) { Text("Text aendern") }
                    ColorDots(t.color) { vm.setTextColor(t.id, it) }
                }
                Slider(t.size, { vm.setTextSize(t.id, it) }, valueRange = 0.02f..0.2f)
            }
            Kind.IMAGE -> p.images.find { it.id == sel.id }?.let { im ->
                Text("Groesse (oder mit zwei Fingern in der Vorschau)", color = Color.Gray, fontSize = 11.sp)
                Slider(im.scale, { vm.setImageScale(im.id, it) }, valueRange = 0.05f..1.5f)
            }
            Kind.SUB -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    SubKind.values().forEach { k ->
                        FilterChip(selected = p.subStyle.kind == k, onClick = { vm.setSubKind(k) }, label = { Text(k.label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                    }
                    TextButton(onClick = onEditText) { Text("Text") }
                    TextButton(onClick = onPresets) { Text("Vorlagen") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDots(p.subStyle.color) { vm.setSubColor(it) }
                    Spacer(Modifier.width(10.dp))
                    Text("Hervorhebung", color = Color.Gray, fontSize = 11.sp)
                    ColorDots(p.subStyle.highlight) { vm.setSubHighlight(it) }
                }
                Text("Groesse/Position gelten fuer alle Untertitel (auch per Geste in der Vorschau)", color = Color.Gray, fontSize = 11.sp)
                Slider(p.subStyle.size, { vm.setSubSize(it) }, valueRange = 0.025f..0.15f)
            }
            Kind.CLIP, Kind.AUDIO -> Text(
                "Ränder ziehen = kuerzen  |  lange druecken und ziehen = verschieben",
                color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }
    IconButton(onClick = { vm.deleteSelected() }) { Icon(Icons.Default.Delete, "Löschen", tint = Color(0xFFFF8A80)) }
    }
}

@Composable
private fun ColorDots(current: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        palette.forEach { c ->
            Box(
                Modifier.padding(3.dp).size(if (c == current) 26.dp else 20.dp).clip(CircleShape)
                    .background(Color(c)).clickable { onPick(c) }
            )
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
