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
import java.util.UUID

private const val MIN = 200L

/** Magnetische Hilfslinien (Anteil von Breite/Hoehe): Raender, Goldener Schnitt, Mitte. */
val GUIDES_X = floatArrayOf(0.1f, 0.5f, 0.9f)
val GUIDES_Y = floatArrayOf(0.1f, 0.382f, 0.5f, 0.618f, 0.9f)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val engine = PreviewEngine(app)
    val images = ImageCache(app)
    val painter = OverlayPainter { images.get(it) }
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
    private var clipAcc = 0f
    private var job: Job? = null
    private var exporter: Exporter? = null

    private data class Meta(val uri: String, val dur: Long, val w: Int, val h: Int)

    init {
        PresetStore.load(app).let { presets = it.first; defaultPreset = it.second }
        val id = ProjectStore.lastId(app) ?: ProjectStore.newId().also { ProjectStore.save(app, it, newProjectData()) }
        currentId = id
        ProjectStore.setLast(app, id)
        applyLoaded(ProjectStore.load(app, id) ?: Project())
        viewModelScope.launch {
            snapshotFlow { project }.collectLatest { p ->
                delay(600)
                withContext(Dispatchers.IO) { try { ProjectStore.save(getApplication(), currentId, p) } catch (_: Exception) {} }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(30)
                val isPlaying = engine.player.isPlaying
                playing = isPlaying
                if (isPlaying) posMs = engine.pos(project.clips).coerceAtMost(project.totalMs)
                else if (engine.player.playbackState == androidx.media3.common.Player.STATE_ENDED) posMs = project.totalMs
                engine.syncAudio(project.audios, isPlaying, posMs)
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
    fun seekTo(ms: Long) { val t = ms.coerceIn(0L, project.totalMs); posMs = t; engine.seek(project.clips, t) }
    fun togglePlay() = engine.toggle(project.clips, project.totalMs)
    fun onPreviewTap() {
        val k = selection?.kind
        if (k == Kind.TEXT || k == Kind.IMAGE || k == Kind.SUB) selection = null else togglePlay()
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
            Meta(u.toString(), d, w, h)
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
            audios = loaded.audios.filter { ok(it.uri) }
        )
        undoStack.clear(); selection = null; posMs = 0; resetPreviewZoom()
        project = p
        nextId = (p.clips.map { it.id } + p.texts.map { it.id } + p.images.map { it.id } +
            p.audios.map { it.id } + p.subs.map { it.id }).maxOrNull()?.plus(1) ?: 1L
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
        engine.player.pause()
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
            for (m in metas) p = p.copy(clips = p.clips + Clip(nid(), m.uri, m.dur, 0, m.dur))
            set(p, true)
        }
    }

    private fun slot(): Pair<Long, Long> {
        val total = project.totalMs
        val d = minOf(3000L, total).coerceAtLeast(MIN)
        return posMs.coerceAtMost((total - d).coerceAtLeast(0L)) to d
    }

    fun addText(): Long? {
        if (project.clips.isEmpty()) { message = "Zuerst ein Video hinzufuegen."; return null }
        snap()
        val (s, d) = slot()
        val id = nid()
        set(project.copy(texts = project.texts + TextItem(id, "Text", s, d)))
        selection = Selection(Kind.TEXT, id)
        return id
    }

    fun addImage(uri: Uri) {
        if (project.clips.isEmpty()) { message = "Zuerst ein Video hinzufuegen."; return }
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { importFile(uri, "jpg")?.also { images.get(it) } }
            if (local == null) { message = "Bild konnte nicht gelesen werden."; return@launch }
            snap()
            val (s, d) = slot()
            val id = nid()
            set(project.copy(images = project.images + ImageItem(id, local, s, d)))
            selection = Selection(Kind.IMAGE, id)
        }
    }

    fun addAudio(uri: Uri) {
        if (project.clips.isEmpty()) { message = "Zuerst ein Video hinzufuegen."; return }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { nameOf(uri) }
            val meta = withContext(Dispatchers.IO) {
                importFile(uri, name.substringAfterLast('.', "mp3").take(5))?.let { probe(Uri.parse(it)) }
            }
            if (meta == null) { message = "Audio konnte nicht gelesen werden."; return@launch }
            val total = project.totalMs
            val start = posMs.coerceAtMost(total - MIN)
            val dur = minOf(meta.dur, total - start)
            snap()
            val id = nid()
            set(project.copy(audios = project.audios + AudioItem(id, meta.uri, name, meta.dur, start, 0, dur)))
            selection = Selection(Kind.AUDIO, id)
        }
    }

    fun setAspect(a: AspectPreset) { snap(); project = project.copy(aspect = a) }

    // ---------- Schneiden / Loeschen / Text ----------
    fun split() {
        var acc = 0L
        val cs = project.clips
        for ((i, c) in cs.withIndex()) {
            if (posMs > acc + 250 && posMs < acc + c.durMs - 250) {
                snap()
                val cut = c.inMs + (posMs - acc)
                val l = c.copy(outMs = cut)
                val r = c.copy(id = nid(), inMs = cut)
                set(project.copy(clips = cs.take(i) + l + r + cs.drop(i + 1)), true)
                selection = Selection(Kind.CLIP, r.id)
                return
            }
            acc += c.durMs
        }
        message = "Playhead an die Stelle setzen, an der geschnitten werden soll."
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
            Kind.SUB -> { rawX = 0.5f; rawY = p.subStyle.y }
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
        snapX = if (s.kind == Kind.SUB) null else gx
        snapY = gy
        live { p ->
            when (s.kind) {
                Kind.TEXT -> p.copy(texts = p.texts.map {
                    if (it.id == s.id) it.copy(x = sx, y = sy, size = (it.size * z).coerceIn(0.02f, 0.25f)) else it
                })
                Kind.IMAGE -> p.copy(images = p.images.map {
                    if (it.id == s.id) it.copy(x = sx, y = sy, scale = (it.scale * z).coerceIn(0.05f, 1.5f)) else it
                })
                Kind.SUB -> p.copy(subStyle = p.subStyle.copy(y = sy, size = (p.subStyle.size * z).coerceIn(0.025f, 0.15f)))
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
    fun setImageScale(id: Long, v: Float) = live { p -> p.copy(images = p.images.map { if (it.id == id) it.copy(scale = v) else it }) }
    fun setSubKind(k: SubKind) { snap(); project = project.copy(subStyle = project.subStyle.copy(kind = k)) }
    fun setSubSize(v: Float) = live { p -> p.copy(subStyle = p.subStyle.copy(size = v)) }
    fun setSubColor(c: Int) = live { p -> p.copy(subStyle = p.subStyle.copy(color = c)) }
    fun setSubHighlight(c: Int) = live { p -> p.copy(subStyle = p.subStyle.copy(highlight = c)) }

    // ---------- Timeline-Drags ----------
    fun beginDrag() { snap(); clipAcc = 0f }
    fun endDrag() { engine.setClips(project.clips, posMs); posMs = posMs.coerceAtMost(project.totalMs) }

    fun moveItem(k: Kind, id: Long, d: Float) {
        val p = project
        val total = p.totalMs
        fun cs(s: Long, dur: Long) = (s + d.toLong()).coerceIn(0L, maxOf(0L, total - dur))
        project = when (k) {
            Kind.CLIP -> {
                clipAcc += d
                val l = p.clips.toMutableList()
                val i = l.indexOfFirst { it.id == id }
                if (i < 0) p else {
                    if (clipAcc > 0 && i < l.lastIndex && clipAcc > l[i + 1].durMs / 2f) { clipAcc -= l[i + 1].durMs; Collections.swap(l, i, i + 1) }
                    else if (clipAcc < 0 && i > 0 && -clipAcc > l[i - 1].durMs / 2f) { clipAcc += l[i - 1].durMs; Collections.swap(l, i, i - 1) }
                    p.copy(clips = l)
                }
            }
            Kind.TEXT -> p.copy(texts = p.texts.map { if (it.id == id) it.copy(startMs = cs(it.startMs, it.durMs)) else it })
            Kind.IMAGE -> p.copy(images = p.images.map { if (it.id == id) it.copy(startMs = cs(it.startMs, it.durMs)) else it })
            Kind.AUDIO -> p.copy(audios = p.audios.map { if (it.id == id) it.copy(startMs = cs(it.startMs, it.durMs)) else it })
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

    fun trimStart(k: Kind, id: Long, d: Float) {
        val p = project
        val dl = d.toLong()
        project = when (k) {
            Kind.CLIP -> p.copy(clips = p.clips.map {
                if (it.id == id) it.copy(inMs = (it.inMs + dl).coerceIn(0L, maxOf(0L, it.outMs - MIN))) else it
            })
            Kind.TEXT -> p.copy(texts = p.texts.map {
                if (it.id == id) { val end = it.startMs + it.durMs; val ns = (it.startMs + dl).coerceIn(0L, end - MIN); it.copy(startMs = ns, durMs = end - ns) } else it
            })
            Kind.IMAGE -> p.copy(images = p.images.map {
                if (it.id == id) { val end = it.startMs + it.durMs; val ns = (it.startMs + dl).coerceIn(0L, end - MIN); it.copy(startMs = ns, durMs = end - ns) } else it
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

    fun trimEnd(k: Kind, id: Long, d: Float) {
        val p = project
        val dl = d.toLong()
        val total = p.totalMs
        project = when (k) {
            Kind.CLIP -> p.copy(clips = p.clips.map {
                if (it.id == id) it.copy(outMs = (it.outMs + dl).coerceIn(it.inMs + MIN, it.srcDurMs)) else it
            })
            Kind.TEXT -> p.copy(texts = p.texts.map {
                if (it.id == id) { val ne = (it.startMs + it.durMs + dl).coerceIn(it.startMs + MIN, maxOf(it.startMs + MIN, total)); it.copy(durMs = ne - it.startMs) } else it
            })
            Kind.IMAGE -> p.copy(images = p.images.map {
                if (it.id == id) { val ne = (it.startMs + it.durMs + dl).coerceIn(it.startMs + MIN, maxOf(it.startMs + MIN, total)); it.copy(durMs = ne - it.startMs) } else it
            })
            Kind.AUDIO -> p.copy(audios = p.audios.map {
                if (it.id == id) {
                    val hi = minOf(it.startMs + (it.srcDurMs - it.inMs), maxOf(total, it.startMs + MIN))
                    val ne = (it.startMs + it.durMs + dl).coerceIn(it.startMs + MIN, maxOf(it.startMs + MIN, hi))
                    it.copy(durMs = ne - it.startMs)
                } else it
            })
            Kind.SUB -> p.copy(subs = p.subs.map {
                if (it.id == id) {
                    val ne = (it.endMs + dl).coerceIn(it.startMs + MIN, maxOf(it.startMs + MIN, total))
                    it.copy(endMs = ne, words = it.words.map { w -> w.copy(startMs = minOf(w.startMs, ne), endMs = minOf(w.endMs, ne)) })
                } else it
            })
        }
    }

    // ---------- Untertitel ----------
    fun generateSubtitles(lang: VoskLang) {
        val clips = project.clips
        if (clips.isEmpty()) { message = "Zuerst ein Video hinzufuegen."; return }
        engine.player.pause()
        busy = "Untertitel (${lang.label})"; busyProgress = -1f
        job = viewModelScope.launch {
            try {
                val subs = withContext(Dispatchers.Default) {
                    SubtitleGenerator.generate(getApplication(), clips, lang) { stage, pr -> busy = stage; busyProgress = pr }
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
            } catch (e: Exception) {
                message = "Untertitel fehlgeschlagen: ${e.message}"
            } finally { busy = null }
        }
    }

    // ---------- Export ----------
    fun export() {
        val p = project
        if (p.clips.isEmpty()) { message = "Kein Video im Projekt."; return }
        engine.player.pause()
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

    fun cancelBusy() {
        exporter?.cancel(); exporter = null
        job?.cancel()
        busy = null
    }
}
