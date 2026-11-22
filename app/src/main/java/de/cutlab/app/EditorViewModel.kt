package de.cutlab.app

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import java.util.Collections
import kotlin.math.roundToInt
import java.util.UUID

private const val MIN = 200L

/** Magnetische Hilfslinien (Anteil von Breite/Hoehe): Raender, Goldener Schnitt, Mitte. */
val GUIDES_X = floatArrayOf(0.1f, 0.5f, 0.9f)
val GUIDES_Y = floatArrayOf(0.1f, 0.382f, 0.5f, 0.618f, 0.9f)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val engine = PreviewEngine(app)
    val images = ImageCache(app)
    val painter = OverlayPainter({ images.get(it) })
    val thumbs = ThumbCache(app)

    var project by mutableStateOf(Project()); private set
    var posMs by mutableLongStateOf(0L); private set
    var playing by mutableStateOf(false); private set
    var selection by mutableStateOf<Selection?>(null); private set
    var zoom by mutableFloatStateOf(70f)            // dp pro Sekunde
    var busy by mutableStateOf<String?>(null); private set
    var busyProgress by mutableFloatStateOf(-1f); private set
    var message by mutableStateOf<String?>(null)
    var showProjects by mutableStateOf(false)
    var timelineDp by mutableFloatStateOf(150f)          // Hoehe der Timeline, per Griff verstellbar
    var presets by mutableStateOf<List<SubPreset>>(emptyList()); private set
    var defaultPreset by mutableStateOf<String?>(null); private set
    var snapX by mutableStateOf<Float?>(null); private set
    var snapY by mutableStateOf<Float?>(null); private set
    var dragging by mutableStateOf(false); private set
    var pz by mutableFloatStateOf(1f); private set       // Zoom der Vorschau (nur Ansicht, nicht Export)
    var pox by mutableFloatStateOf(0f); private set
    var poy by mutableFloatStateOf(0f); private set
    private var rawX = 0f
    private var rawY = 0f
    var projectList by mutableStateOf<List<ProjectInfo>>(emptyList()); private set
    var currentId: String = ""; private set

    private val undoStack = ArrayDeque<Project>()
    private var nextId = 1L
    private var lastLive = 0L
    var snapGuideMs by mutableStateOf<Long?>(null); private set     // gelbe Einrast-Linie in der Timeline
    var dragInfo by mutableStateOf<String?>(null); private set      // Zeitanzeige beim Ziehen/Kuerzen
    private var lastDragged: Selection? = null
    private var anchorMs = 0L                                          // Playhead vor dem Ziehen (zum Einrasten)
    private var dragRaw = Float.NaN
    private var laneAcc = 0f
    private var zoomBefore = 0f
    private var setupRunning = false
    private var job: Job? = null
    private var exporter: Exporter? = null

    private data class Meta(val uri: String, val dur: Long, val w: Int, val h: Int, val hasAudio: Boolean = true)

    init {
        PresetStore.load(app).let { presets = it.first; defaultPreset = it.second }
        val id = ProjectStore.lastId(app) ?: ProjectStore.newId().also { ProjectStore.save(app, it, newProjectData()) }
        currentId = id
        ProjectStore.setLast(app, id)
        applyLoaded(ProjectStore.load(app, id) ?: Project())
        firstRunSetup()
        viewModelScope.launch {
            snapshotFlow { project }.collectLatest { p ->
                delay(600)
                withContext(Dispatchers.IO) { try { ProjectStore.save(getApplication(), currentId, p) } catch (_: Exception) {} }
            }
        }
        viewModelScope.launch {
            var last = SystemClock.uptimeMillis()
            while (true) {
                delay(30)
                val now = SystemClock.uptimeMillis()
                val dt = now - last
                last = now
                if (playing) {
                    posMs += dt
                    val tot = project.totalMs
                    if (posMs >= tot) { posMs = tot; playing = false }
                }
                engine.sync(posMs, playing)
                engine.syncAudio(project.audios, playing, posMs)
                engine.syncVideos(project.videos, playing, posMs)
            }
        }
    }

    override fun onCleared() { saveNow(); engine.release(); thumbs.release() }

    private fun nid() = nextId++
    private fun snap() { undoStack.addLast(project); if (undoStack.size > 50) undoStack.removeFirst() }
    private fun set(p: Project, syncClips: Boolean = false) {
        project = p
        if (syncClips) engine.setClips(p.clips, posMs)
    }
    private fun live(f: (Project) -> Project) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLive > 700) snap()
        lastLive = now
        project = f(project)
    }

    // ---------- Wiedergabe ----------
    fun select(s: Selection?) { selection = s }
    fun seekTo(ms: Long) { posMs = ms.coerceIn(0L, project.totalMs) }
    fun togglePlay() {
        val total = project.totalMs
        if (total <= 0L) return
        if (playing) playing = false else { if (posMs >= total) posMs = 0L; playing = true }
    }
    fun onPreviewTap() {
        val k = selection?.kind
        if (k == Kind.TEXT || k == Kind.IMAGE || k == Kind.SUB || k == Kind.VIDEO) selection = null else togglePlay()
    }

    fun undo() {
        val p = undoStack.removeLastOrNull() ?: return
        project = p
        engine.setClips(p.clips, posMs)
        posMs = posMs.coerceAtMost(p.totalMs)
        selection = null
    }

    // ---------- Importieren ----------
    private fun probe(u: Uri): Meta? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(getApplication<Application>(), u)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            Meta(u.toString(), d, w, h, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        } catch (e: Exception) { null } finally { r.release() }
    }

    private fun nameOf(u: Uri): String = try {
        getApplication<Application>().contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null } ?: "Musik"

    /** Kopiert eine ausgewaehlte Datei in den App-Speicher, damit sie nach einem Neustart noch da ist. */
    private fun importFile(u: Uri, ext: String): String? = try {
        val f = File(ProjectStore.mediaDir(getApplication(), currentId), "${UUID.randomUUID()}.$ext")
        getApplication<Application>().contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        Uri.fromFile(f).toString()
    } catch (e: Exception) { null }

    // ---------- Projekte ----------
    private fun applyLoaded(loaded: Project) {
        fun ok(u: String) = Uri.parse(u).path?.let { File(it).exists() } ?: false
        val p = loaded.copy(
            clips = loaded.clips.filter { ok(it.uri) },
            images = loaded.images.filter { ok(it.uri) },
            audios = loaded.audios.filter { ok(it.uri) },
            videos = loaded.videos.filter { ok(it.uri) }
        )
        undoStack.clear(); selection = null; posMs = 0; resetPreviewZoom()
        project = p
        nextId = (p.clips.map { it.id } + p.texts.map { it.id } + p.images.map { it.id } +
            p.audios.map { it.id } + p.videos.map { it.id } + p.subs.map { it.id }).maxOrNull()?.plus(1) ?: 1L
        engine.setClips(p.clips, 0)
    }

    /** Neues Projekt startet mit der Standard-Untertitelvorlage (falls gesetzt). */
    private fun newProjectData() = Project(subStyle = presets.find { it.name == defaultPreset }?.style ?: SubStyle())

    // ---------- Untertitel-Vorlagen ----------
    private fun persistPresets() { try { PresetStore.save(getApplication(), presets, defaultPreset) } catch (_: Exception) {} }

    fun savePreset(name: String) {
        presets = presets.filter { it.name != name } + SubPreset(name, project.subStyle)
        persistPresets()
    }

    fun applyPreset(p: SubPreset) { snap(); project = project.copy(subStyle = p.style) }

    fun deletePreset(name: String) {
        presets = presets.filter { it.name != name }
        if (defaultPreset == name) defaultPreset = null
        persistPresets()
    }

    fun toggleDefaultPreset(name: String) {
        defaultPreset = if (defaultPreset == name) null else name
        persistPresets()
    }

    private fun saveNow() { try { ProjectStore.save(getApplication(), currentId, project) } catch (_: Exception) {} }

    private fun switchTo(id: String) {
        playing = false
        currentId = id
        ProjectStore.setLast(getApplication(), id)
        applyLoaded(ProjectStore.load(getApplication(), id) ?: Project())
        showProjects = false
    }

    private fun refreshList() {
        viewModelScope.launch { projectList = withContext(Dispatchers.IO) { ProjectStore.list(getApplication()) } }
    }

    fun openList() { saveNow(); showProjects = true; refreshList() }

    fun openProject(id: String) {
        if (id == currentId) { showProjects = false; return }
        saveNow(); switchTo(id)
    }

    fun createProject() {
        saveNow()
        val id = ProjectStore.newId()
        ProjectStore.save(getApplication(), id, newProjectData())
        switchTo(id)
    }

    fun deleteProject(id: String) {
        ProjectStore.delete(getApplication(), id)
        if (id == currentId) {
            val other = ProjectStore.list(getApplication()).firstOrNull()?.id
            if (other != null) switchTo(other).also { showProjects = true }
            else { val nid = ProjectStore.newId(); ProjectStore.save(getApplication(), nid, newProjectData()); switchTo(nid); showProjects = true }
        }
        refreshList()
    }

    fun renameProject(id: String, name: String) {
        val n = name.trim().ifEmpty { "Projekt" }
        if (id == currentId) { project = project.copy(name = n); saveNow() }
        else ProjectStore.load(getApplication(), id)?.let { ProjectStore.save(getApplication(), id, it.copy(name = n)) }
        refreshList()
    }

    fun addVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Video wird importiert ..."; busyProgress = -1f
            val metas = try {
                withContext(Dispatchers.IO) { uris.mapNotNull { u -> importFile(u, "mp4")?.let { probe(Uri.parse(it)) } } }
            } finally { busy = null }
            if (metas.isEmpty()) { message = "Video konnte nicht gelesen werden."; return@launch }
            snap()
            var p = project
            if (p.clips.isEmpty()) {
                val m = metas.first()
                p = p.copy(aspect = if (m.h > m.w) AspectPreset.PORTRAIT else if (m.w > m.h) AspectPreset.LANDSCAPE else AspectPreset.SQUARE)
            }
            var cursor = p.clips.maxOfOrNull { it.startMs + it.durMs } ?: 0L
            for (m in metas) {
                p = p.copy(clips = p.clips + Clip(nid(), m.uri, m.dur, 0, m.dur, cursor, m.w.coerceAtLeast(1).toFloat() / m.h.coerceAtLeast(1), m.hasAudio))
                cursor += m.dur
            }
            set(p, true)
        }
    }

    private fun slot(): Pair<Long, Long> = posMs to 3000L

    fun addText(): Long? {
        snap()
        val (s, d) = slot()
        val id = nid()
        set(project.copy(texts = project.texts + TextItem(id, "Text", s, d, lane = freeLane(project.texts.map { Triple(it.lane, it.startMs, it.startMs + it.durMs) }, s, s + d))))
        selection = Selection(Kind.TEXT, id)
        return id
    }

    fun addImage(uri: Uri) {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { importFile(uri, "jpg")?.also { images.get(it) } }
            if (local == null) { message = "Bild konnte nicht gelesen werden."; return@launch }
            snap()
            val (s, d) = slot()
            val id = nid()
            set(project.copy(images = project.images + ImageItem(id, local, s, d, lane = freeLane(project.images.map { Triple(it.lane, it.startMs, it.startMs + it.durMs) }, s, s + d))))
            selection = Selection(Kind.IMAGE, id)
        }
    }

    fun addAudio(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { nameOf(uri) }
            val meta = withContext(Dispatchers.IO) {
                importFile(uri, name.substringAfterLast('.', "mp3").take(5))?.let { probe(Uri.parse(it)) }
            }
            if (meta == null) { message = "Audio konnte nicht gelesen werden."; return@launch }
            val total = project.totalMs
            val start = posMs
            val dur = if (total > start + MIN) minOf(meta.dur, total - start) else meta.dur
            snap()
            val id = nid()
            set(project.copy(audios = project.audios + AudioItem(id, meta.uri, name, meta.dur, start, 0, dur, lane = freeLane(project.audios.map { Triple(it.lane, it.startMs, it.startMs + it.durMs) }, start, start + dur))))
            selection = Selection(Kind.AUDIO, id)
        }
    }

    /** Zusaetzliches Video auf einer Overlay-Spur (Bild-in-Bild / Ueberblenden). */
    fun addOverlayVideo(uri: Uri) {
        viewModelScope.launch {
            busy = "Video wird importiert ..."; busyProgress = -1f
            val meta = try {
                withContext(Dispatchers.IO) { importFile(uri, "mp4")?.let { probe(Uri.parse(it)) } }
            } finally { busy = null }
            if (meta == null || meta.w <= 0 || meta.h <= 0) { message = "Video konnte nicht gelesen werden."; return@launch }
            val start = posMs
            val dur = meta.dur
            val lane = freeLane(project.videos.map { Triple(it.track, it.startMs, it.startMs + it.durMs) }, start, start + dur)
            snap()
            val id = nid()
            set(project.copy(videos = project.videos + VideoItem(
                id, meta.uri, meta.dur, meta.w.toFloat() / meta.h, start, 0, dur,
                track = lane, hasAudio = meta.hasAudio
            )))
            selection = Selection(Kind.VIDEO, id)
        }
    }

    fun setAspect(a: AspectPreset) { snap(); project = project.copy(aspect = a) }

    // ---------- Schneiden / Loeschen / Text ----------
    fun split() {
        val cs = project.clips
        val c = cs.firstOrNull { posMs > it.startMs + 250 && posMs < it.startMs + it.durMs - 250 }
        if (c == null) { message = "Playhead an die Stelle setzen, an der geschnitten werden soll."; return }
        snap()
        val cut = c.inMs + (posMs - c.startMs)
        val l = c.copy(outMs = cut)
        val r = c.copy(id = nid(), inMs = cut, startMs = posMs)
        set(project.copy(clips = cs.map { if (it.id == c.id) l else it } + r), true)
        selection = Selection(Kind.CLIP, r.id)
    }

    fun deleteSelected() {
        val s = selection ?: return
        snap()
        val p = project
        val np = when (s.kind) {
            Kind.CLIP -> p.copy(clips = p.clips.filter { it.id != s.id })
            Kind.TEXT -> p.copy(texts = p.texts.filter { it.id != s.id })
            Kind.IMAGE -> p.copy(images = p.images.filter { it.id != s.id })
            Kind.AUDIO -> p.copy(audios = p.audios.filter { it.id != s.id })
            Kind.VIDEO -> p.copy(videos = p.videos.filter { it.id != s.id })
            Kind.SUB -> p.copy(subs = p.subs.filter { it.id != s.id })
        }
        selection = null
        set(np, s.kind == Kind.CLIP)
        posMs = posMs.coerceAtMost(np.totalMs)
    }

    fun setText(sel: Selection, text: String) {
        snap()
        val p = project
        project = when (sel.kind) {
            Kind.TEXT -> p.copy(texts = p.texts.map { if (it.id == sel.id) it.copy(text = text) else it })
            Kind.SUB -> p.copy(subs = p.subs.map { if (it.id == sel.id) it.copy(text = text, words = evenWords(text, it.startMs, it.endMs)) else it })
            else -> p
        }
    }

    // ---------- Live-Aenderungen (Gesten, Slider) ----------
    fun beginTransform() {
        val s = selection ?: return
        val p = project
        when (s.kind) {
            Kind.TEXT -> p.texts.find { it.id == s.id }?.let { rawX = it.x; rawY = it.y }
            Kind.IMAGE -> p.images.find { it.id == s.id }?.let { rawX = it.x; rawY = it.y }
            Kind.VIDEO -> p.videos.find { it.id == s.id }?.let { rawX = it.x; rawY = it.y }
            Kind.SUB -> { rawX = p.subStyle.x; rawY = p.subStyle.y }
            else -> {}
        }
        dragging = true
    }

    fun endTransform() { dragging = false; snapX = null; snapY = null }

    private fun snapTo(v: Float, g: FloatArray): Pair<Float, Float?> {
        val n = g.minByOrNull { abs(it - v) } ?: return v to null
        return if (abs(n - v) < 0.02f) n to n else v to null
    }

    /** Verschieben/Skalieren eines Overlays; rastet sanft an den Hilfslinien ein, laesst sich aber weiterziehen. */
    fun transform(dx: Float, dy: Float, z: Float) {
        val s = selection ?: return
        rawX = (rawX + dx).coerceIn(0f, 1f)
        rawY = (rawY + dy).coerceIn(0f, 1f)
        val (sx, gx) = snapTo(rawX, GUIDES_X)
        val (sy, gy) = snapTo(rawY, GUIDES_Y)
        snapX = gx
        snapY = gy
        live { p ->
            when (s.kind) {
                Kind.TEXT -> p.copy(texts = p.texts.map {
                    if (it.id == s.id) it.copy(x = sx, y = sy, size = (it.size * z).coerceIn(0.02f, 0.25f)) else it
                })
                Kind.IMAGE -> p.copy(images = p.images.map {
                    if (it.id == s.id) it.copy(x = sx, y = sy, scale = (it.scale * z).coerceIn(0.05f, 1.5f)) else it
                })
                Kind.VIDEO -> p.copy(videos = p.videos.map {
                    if (it.id == s.id) it.copy(x = sx, y = sy, scale = (it.scale * z).coerceIn(0.1f, 1.5f)) else it
                })
                Kind.SUB -> p.copy(subStyle = p.subStyle.copy(x = sx, y = sy, size = (p.subStyle.size * z).coerceIn(0.025f, 0.15f)))
                else -> p
            }
        }
    }

    fun zoomPreview(z: Float, panX: Float, panY: Float, w: Float, h: Float) {
        pz = (pz * z).coerceIn(1f, 5f)
        val mx = (pz - 1f) * w / 2f
        val my = (pz - 1f) * h / 2f
        pox = (pox + panX).coerceIn(-mx, mx)
        poy = (poy + panY).coerceIn(-my, my)
    }

    fun resetPreviewZoom() { pz = 1f; pox = 0f; poy = 0f }

    fun setTextSize(id: Long, v: Float) = live { p -> p.copy(texts = p.texts.map { if (it.id == id) it.copy(size = v) else it }) }
    fun setTextColor(id: Long, c: Int) = live { p -> p.copy(texts = p.texts.map { if (it.id == id) it.copy(color = c) else it }) }
    fun setVideoScale(id: Long, v: Float) = live { p -> p.copy(videos = p.videos.map { if (it.id == id) it.copy(scale = v) else it }) }
    fun setVideoMuted(id: Long, m: Boolean) { snap(); project = project.copy(videos = project.videos.map { if (it.id == id) it.copy(muted = m) else it }) }
    fun setVideoFade(id: Long, ms: Long) { snap(); project = project.copy(videos = project.videos.map { if (it.id == id) it.copy(fadeMs = ms) else it }) }
    fun setVideoTrack(id: Long, t: Int) { snap(); project = project.copy(videos = project.videos.map { if (it.id == id) it.copy(track = t) else it }) }
    /** Deckkraft eines Overlay-Videos zum aktuellen Zeitpunkt (Ein-/Ausblenden). */
    fun videoAlpha(id: Long): Float {
        val v = project.videos.find { it.id == id } ?: return 1f
        return painter.videoAlpha(v, posMs)
    }
    fun setImageScale(id: Long, v: Float) = live { p -> p.copy(images = p.images.map { if (it.id == id) it.copy(scale = v) else it }) }
    fun setSubKind(k: SubKind) { snap(); project = project.copy(subStyle = project.subStyle.copy(kind = k)) }
    fun setSubSize(v: Float) = live { p -> p.copy(subStyle = p.subStyle.copy(size = v)) }
    fun setTextOutline(id: Long, c: Int) = live { p -> p.copy(texts = p.texts.map { if (it.id == id) it.copy(outline = c) else it }) }
    fun setSubOutline(c: Int) = live { p -> p.copy(subStyle = p.subStyle.copy(outline = c)) }
    fun setSubColor(c: Int) = live { p -> p.copy(subStyle = p.subStyle.copy(color = c)) }
    fun setSubHighlight(c: Int) = live { p -> p.copy(subStyle = p.subStyle.copy(highlight = c)) }

    // ---------- Timeline-Drags ----------
    fun beginDrag() { snap(); dragRaw = Float.NaN; laneAcc = 0f; snapGuideMs = null; lastDragged = null; dragInfo = ""; anchorMs = posMs }

    /** Feinmodus: beim Verschieben/Kuerzen zoomt die Timeline heran und springt an die Kante (Anzeige im Vorschaubild). */
    fun beginFine(edgeMs: Long) {
        if (zoomBefore == 0f) zoomBefore = zoom
        zoom = maxOf(zoom, 280f)
        seekTo(edgeMs)
    }

    private fun endFine() { if (zoomBefore != 0f) { zoom = zoomBefore; zoomBefore = 0f } }

    // ---------- Spuren (Lanes) ----------
    // Video: 0 = Video 1 (Hauptspur), 1.. = Overlay-Spuren (Video 2, 3, ...). Text/Bild/Ton: 0.. = Text 1, Text 2, ...
    private fun laneOf(k: Kind, id: Long): Int? = when (k) {
        Kind.CLIP -> if (project.clips.any { it.id == id }) 0 else null
        Kind.VIDEO -> project.videos.find { it.id == id }?.track?.plus(1)
        Kind.TEXT -> project.texts.find { it.id == id }?.lane
        Kind.IMAGE -> project.images.find { it.id == id }?.lane
        Kind.AUDIO -> project.audios.find { it.id == id }?.lane
        Kind.SUB -> null
    }

    /** Hoechste Spur, auf der andere Elemente liegen (so entsteht pro Zug hoechstens EINE neue Spur). */
    private fun maxLaneOthers(k: Kind, id: Long): Int {
        val p = project
        val lanes: List<Int> = when (k) {
            Kind.CLIP, Kind.VIDEO -> p.videos.filter { it.id != id }.map { it.track + 1 }
            Kind.TEXT -> p.texts.filter { it.id != id }.map { it.lane }
            Kind.IMAGE -> p.images.filter { it.id != id }.map { it.lane }
            Kind.AUDIO -> p.audios.filter { it.id != id }.map { it.lane }
            Kind.SUB -> emptyList()
        }
        val base = if (k == Kind.CLIP || k == Kind.VIDEO) 0 else -1      // Video 1 gibt es immer
        return maxOf(base, lanes.maxOrNull() ?: base)
    }

    private fun setLane(k: Kind, id: Long, l: Int) {
        val p = project
        when (k) {
            Kind.CLIP -> if (l >= 1) {
                // Hauptspur-Clip nach oben gezogen -> wird zu einem Overlay-Video (passend eingepasst)
                val c = p.clips.find { it.id == id } ?: return
                val fit = if (c.aspect >= p.aspect.ratio) 1f else c.aspect / p.aspect.ratio
                project = p.copy(
                    clips = p.clips.filter { it.id != id },
                    videos = p.videos + VideoItem(id, c.uri, c.srcDurMs, c.aspect, c.startMs, c.inMs, c.durMs, scale = fit, track = l - 1, hasAudio = c.hasAudio)
                )
                selection = Selection(Kind.VIDEO, id)
                engine.setClips(project.clips, posMs)
            }
            Kind.VIDEO -> if (l == 0) {
                // Overlay-Video nach unten auf Video 1 gezogen -> wird wieder ein Hauptspur-Clip
                val v = p.videos.find { it.id == id } ?: return
                project = p.copy(
                    videos = p.videos.filter { it.id != id },
                    clips = p.clips + Clip(id, v.uri, v.srcDurMs, v.inMs, v.inMs + v.durMs, v.startMs, v.aspect, v.hasAudio)
                )
                selection = Selection(Kind.CLIP, id)
                engine.setClips(project.clips, posMs)
            } else {
                project = p.copy(videos = p.videos.map { if (it.id == id) it.copy(track = l - 1) else it })
            }
            Kind.TEXT -> project = p.copy(texts = p.texts.map { if (it.id == id) it.copy(lane = l) else it })
            Kind.IMAGE -> project = p.copy(images = p.images.map { if (it.id == id) it.copy(lane = l) else it })
            Kind.AUDIO -> project = p.copy(audios = p.audios.map { if (it.id == id) it.copy(lane = l) else it })
            Kind.SUB -> {}
        }
    }

    /** Senkrechtes Ziehen wechselt die Spur (Video/Text/Bild: nach oben = hoehere Spur, Ton: nach unten). */
    fun dragLane(k: Kind, id: Long, dyPx: Float, stridePx: Float) {
        if (k == Kind.SUB || stridePx <= 0f) return
        laneAcc += dyPx
        val steps = (laneAcc / stridePx).roundToInt()
        if (steps == 0) return
        laneAcc -= steps * stridePx
        val cur = laneOf(k, id) ?: return
        lastDragged = Selection(k, id)
        val delta = if (k == Kind.AUDIO) steps else -steps
        val nl = (cur + delta).coerceIn(0, minOf(maxLaneOthers(k, id) + 1, 6))
        if (nl != cur) setLane(k, id, nl)
    }

    /** Niedrigste Spur, auf der im Zeitraum noch nichts liegt. */
    private fun freeLane(occupied: List<Triple<Int, Long, Long>>, start: Long, end: Long): Int {
        for (l in 0 until 5) if (occupied.none { it.first == l && it.second < end && it.third > start }) return l
        return 5
    }

    /** Leere Spuren zwischendurch entfernen (Overlay-Spuren zaehlen ab 0, Video 1 bleibt immer). */
    private fun compactLanes() {
        val p = project
        fun map(lanes: List<Int>): Map<Int, Int> = lanes.distinct().sorted().withIndex().associate { it.value to it.index }
        val tm = map(p.texts.map { it.lane })
        val im = map(p.images.map { it.lane })
        val am = map(p.audios.map { it.lane })
        val vmap = map(p.videos.map { it.track })
        project = p.copy(
            texts = p.texts.map { it.copy(lane = tm[it.lane] ?: 0) },
            images = p.images.map { it.copy(lane = im[it.lane] ?: 0) },
            audios = p.audios.map { it.copy(lane = am[it.lane] ?: 0) },
            videos = p.videos.map { it.copy(track = vmap[it.track] ?: 0) }
        )
    }

    // ---------- Ueberschreiben (Videos auf derselben Spur) ----------
    private fun pieces(s: Long, e: Long, a: Long, b: Long): List<Pair<Long, Long>> {
        if (b <= s || a >= e) return listOf(s to e)
        val out = ArrayList<Pair<Long, Long>>()
        if (a > s) out.add(s to a)
        if (b < e) out.add(b to e)
        return out
    }

    /** Wird ein Video ueber ein anderes gelegt, wird das darunter an dieser Stelle gekappt (oder geteilt/entfernt). */
    private fun overwrite(k: Kind, id: Long) {
        val p = project
        if (k == Kind.CLIP) {
            val me = p.clips.find { it.id == id } ?: return
            val a = me.startMs
            val b = me.startMs + me.durMs
            val out = ArrayList<Clip>()
            for (c in p.clips) {
                if (c.id == id) { out.add(c); continue }
                val s0 = c.startMs
                var first = true
                for ((ps, pe) in pieces(s0, s0 + c.durMs, a, b)) {
                    if (pe - ps < 100) continue
                    val nc = c.copy(startMs = ps, inMs = c.inMs + (ps - s0), outMs = c.inMs + (pe - s0))
                    out.add(if (first) nc else nc.copy(id = nid()))
                    first = false
                }
            }
            project = p.copy(clips = out)
        } else if (k == Kind.VIDEO) {
            val me = p.videos.find { it.id == id } ?: return
            val a = me.startMs
            val b = me.startMs + me.durMs
            val out = ArrayList<VideoItem>()
            for (v in p.videos) {
                if (v.id == id || v.track != me.track) { out.add(v); continue }
                val s0 = v.startMs
                var first = true
                for ((ps, pe) in pieces(s0, s0 + v.durMs, a, b)) {
                    if (pe - ps < 100) continue
                    val nv = v.copy(startMs = ps, inMs = v.inMs + (ps - s0), durMs = pe - ps)
                    out.add(if (first) nv else nv.copy(id = nid()))
                    first = false
                }
            }
            project = p.copy(videos = out)
        }
    }

    fun endDrag() {
        endFine()
        snapGuideMs = null; dragRaw = Float.NaN; dragInfo = null
        lastDragged?.let { ld ->
            val kindNow = when {
                project.clips.any { it.id == ld.id } -> Kind.CLIP
                project.videos.any { it.id == ld.id } -> Kind.VIDEO
                else -> ld.kind
            }
            overwrite(kindNow, ld.id)
        }
        lastDragged = null
        compactLanes()
        engine.setClips(project.clips, posMs)
        posMs = posMs.coerceAtMost(project.totalMs)
    }

    // ---------- Einrasten (Docking) ----------
    private fun startOf(k: Kind, id: Long): Long? = when (k) {
        Kind.CLIP -> project.clips.find { it.id == id }?.startMs
        Kind.TEXT -> project.texts.find { it.id == id }?.startMs
        Kind.IMAGE -> project.images.find { it.id == id }?.startMs
        Kind.AUDIO -> project.audios.find { it.id == id }?.startMs
        Kind.VIDEO -> project.videos.find { it.id == id }?.startMs
        Kind.SUB -> project.subs.find { it.id == id }?.startMs
    }

    private fun durOf(k: Kind, id: Long): Long? = when (k) {
        Kind.CLIP -> project.clips.find { it.id == id }?.durMs
        Kind.TEXT -> project.texts.find { it.id == id }?.durMs
        Kind.IMAGE -> project.images.find { it.id == id }?.durMs
        Kind.AUDIO -> project.audios.find { it.id == id }?.durMs
        Kind.VIDEO -> project.videos.find { it.id == id }?.durMs
        Kind.SUB -> project.subs.find { it.id == id }?.let { it.endMs - it.startMs }
    }

    /** Punkte, an denen Elemente einrasten: Anfang, Playhead, Clip-Grenzen und Kanten der Nachbarn auf derselben Spur. */
    private fun snapPoints(k: Kind, id: Long): List<Long> {
        val p = project
        val lane = laneOf(k, id) ?: 0
        val pts = ArrayList<Long>()
        pts.add(0L); pts.add(anchorMs)
        p.clips.filter { it.id != id }.forEach { pts.add(it.startMs); pts.add(it.startMs + it.durMs) }
        when (k) {
            Kind.TEXT -> p.texts.filter { it.id != id && it.lane == lane }.forEach { pts.add(it.startMs); pts.add(it.startMs + it.durMs) }
            Kind.IMAGE -> p.images.filter { it.id != id && it.lane == lane }.forEach { pts.add(it.startMs); pts.add(it.startMs + it.durMs) }
            Kind.AUDIO -> p.audios.filter { it.id != id && it.lane == lane }.forEach { pts.add(it.startMs); pts.add(it.startMs + it.durMs) }
            Kind.VIDEO -> p.videos.filter { it.id != id && it.track + 1 == lane }.forEach { pts.add(it.startMs); pts.add(it.startMs + it.durMs) }
            Kind.SUB -> p.subs.filter { it.id != id }.forEach { pts.add(it.startMs); pts.add(it.endMs) }
            Kind.CLIP -> {}
        }
        return pts
    }

    private fun snapThreshold(): Long = (14f * 1000f / zoom).toLong()      // ca. 14 dp auf dem Bildschirm

    private fun snapSpan(start: Long, dur: Long, pts: List<Long>): Long {
        var best = start; var bd = Long.MAX_VALUE; var guide: Long? = null
        for (pt in pts) {
            val d1 = abs(start - pt); if (d1 < bd) { bd = d1; best = pt; guide = pt }
            val d2 = abs(start + dur - pt); if (d2 < bd) { bd = d2; best = pt - dur; guide = pt }
        }
        return if (bd <= snapThreshold()) { snapGuideMs = guide; best } else { snapGuideMs = null; start }
    }

    private fun snapEdge(edge: Long, pts: List<Long>): Long {
        var best = edge; var bd = Long.MAX_VALUE
        for (pt in pts) { val d = abs(edge - pt); if (d < bd) { bd = d; best = pt } }
        return if (bd <= snapThreshold()) { snapGuideMs = best; best } else { snapGuideMs = null; edge }
    }

    /** Verschieben: rastet schon waehrend des Ziehens ein (gelbe Linie) und zeigt Start/Ende an. */
    fun moveItem(k: Kind, id: Long, d: Float) {
        val cur = startOf(k, id) ?: return
        val dur = durOf(k, id) ?: return
        lastDragged = Selection(k, id)
        if (dragRaw.isNaN()) dragRaw = cur.toFloat()
        dragRaw += d
        if (dragRaw < 0f) dragRaw = 0f
        val ns = snapSpan(dragRaw.toLong(), dur, snapPoints(k, id)).coerceAtLeast(0L)
        moveByDelta(k, id, (ns - cur).toFloat())
        seekTo(ns)                                       // der weisse Strich folgt dem Anfang und zeigt dessen Zeit
    }

    fun trimStart(k: Kind, id: Long, d: Float) {
        val cur = startOf(k, id) ?: return
        lastDragged = Selection(k, id)
        if (dragRaw.isNaN()) dragRaw = cur.toFloat()
        dragRaw += d
        if (dragRaw < 0f) dragRaw = 0f
        val edge = snapEdge(dragRaw.toLong(), snapPoints(k, id))
        trimStartRaw(k, id, (edge - cur).toFloat())
        val now = startOf(k, id) ?: return
        if (now != edge) dragRaw = now.toFloat()           // an Grenze gestoppt: Rohwert folgt der Realitaet
        seekTo(now)
    }

    fun trimEnd(k: Kind, id: Long, d: Float) {
        val cur = startOf(k, id) ?: return
        val dur = durOf(k, id) ?: return
        lastDragged = Selection(k, id)
        if (dragRaw.isNaN()) dragRaw = (cur + dur).toFloat()
        dragRaw += d
        val edge = snapEdge(dragRaw.toLong().coerceAtLeast(0L), snapPoints(k, id))
        trimEndRaw(k, id, (edge - (cur + dur)).toFloat())
        val end = (startOf(k, id) ?: return) + (durOf(k, id) ?: return)
        if (end != edge) dragRaw = end.toFloat()
        seekTo(end)
    }

    private fun moveByDelta(k: Kind, id: Long, d: Float) {
        val p = project
        fun cs(s: Long, @Suppress("UNUSED_PARAMETER") dur: Long) = (s + d.toLong()).coerceAtLeast(0L)
        project = when (k) {
            Kind.CLIP -> p.copy(clips = p.clips.map { if (it.id == id) it.copy(startMs = cs(it.startMs, it.durMs)) else it })
            Kind.TEXT -> p.copy(texts = p.texts.map { if (it.id == id) it.copy(startMs = cs(it.startMs, it.durMs)) else it })
            Kind.IMAGE -> p.copy(images = p.images.map { if (it.id == id) it.copy(startMs = cs(it.startMs, it.durMs)) else it })
            Kind.AUDIO -> p.copy(audios = p.audios.map { if (it.id == id) it.copy(startMs = cs(it.startMs, it.durMs)) else it })
            Kind.VIDEO -> p.copy(videos = p.videos.map { if (it.id == id) it.copy(startMs = cs(it.startMs, it.durMs)) else it })
            Kind.SUB -> p.copy(subs = p.subs.map {
                if (it.id == id) {
                    val dur = it.endMs - it.startMs
                    val ns = cs(it.startMs, dur)
                    val dd = ns - it.startMs
                    it.copy(startMs = ns, endMs = ns + dur, words = it.words.map { w -> w.copy(startMs = w.startMs + dd, endMs = w.endMs + dd) })
                } else it
            })
        }
    }

    private fun trimStartRaw(k: Kind, id: Long, d: Float) {
        val p = project
        val dl = d.toLong()
        project = when (k) {
            Kind.CLIP -> p.copy(clips = p.clips.map {
                if (it.id == id) {
                    val lo = maxOf(0L, it.inMs - it.startMs)
                    val ni = (it.inMs + dl).coerceIn(lo, maxOf(lo, it.outMs - MIN))
                    it.copy(startMs = it.startMs + (ni - it.inMs), inMs = ni)     // Anfang wandert, der Rest bleibt wo er ist
                } else it
            })
            Kind.TEXT -> p.copy(texts = p.texts.map {
                if (it.id == id) { val end = it.startMs + it.durMs; val ns = (it.startMs + dl).coerceIn(0L, end - MIN); it.copy(startMs = ns, durMs = end - ns) } else it
            })
            Kind.IMAGE -> p.copy(images = p.images.map {
                if (it.id == id) { val end = it.startMs + it.durMs; val ns = (it.startMs + dl).coerceIn(0L, end - MIN); it.copy(startMs = ns, durMs = end - ns) } else it
            })
            Kind.VIDEO -> p.copy(videos = p.videos.map {
                if (it.id == id) {
                    val end = it.startMs + it.durMs
                    val ns = maxOf((it.startMs + dl).coerceIn(0L, end - MIN), it.startMs - it.inMs)
                    it.copy(startMs = ns, inMs = it.inMs + (ns - it.startMs), durMs = end - ns)
                } else it
            })
            Kind.AUDIO -> p.copy(audios = p.audios.map {
                if (it.id == id) {
                    val end = it.startMs + it.durMs
                    val ns = maxOf((it.startMs + dl).coerceIn(0L, end - MIN), it.startMs - it.inMs)
                    it.copy(startMs = ns, inMs = it.inMs + (ns - it.startMs), durMs = end - ns)
                } else it
            })
            Kind.SUB -> p.copy(subs = p.subs.map {
                if (it.id == id) {
                    val ns = (it.startMs + dl).coerceIn(0L, it.endMs - MIN)
                    it.copy(startMs = ns, words = it.words.map { w -> w.copy(startMs = maxOf(w.startMs, ns), endMs = maxOf(w.endMs, ns)) })
                } else it
            })
        }
    }

    private fun trimEndRaw(k: Kind, id: Long, d: Float) {
        val p = project
        val dl = d.toLong()
        project = when (k) {
            Kind.CLIP -> p.copy(clips = p.clips.map {
                if (it.id == id) it.copy(outMs = (it.outMs + dl).coerceIn(it.inMs + MIN, it.srcDurMs)) else it
            })
            Kind.TEXT -> p.copy(texts = p.texts.map {
                if (it.id == id) { val ne = (it.startMs + it.durMs + dl).coerceAtLeast(it.startMs + MIN); it.copy(durMs = ne - it.startMs) } else it
            })
            Kind.IMAGE -> p.copy(images = p.images.map {
                if (it.id == id) { val ne = (it.startMs + it.durMs + dl).coerceAtLeast(it.startMs + MIN); it.copy(durMs = ne - it.startMs) } else it
            })
            Kind.VIDEO -> p.copy(videos = p.videos.map {
                if (it.id == id) {
                    val hi = it.startMs + (it.srcDurMs - it.inMs)
                    val ne = (it.startMs + it.durMs + dl).coerceIn(it.startMs + MIN, maxOf(it.startMs + MIN, hi))
                    it.copy(durMs = ne - it.startMs)
                } else it
            })
            Kind.AUDIO -> p.copy(audios = p.audios.map {
                if (it.id == id) {
                    val hi = it.startMs + (it.srcDurMs - it.inMs)
                    val ne = (it.startMs + it.durMs + dl).coerceIn(it.startMs + MIN, maxOf(it.startMs + MIN, hi))
                    it.copy(durMs = ne - it.startMs)
                } else it
            })
            Kind.SUB -> p.copy(subs = p.subs.map {
                if (it.id == id) {
                    val ne = (it.endMs + dl).coerceAtLeast(it.startMs + MIN)
                    it.copy(endMs = ne, words = it.words.map { w -> w.copy(startMs = minOf(w.startMs, ne), endMs = minOf(w.endMs, ne)) })
                } else it
            })
        }
    }

    // ---------- Untertitel ----------
    fun generateSubtitles(engine: AsrEngine, lang: String, hint: String) {
        val clips = project.clips
        if (clips.isEmpty()) { message = "Zuerst ein Video hinzufuegen."; return }
        playing = false
        busy = "Untertitel werden erzeugt ..."; busyProgress = -1f
        job = viewModelScope.launch {
            try {
                val subs = withContext(Dispatchers.Default) {
                    SubtitleGenerator.generate(getApplication(), clips, engine, lang, hint) { stage, pr -> busy = stage; busyProgress = pr }
                }
                if (subs.isEmpty()) message = "Keine Sprache erkannt."
                else {
                    snap()
                    val withIds = subs.map { it.copy(id = nid()) }
                    set(project.copy(subs = withIds))
                    selection = Selection(Kind.SUB, withIds.first().id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Untertitel fehlgeschlagen: ${e.message}"
            } finally { busy = null }
        }
    }

    // ---------- Export ----------
    fun export() {
        val p = project
        if (p.isEmpty) { message = "Das Projekt ist leer."; return }
        playing = false
        busy = "Exportiere Video ..."; busyProgress = 0f
        val ex = Exporter(getApplication(), images)
        exporter = ex
        try {
            ex.start(p, { busyProgress = it }) { err ->
                busy = null; exporter = null
                message = if (err == null) "Fertig! Gespeichert unter Filme/CutLab." else "Export fehlgeschlagen: $err"
            }
        } catch (e: Exception) {
            busy = null; exporter = null
            message = "Export fehlgeschlagen: ${e.message}"
        }
    }

    /** Beim allerersten Start: Sprachmodell (Deutsch, Englisch und viele weitere Sprachen) einmalig laden. */
    private fun firstRunSetup() {
        val ctx = getApplication<Application>()
        val skip = File(ctx.filesDir, "setup_skipped")
        val model = File(File(ctx.filesDir, "whisper"), "ggml-small-q5_1.bin")
        if (skip.exists() || (model.exists() && model.length() > 10_000_000)) return
        job = viewModelScope.launch {
            setupRunning = true
            busy = "Einmalige Einrichtung: Sprachmodell (Deutsch, Englisch u. a.) wird geladen ..."
            busyProgress = 0f
            try {
                withContext(Dispatchers.IO) {
                    if (WhisperLib.available) WhisperModels.ensure(ctx, AsrEngine.WHISPER_SMALL) { busyProgress = it }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Das Sprachmodell konnte nicht geladen werden (kein Internet?). Es wird beim ersten Erzeugen von Untertiteln nachgeholt."
            } finally { busy = null; setupRunning = false }
        }
    }

    fun cancelBusy() {
        if (setupRunning) { try { File(getApplication<Application>().filesDir, "setup_skipped").writeText("1") } catch (_: Exception) {} }
        try { WhisperLib.abort() } catch (_: Throwable) {}
        exporter?.cancel(); exporter = null
        job?.cancel()
        busy = null
    }
}
