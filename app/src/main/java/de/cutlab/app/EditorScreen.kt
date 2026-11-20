package de.cutlab.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

private val palette = listOf(0xFFFFFFFF, 0xFFFFD400, 0xFF00E5FF, 0xFFFF4081, 0xFF69F0AE, 0xFFFF5252).map { it.toInt() }

@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    var editing by remember { mutableStateOf<Selection?>(null) }
    var showLang by remember { mutableStateOf(false) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.addVideos(it) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::addImage) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addAudio) }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014)).systemBarsPadding()) {
        TopBar(vm)
        PreviewArea(vm, Modifier.weight(1f))
        Transport(vm)
        Timeline(vm, Modifier.fillMaxWidth().height(215.dp))
        SelectionPanel(vm) { editing = vm.selection }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xFF181820))) {
            Tool(Icons.Default.Movie, "Video") { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            Tool(Icons.Default.ContentCut, "Teilen") { vm.split() }
            Tool(Icons.Default.Title, "Text") { vm.addText()?.let { editing = Selection(Kind.TEXT, it) } }
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
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Text bearbeiten") },
                text = { OutlinedTextField(text, { text = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.setText(sel, text); editing = null }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Abbrechen") } }
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

    vm.message?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun TopBar(vm: EditorViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("CutLab", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
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
            val k = vm.selection?.kind
            val canTransform = k == Kind.TEXT || k == Kind.IMAGE || k == Kind.SUB
            Box(Modifier.aspectRatio(p.aspect.ratio)) {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            player = vm.engine.player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(vm.selection) { detectTapGestures { vm.onPreviewTap() } }
                        .then(
                            if (canTransform) Modifier.pointerInput(vm.selection) {
                                detectTransformGestures { _, pan, zoom, _ -> vm.transform(pan.x / size.width, pan.y / size.height, zoom) }
                            } else Modifier
                        )
                ) {
                    drawIntoCanvas { vm.painter.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), vm.posMs, vm.project) }
                    val r = vm.selection?.let { vm.painter.bounds[it.id] }
                    if (r != null) drawRect(Color.White, Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2.dp.toPx()))
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
private fun SelectionPanel(vm: EditorViewModel, onEditText: () -> Unit) {
    val sel = vm.selection ?: return
    val p = vm.project
    Column(Modifier.fillMaxWidth().background(Color(0xFF20202A)).padding(horizontal = 12.dp, vertical = 4.dp)) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubKind.values().forEach { k ->
                        FilterChip(selected = p.subStyle.kind == k, onClick = { vm.setSubKind(k) }, label = { Text(k.label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                    }
                    TextButton(onClick = onEditText) { Text("Text") }
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
