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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

private const val MIN = 200L

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val engine = PreviewEngine(app)
    val images = ImageCache(app)
    val painter = OverlayPainter { images.get(it) }

    var project by mutableStateOf(Project()); private set
    var posMs by mutableLongStateOf(0L); private set
    var playing by mutableStateOf(false); private set
    var selection by mutableStateOf<Selection?>(null); private set
    var zoom by mutableFloatStateOf(70f)            // dp pro Sekunde
    var busy by mutableStateOf<String?>(null); private set
    var busyProgress by mutableFloatStateOf(-1f); private set
    var message by mutableStateOf<String?>(null)

    private val undoStack = ArrayDeque<Project>()
    private var nextId = 1L
    private var lastLive = 0L
    private var clipAcc = 0f
    private var job: Job? = null
    private var exporter: Exporter? = null

    private data class Meta(val uri: String, val dur: Long, val w: Int, val h: Int)

    init {
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

    override fun onCleared() { engine.release() }

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

    fun addVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val metas = withContext(Dispatchers.IO) { uris.mapNotNull { probe(it) } }
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
            withContext(Dispatchers.IO) { images.get(uri.toString()) }
            snap()
            val (s, d) = slot()
            val id = nid()
            set(project.copy(images = project.images + ImageItem(id, uri.toString(), s, d)))
            selection = Selection(Kind.IMAGE, id)
        }
    }

    fun addAudio(uri: Uri) {
        if (project.clips.isEmpty()) { message = "Zuerst ein Video hinzufuegen."; return }
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { probe(uri) }
            val name = withContext(Dispatchers.IO) { nameOf(uri) }
            if (meta == null) { message = "Audio konnte nicht gelesen werden."; return@launch }
            val total = project.totalMs
            val start = posMs.coerceAtMost(total - MIN)
            val dur = minOf(meta.dur, total - start)
            snap()
            val id = nid()
            set(project.copy(audios = project.audios + AudioItem(id, uri.toString(), name, meta.dur, start, 0, dur)))
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
    fun transform(dx: Float, dy: Float, z: Float) {
        val s = selection ?: return
        live { p ->
            when (s.kind) {
                Kind.TEXT -> p.copy(texts = p.texts.map {
                    if (it.id == s.id) it.copy(x = (it.x + dx).coerceIn(0f, 1f), y = (it.y + dy).coerceIn(0f, 1f), size = (it.size * z).coerceIn(0.02f, 0.25f)) else it
                })
                Kind.IMAGE -> p.copy(images = p.images.map {
                    if (it.id == s.id) it.copy(x = (it.x + dx).coerceIn(0f, 1f), y = (it.y + dy).coerceIn(0f, 1f), scale = (it.scale * z).coerceIn(0.05f, 1.5f)) else it
                })
                Kind.SUB -> p.copy(subStyle = p.subStyle.copy(y = (p.subStyle.y + dy).coerceIn(0.05f, 0.95f), size = (p.subStyle.size * z).coerceIn(0.025f, 0.15f)))
                else -> p
            }
        }
    }
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
