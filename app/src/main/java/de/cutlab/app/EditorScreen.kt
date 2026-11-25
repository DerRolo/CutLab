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
import android.app.Activity
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.input.KeyboardType
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

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.addVideos(it) }
    var overlayMode by remember { mutableStateOf(false) }       // true = auf neue, hoehere Spur legen
    var menu by remember { mutableStateOf(0) }                  // 1 = + Media, 2 = + Overlay
    val pickOverlayVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.addOverlayVideo(u, true) } }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.addImage(u, overlayMode) } }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.addAudio(u, overlayMode) } }

    if (vm.fullscreen) FullscreenPreview(vm)
    else if (vm.showSubList) SubtitleListScreen(vm)
    else if (vm.showProjects) ProjectsScreen(vm)
    else ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Text("CutLab", fontSize = 20.sp, modifier = Modifier.padding(16.dp))
                NavigationDrawerItem(
                    label = { Text("Projekte") }, icon = { Icon(Icons.Default.FolderOpen, null) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; vm.openList() }
                )
                NavigationDrawerItem(
                    label = { Text("Neues Projekt") }, icon = { Icon(Icons.Default.Add, null) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; vm.createProject() }
                )
                NavigationDrawerItem(
                    label = { Text("Projekt umbenennen") }, icon = { Icon(Icons.Default.Edit, null) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; renaming = true }
                )
            }
        }
    ) {
    Column(Modifier.fillMaxSize().background(Color(0xFF101014)).systemBarsPadding()) {
        TopBar(vm, onMenu = { scope.launch { drawer.open() } }, onRename = { renaming = true })
        PreviewArea(vm, Modifier.weight(1f)) { editing = it }
        Transport(vm)
        EditBar(vm)
        Box(
            Modifier.fillMaxWidth().height(20.dp).background(Color(0xFF181820))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { ch, dy -> ch.consume(); vm.timelineDp = (vm.timelineDp - dy / density).coerceIn(80f, 360f) }
                },
            contentAlignment = Alignment.Center
        ) { Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Gray)) }
        Timeline(vm, Modifier.fillMaxWidth().height(vm.timelineDp.dp), onEdit = { editing = it })
        SelectionPanel(vm, onEditText = { editing = vm.selection }, onPresets = { showPresets = true }, onList = { vm.showSubList = true })
        Row(Modifier.fillMaxWidth().background(Color(0xFF181820))) {
            // + Media: legt Neues auf die erste freie Spur
            Box(Modifier.weight(1f)) {
                Tool(Icons.Default.AddCircle, "+ Media") { menu = 1 }
                DropdownMenu(expanded = menu == 1, onDismissRequest = { menu = 0 }) {
                    MenuEntry(Icons.Default.Movie, "Video") { menu = 0; pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                    MenuEntry(Icons.Default.Image, "Bild") { menu = 0; overlayMode = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    MenuEntry(Icons.Default.Title, "Text") { menu = 0; vm.addText(false)?.let { editing = Selection(Kind.TEXT, it); addedText = it } }
                    MenuEntry(Icons.Default.MusicNote, "Musik") { menu = 0; overlayMode = false; pickAudio.launch(arrayOf("audio/*")) }
                }
            }
            // + Overlay: erzeugt sofort eine neue, hoehere Spur
            Box(Modifier.weight(1f)) {
                Tool(Icons.Default.Layers, "+ Overlay") { menu = 2 }
                DropdownMenu(expanded = menu == 2, onDismissRequest = { menu = 0 }) {
                    MenuEntry(Icons.Default.Movie, "Video") { menu = 0; pickOverlayVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                    MenuEntry(Icons.Default.Image, "Bild") { menu = 0; overlayMode = true; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    MenuEntry(Icons.Default.Title, "Text") { menu = 0; vm.addText(true)?.let { editing = Selection(Kind.TEXT, it); addedText = it } }
                    MenuEntry(Icons.Default.MusicNote, "Musik") { menu = 0; overlayMode = true; pickAudio.launch(arrayOf("audio/*")) }
                }
            }
            Box(Modifier.weight(1f)) { Tool(Icons.Default.Subtitles, "Untertitel") { showLang = true } }
            Box(Modifier.weight(1f)) {
                Tool(Icons.Default.FormatListBulleted, "Untertitel-Liste") {
                    if (vm.project.subs.isEmpty()) vm.message = "Noch keine Untertitel. Erzeuge sie zuerst über „Untertitel“." else vm.showSubList = true
                }
            }
        }
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
            title = { Text("Vorlage laden oder speichern") },
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
private fun TopBar(vm: EditorViewModel, onMenu: () -> Unit, onRename: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menü", tint = Color.White) }
        // Projektname: umrahmt mit Stift, damit man sieht, dass man ihn aendern kann
        Row(
            Modifier.weight(1f).border(1.dp, Color(0xFF555566), RoundedCornerShape(10.dp))
                .clickable(onClick = onRename).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(vm.project.name, color = Color.White, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Edit, "Projektname ändern", tint = Color.Gray, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { vm.export() }) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(4.dp)); Text("Export") }
    }
}

@Composable
private fun PreviewArea(vm: EditorViewModel, modifier: Modifier, onEdit: (Selection) -> Unit) {
    val p = vm.project
    val onEditNow by rememberUpdatedState(onEdit)
    val penPainter = rememberVectorPainter(Icons.Default.Edit)
    val trashPainter = rememberVectorPainter(Icons.Default.Delete)
    val closePainter = rememberVectorPainter(Icons.Default.Close)
    Box(modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        if (p.isEmpty) {
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
                                val clsC = Offset(r.right.coerceIn(rad, w - rad), r.bottom.coerceIn(rad, h - rad))
                                if (hypot(x - clsC.x, y - clsC.y) <= hit) { vm.select(null); return@detectTapGestures }
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
                    GapCover(vm)
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
                            val cl = Offset(r.right.coerceIn(rad, size.width - rad), r.bottom.coerceIn(rad, size.height - rad))
                            drawCircle(Color(0xFF616161), rad, cl)
                            translate(cl.x - ic / 2, cl.y - ic / 2) { with(closePainter) { draw(Size(ic, ic), colorFilter = ColorFilter.tint(Color.White)) } }
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
    var aspectMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.togglePlay() }) {
            Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White)
        }
        IconButton(onClick = { vm.loop = !vm.loop }) {
            Icon(Icons.Default.Repeat, "Endlos-Wiedergabe", tint = if (vm.loop) Color(0xFF69F0AE) else Color.Gray)
        }
        TimeLabel(vm)
        Spacer(Modifier.weight(1f))
        Box {
            AssistChip(onClick = { aspectMenu = true }, label = { Text(vm.project.aspect.label, fontSize = 11.sp) })
            DropdownMenu(expanded = aspectMenu, onDismissRequest = { aspectMenu = false }) {
                AspectPreset.values().forEach { a ->
                    DropdownMenuItem(text = { Text(a.label) }, onClick = { vm.setAspect(a); aspectMenu = false })
                }
            }
        }
        IconButton(onClick = { vm.zoom = (vm.zoom * 0.75f).coerceAtLeast(2f) }) { Icon(Icons.Default.ZoomOut, "Zoom -", tint = Color.White) }
        IconButton(onClick = { vm.zoom = (vm.zoom * 1.33f).coerceAtMost(400f) }) { Icon(Icons.Default.ZoomIn, "Zoom +", tint = Color.White) }
        IconButton(onClick = { vm.select(null); vm.fullscreen = true }) { Icon(Icons.Default.Fullscreen, "Vollbild", tint = Color.White) }
    }
}

@Composable
private fun TimeLabel(vm: EditorViewModel) {
    Text("${fmtTime(vm.posMs)} / ${fmtTime(vm.project.totalMs)}", color = Color.White, fontSize = 13.sp)
}

private val outlinePalette = listOf(0xFF000000, 0xFFFFFFFF, 0xFF1F2A44, 0x00000000).map { it.toInt() }

@Composable
private fun SelectionPanel(vm: EditorViewModel, onEditText: () -> Unit, onPresets: () -> Unit, onList: () -> Unit) {
    var subTab by remember { mutableIntStateOf(0) }
    val sel = vm.selection ?: run { vm.trackSel?.let { TrackPanel(vm, it.first, it.second) }; return }
    val p = vm.project
    Row(Modifier.fillMaxWidth().background(Color(0xFF20202A)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Column(Modifier.weight(1f)) {
            when (sel.kind) {
                Kind.TEXT -> p.texts.find { it.id == sel.id }?.let { t ->
                    TextButton(onClick = onEditText) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Text ändern") }
                    ColorRow("Schrift", t.color, palette) { vm.setTextColor(t.id, it) }
                    ColorRow("Kontur", t.outline, outlinePalette) { vm.setTextOutline(t.id, it) }
                    SizeRow("Schriftgröße", t.size, 0.02f..0.2f) { vm.setTextSize(t.id, it) }
                }
                Kind.VIDEO -> p.videos.find { it.id == sel.id }?.let { v ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = v.muted, onClick = { vm.setVideoMuted(v.id, !v.muted) }, label = { Text(if (v.muted) "Ton aus" else "Ton an", fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                        listOf(0L to "Ohne Fade", 300L to "Fade 0,3 s", 600L to "Fade 0,6 s", 1000L to "Fade 1 s").forEach { (ms, label) ->
                            FilterChip(selected = v.fadeMs == ms, onClick = { vm.setVideoFade(v.id, ms) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                    if (v.hasAudio) VolumeRow(v.gainDb) { vm.setVideoGain(v.id, it) } else Text("Dieses Video hat keinen Ton.", color = Color.Gray, fontSize = 11.sp)
                    Text("Größe (oder mit zwei Fingern in der Vorschau)", color = Color.Gray, fontSize = 11.sp)
                    Slider(v.scale, { vm.setVideoScale(v.id, it) }, valueRange = 0.1f..1.5f)
                }
                Kind.IMAGE -> p.images.find { it.id == sel.id }?.let { im ->
                    Text("Größe (oder mit zwei Fingern in der Vorschau)", color = Color.Gray, fontSize = 11.sp)
                    Slider(im.scale, { vm.setImageScale(im.id, it) }, valueRange = 0.05f..1.5f)
                }
                Kind.SUB -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        listOf("Stil", "Größe", "Extras").forEachIndexed { i, label ->
                            FilterChip(selected = subTab == i, onClick = { subTab = i }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                        }
                        FilterChip(
                            selected = false, onClick = onList,
                            label = { Text("Untertitelliste", fontSize = 12.sp) },
                            leadingIcon = null, modifier = Modifier.padding(end = 4.dp)
                        )
                        FilterChip(
                            selected = false, onClick = onPresets,
                            label = { Text("★ Vorlage laden/speichern", fontSize = 12.sp) },
                            leadingIcon = null, modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    when (subTab) {
                        0 -> Column {
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                SubKind.values().forEach { k ->
                                    FilterChip(selected = p.subStyle.kind == k, onClick = { vm.setSubKind(k) }, label = { Text(k.label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp))
                                }
                            }
                            ColorRow("Schrift", p.subStyle.color, palette) { vm.setSubColor(it) }
                            ColorRow("Aktives Wort", p.subStyle.highlight, palette) { vm.setSubHighlight(it) }
                            ColorRow("Kontur", p.subStyle.outline, outlinePalette) { vm.setSubOutline(it) }
                        }
                        1 -> SizeRow("Schriftgröße", p.subStyle.size, 0.025f..0.15f) { vm.setSubSize(it) }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = p.subStyle.hidePunct, onClick = { vm.setSubHidePunct(!p.subStyle.hidePunct) },
                                label = { Text("Ohne Satzzeichen", fontSize = 12.sp) }, modifier = Modifier.padding(end = 8.dp)
                            )
                            Text("Blendet Punkt, Komma usw. aus.\nDer Text selbst bleibt erhalten.", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
                Kind.CLIP -> p.clips.find { it.id == sel.id }?.let { c ->
                    if (c.hasAudio) VolumeRow(c.gainDb) { vm.setClipGain(c.id, it) } else Text("Dieser Clip hat keinen Ton.", color = Color.Gray, fontSize = 11.sp)
                }
                Kind.AUDIO -> p.audios.find { it.id == sel.id }?.let { a ->
                    Text(a.name, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                    VolumeRow(a.gainDb) { vm.setAudioGain(a.id, it) }
                }
            }
        }
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** Schnellzugriff ueber der Timeline (wie bei DaVinci): Schneiden und Loeschen. */
@Composable
private fun EditBar(vm: EditorViewModel) {
    val pad = PaddingValues(horizontal = 8.dp)
    val hasSel = vm.selection != null || vm.trackSel != null
    Row(Modifier.fillMaxWidth().background(Color(0xFF1C1C26)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.split() }, contentPadding = pad) { Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Teilen", fontSize = 12.sp) }
        TextButton(onClick = { vm.deleteSelected() }, contentPadding = pad) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Löschen", fontSize = 12.sp) }
        TextButton(onClick = { vm.undo() }, contentPadding = pad) { Icon(Icons.Default.Undo, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Rückgängig", fontSize = 12.sp) }
        TextButton(onClick = { vm.select(null) }, enabled = hasSel, contentPadding = pad) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Abwählen", fontSize = 12.sp) }
    }
}

/** Vollbild: nur das Video; Antippen startet und stoppt die Wiedergabe. */
@Composable
private fun FullscreenPreview(vm: EditorViewModel) {
    BackHandler { vm.fullscreen = false }
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val w = activity?.window
        val c = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PreviewArea(vm, Modifier.fillMaxSize()) { }
        if (!vm.playing) Icon(Icons.Default.PlayArrow, null, tint = Color(0xAAFFFFFF), modifier = Modifier.align(Alignment.Center).size(72.dp))
        IconButton(onClick = { vm.fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Default.FullscreenExit, "Vollbild beenden", tint = Color.White)
        }
    }
}

private fun trackName(k: Kind, lane: Int): String = when (k) {
    Kind.CLIP -> "Video 1"
    Kind.VIDEO -> "Video ${lane + 2}"
    Kind.SUB -> "Untertitel"
    Kind.TEXT -> "Text ${lane + 1}"
    Kind.IMAGE -> "Bild ${lane + 1}"
    Kind.AUDIO -> "Ton ${lane + 1}"
}

/** Ganze Spur ausgewaehlt: Lautstaerke fuer alle Clips dieser Spur. */
@Composable
private fun TrackPanel(vm: EditorViewModel, k: Kind, lane: Int) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF20202A)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Ganze Spur: ${trackName(k, lane)} (alle Clips)", color = Color(0xFFFFD400), fontSize = 12.sp)
            if (vm.trackHasAudio(k, lane)) {
                VolumeRow(vm.trackGain(k, lane)) { vm.setTrackGain(k, lane, it) }
            } else Text("Diese Spur hat keinen Ton.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
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

/** Schwarz, solange auf der Hauptspur kein Clip liegt (Luecken, Texte ohne Video). */
@Composable
private fun GapCover(vm: EditorViewModel) {
    val pos = vm.posMs
    val covered = vm.project.clips.any { pos >= it.startMs && pos < it.startMs + it.durMs }
    if (!covered) Box(Modifier.fillMaxSize().background(Color.Black))
}

private fun dbLabel(db: Float): String = if (db <= -60f) "Aus" else (if (db > 0f) "+" else "") + "%.1f dB".format(db)

/** Lautstaerke: Schieberegler (-60 bis +12 dB) und Wert in dB, der sich antippen und eintippen laesst. */
@Composable
private fun VolumeRow(db: Float, onChange: (Float) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    var lastTap by remember { mutableLongStateOf(0L) }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.VolumeUp, "Lautstärke", tint = Color.White, modifier = Modifier.size(20.dp))
        Slider(
            db, { onChange((it * 2f).roundToInt() / 2f) }, valueRange = -60f..12f,
            modifier = Modifier.weight(1f).height(56.dp).padding(horizontal = 6.dp).pointerInput(Unit) {
                // Zweimal kurz antippen = zurueck auf 0 dB (der Regler selbst wird dabei nicht blockiert)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val t0 = System.currentTimeMillis()
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (kotlin.math.abs(ch.position.x - down.position.x) > viewConfiguration.touchSlop * 4) moved = true
                        if (!ch.pressed) break
                    }
                    val now = System.currentTimeMillis()
                    if (!moved && now - t0 < 350L) {
                        if (now - lastTap < 500L) { change(0f); lastTap = 0L } else lastTap = now
                    }
                }
            }
        )
        Text(
            dbLabel(db), color = Color(0xFF69F0AE), fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.width(78.dp).clickable { dialog = true }.padding(vertical = 10.dp)     // feste Breite: aendert sich nicht mit den Ziffern
        )
    }
    if (dialog) {
        var txt by remember { mutableStateOf(if (db <= -60f) "-60" else String.format(java.util.Locale.US, "%.1f", db)) }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Lautstärke in dB") },
            text = {
                Column {
                    OutlinedTextField(txt, { txt = it }, singleLine = true, placeholder = { Text("z. B. -6 oder +3") })
                    Text("0 = unverändert, negativ = leiser, positiv = lauter. -60 oder weniger = aus. Den Regler zweimal antippen setzt auf 0 dB zurück.", color = Color.Gray, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    txt.replace(',', '.').trim().toFloatOrNull()?.let { onChange(it.coerceIn(-60f, 24f)) }
                    dialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Abbrechen") } }
        )
    }
}

/** Schriftgroesse: Schieberegler plus Zahl rechts, die sich antippen und fest eintippen laesst. */
@Composable
private fun SizeRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(78.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
        Text(
            "${(value * 1000).roundToInt()}", color = Color(0xFF69F0AE), fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp).clickable { dialog = true }.padding(vertical = 10.dp)
        )
    }
    if (dialog) {
        var txt by remember { mutableStateOf("${(value * 1000).roundToInt()}") }
        val lo = (range.start * 1000).roundToInt()
        val hi = (range.endInclusive * 1000).roundToInt()
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(label) },
            text = {
                Column {
                    OutlinedTextField(txt, { txt = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("Erlaubt: $lo bis $hi (50 ist mittelgroß)", color = Color.Gray, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    txt.trim().toIntOrNull()?.let { onChange((it.coerceIn(lo, hi)) / 1000f) }
                    dialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Abbrechen") } }
        )
    }
}
