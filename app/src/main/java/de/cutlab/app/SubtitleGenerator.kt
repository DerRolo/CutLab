package de.cutlab.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Sprachen -> Vosk-Modelle (offline). Bei Bedarf Namen/Versionen auf https://alphacephei.com/vosk/models pruefen. */
data class VoskLang(val code: String, val label: String, val folder: String)

object VoskModels {
    val langs = listOf(
        VoskLang("de", "Deutsch", "vosk-model-small-de-0.15"),
        VoskLang("en", "Englisch", "vosk-model-small-en-us-0.15"),
        VoskLang("fr", "Franzoesisch", "vosk-model-small-fr-0.22"),
        VoskLang("es", "Spanisch", "vosk-model-small-es-0.42"),
        VoskLang("it", "Italienisch", "vosk-model-small-it-0.22"),
        VoskLang("tr", "Tuerkisch", "vosk-model-small-tr-0.3"),
        VoskLang("ru", "Russisch", "vosk-model-small-ru-0.22"),
        VoskLang("pt", "Portugiesisch", "vosk-model-small-pt-0.3"),
        VoskLang("nl", "Niederlaendisch", "vosk-model-small-nl-0.22"),
        VoskLang("pl", "Polnisch", "vosk-model-small-pl-0.22")
    )

    fun ensure(ctx: Context, l: VoskLang, onProgress: (Float) -> Unit): File {
        val root = File(ctx.filesDir, "vosk").apply { mkdirs() }
        val dir = File(root, l.folder)
        if (File(dir, "am").exists()) return dir
        val zip = File(ctx.cacheDir, "${l.folder}.zip")
        val conn = URL("https://alphacephei.com/vosk/models/${l.folder}.zip").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        val total = conn.contentLengthLong
        conn.inputStream.use { ins ->
            zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var read = 0L
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); read += n
                    if (total > 0) onProgress(read.toFloat() / total)
                }
            }
        }
        ZipInputStream(zip.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val f = File(root, e.name)
                if (f.canonicalPath.startsWith(root.canonicalPath)) {
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                }
                e = zin.nextEntry
            }
        }
        zip.delete()
        return dir
    }
}

object AudioDecoder {
    /** Dekodiert [startMs, endMs] einer Datei zu 16 kHz / mono / 16 Bit PCM und liefert es in Bloecken. */
    fun decode(
        ctx: Context, uri: Uri, startMs: Long, endMs: Long,
        onChunk: (ByteArray, Int) -> Unit, onProgress: (Float) -> Unit
    ) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        if (track < 0) { ex.release(); return }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val startUs = startMs * 1000; val endUs = endMs * 1000
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val outBuf = ByteArray(8000); var outN = 0
        var acc = 0; var sum = 0L; var cnt = 0; var last = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0 || ex.sampleTime > endUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= startUs - 100_000) {
                        val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (f in 0 until frames) {
                            var m = 0
                            for (k in 0 until ch) m += sb.get()
                            sum += m / ch; cnt++
                            acc += 16000
                            while (acc >= inRate) {
                                acc -= inRate
                                if (cnt > 0) last = (sum / cnt).toInt()
                                sum = 0; cnt = 0
                                outBuf[outN++] = (last and 0xFF).toByte()
                                outBuf[outN++] = ((last shr 8) and 0xFF).toByte()
                                if (outN >= outBuf.size) { onChunk(outBuf, outN); outN = 0 }
                            }
                        }
                        onProgress(((info.presentationTimeUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    inRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            if (outN > 0) onChunk(outBuf, outN)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release(); ex.release()
        }
    }
}

/** Welche Spracherkennung benutzt wird. */
enum class AsrEngine(val label: String, val note: String) {
    WHISPER_SMALL("Genau (Whisper small)", "empfohlen · ca. 190 MB · braucht etwas länger"),
    WHISPER_BASE("Schnell (Whisper base)", "ca. 60 MB · deutlich schneller, etwas ungenauer"),
    VOSK("Einfach (Vosk)", "ca. 45 MB · schnell, aber oft falsch")
}

data class AsrLang(val code: String, val label: String)

object AsrLangs {
    val list = listOf(
        AsrLang("auto", "Automatisch erkennen"),
        AsrLang("de", "Deutsch"), AsrLang("en", "Englisch"), AsrLang("fr", "Französisch"),
        AsrLang("es", "Spanisch"), AsrLang("it", "Italienisch"), AsrLang("tr", "Türkisch"),
        AsrLang("ru", "Russisch"), AsrLang("pt", "Portugiesisch"), AsrLang("nl", "Niederländisch"),
        AsrLang("pl", "Polnisch"), AsrLang("uk", "Ukrainisch"), AsrLang("ar", "Arabisch"),
        AsrLang("ja", "Japanisch"), AsrLang("zh", "Chinesisch")
    )
}

/** Native Whisper-Bibliothek (whisper.cpp). Ist sie nicht eingebaut, bleibt available = false. */
object WhisperLib {
    val available: Boolean by lazy {
        try { System.loadLibrary("cutlab_whisper"); nativeAvailable() } catch (e: Throwable) { false }
    }

    private external fun nativeAvailable(): Boolean
    external fun initContext(modelPath: String, small: Boolean): Long
    external fun progress(): Int
    external fun freeContext(ctx: Long)
    external fun transcribe(ctx: Long, audio: FloatArray, lang: String, prompt: String, threads: Int): Array<String>?
    external fun abort()

    // Geladenes Modell bleibt nach der Erkennung noch 3 Minuten im Speicher (naechster Durchlauf startet sofort)
    private var cachedCtx = 0L
    private var cachedKey: String? = null
    private var busy = false
    private val handler = Handler(Looper.getMainLooper())
    private val releaseTask = Runnable {
        synchronized(WhisperLib) {
            if (!busy && cachedCtx != 0L) { freeContext(cachedCtx); cachedCtx = 0L; cachedKey = null }
        }
    }

    @Synchronized
    fun acquire(key: String, path: String): Long {
        handler.removeCallbacks(releaseTask)
        if (cachedKey != key || cachedCtx == 0L) {
            if (cachedCtx != 0L) freeContext(cachedCtx)
            cachedCtx = initContext(path, key == AsrEngine.WHISPER_SMALL.name)
            cachedKey = if (cachedCtx != 0L) key else null
        }
        busy = true
        return cachedCtx
    }

    @Synchronized
    fun release() {
        busy = false
        handler.postDelayed(releaseTask, 180_000)
    }
}

object WhisperModels {
    private fun fileName(e: AsrEngine) = if (e == AsrEngine.WHISPER_SMALL) "ggml-small-q5_1.bin" else "ggml-base-q5_1.bin"

    fun ensure(ctx: Context, e: AsrEngine, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.filesDir, "whisper").apply { mkdirs() }
        val name = fileName(e)
        val f = File(dir, name)
        if (f.exists() && f.length() > 10_000_000) return f
        val tmp = File(dir, "$name.part")
        val conn = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$name").openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "CutLab")
        if (conn.responseCode != 200) error("Modell-Download fehlgeschlagen (HTTP ${conn.responseCode})")
        val total = conn.contentLengthLong
        conn.inputStream.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var read = 0L
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); read += n
                    onProgress(if (total > 0) read.toFloat() / total else -1f)
                }
            }
        }
        if (!tmp.renameTo(f)) error("Modell konnte nicht gespeichert werden")
        return f
    }
}

object SubtitleGenerator {
    /** Blockierend -> in Dispatchers.Default aufrufen. IDs werden vom ViewModel vergeben. */
    fun generate(
        ctx: Context, clips: List<Clip>, engine: AsrEngine, langCode: String, hint: String,
        onStage: (String, Float) -> Unit
    ): List<Subtitle> {
        val words = when (engine) {
            AsrEngine.VOSK -> {
                if (langCode == "auto") error("Vosk braucht eine feste Sprache. Bitte oben eine Sprache wählen.")
                val l = VoskModels.langs.find { it.code == langCode } ?: error("Für diese Sprache gibt es kein Vosk-Modell.")
                vosk(ctx, clips, l, onStage)
            }
            else -> whisper(ctx, clips, engine, langCode, hint, onStage)
        }
        return group(words.sortedBy { it.startMs })
    }

    private fun whisper(
        ctx: Context, clips: List<Clip>, engine: AsrEngine, lang: String, hint: String,
        onStage: (String, Float) -> Unit
    ): List<Word> {
        if (!WhisperLib.available) error("Whisper ist in dieser App-Version nicht enthalten. Bitte \"Einfach (Vosk)\" wählen.")
        val model = WhisperModels.ensure(ctx, engine) { onStage(if (it >= 0f) "Sprachmodell wird geladen: ${(it * 100).toInt()} %" else "Sprachmodell wird geladen ...", it) }
        onStage("Modell wird gestartet ...", -1f)
        val handle = WhisperLib.acquire(engine.name, model.absolutePath)
        if (handle == 0L) error("Whisper-Modell konnte nicht geladen werden.")
        val words = mutableListOf<Word>()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        try {
            for ((ci, c) in clips.withIndex()) {
                val offset = c.startMs
                val tag = if (clips.size > 1) " (Clip ${ci + 1} von ${clips.size})" else ""
                onStage("Audio wird vorbereitet$tag ...", -1f)
                val pcm = ByteArrayOutputStream()
                AudioDecoder.decode(ctx, Uri.parse(c.uri), c.inMs, c.outMs, { b, n -> pcm.write(b, 0, n) }, { })
                val bytes = pcm.toByteArray()
                val n = bytes.size / 2
                if (n < 8000) continue
                val samples = FloatArray(n)
                for (i in 0 until n) {
                    val lo = bytes[2 * i].toInt() and 0xFF
                    val hi = bytes[2 * i + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort() / 32768f
                }
                // Fortschritt der Erkennung laufend abfragen
                val running = java.util.concurrent.atomic.AtomicBoolean(true)
                val poll = Thread {
                    while (running.get()) {
                        val pr = WhisperLib.progress().coerceIn(0, 100)
                        onStage("Sprache wird erkannt$tag: $pr %", (pr / 100f))
                        try { Thread.sleep(300) } catch (_: InterruptedException) { break }
                    }
                }
                poll.start()
                val rows = try {
                    WhisperLib.transcribe(handle, samples, lang, hint.trim(), threads)
                        ?: error("Spracherkennung fehlgeschlagen oder abgebrochen.")
                } finally { running.set(false); poll.interrupt() }

                // Zeilen lesen: Wortbeginn (genau, per DTW) und Ende des Satzes
                val parsed = ArrayList<Triple<Long, Long, String>>()
                for (r in rows) {
                    val a = r.indexOf('|'); val b = r.indexOf('|', a + 1)
                    if (a < 0 || b < 0) continue
                    val st = r.substring(0, a).toLongOrNull() ?: continue
                    val se = r.substring(a + 1, b).toLongOrNull() ?: continue
                    val txt = r.substring(b + 1).trim()
                    if (txt.isEmpty()) continue
                    // Platzhalter wie [BLANK_AUDIO] oder (Musik) ueberspringen
                    if ((txt.startsWith("[") && txt.endsWith("]")) || (txt.startsWith("(") && txt.endsWith(")")) || txt.startsWith("\u266A")) continue
                    // reine Satzzeichen an das vorige Wort haengen
                    if (txt.none { it.isLetterOrDigit() } && parsed.isNotEmpty()) {
                        val last = parsed.removeAt(parsed.lastIndex)
                        parsed.add(Triple(last.first, last.second, last.third + txt))
                        continue
                    }
                    parsed.add(Triple(st, se, txt))
                }
                // Wortende: nie ueber das naechste Wort, den Satz oder eine plausible Sprechdauer hinaus (Pausen bleiben Pausen)
                for ((k, w) in parsed.withIndex()) {
                    val start = maxOf(w.first, if (k > 0) parsed[k - 1].first else 0L)
                    val dur = (250L + 60L * w.third.length).coerceAtMost(1300L)
                    var end = minOf(w.second, start + dur)
                    if (k + 1 < parsed.size && parsed[k + 1].first > start) end = minOf(end, parsed[k + 1].first)
                    if (end < start + 80) end = start + 80
                    words.add(Word(w.third, offset + start, offset + end))
                }
            }
        } finally { WhisperLib.release() }
        return words
    }

    /** Pro 20 ms: ist dort Sprache/Ton zu hoeren? (Lautstaerke ueber dem Grundrauschen) */
    private fun speechFrames(samples: FloatArray): BooleanArray {
        val fl = 320
        val n = samples.size / fl
        if (n == 0) return BooleanArray(0)
        val e = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            val o = i * fl
            for (k in 0 until fl) { val v = samples[o + k]; sum += v * v }
            e[i] = kotlin.math.sqrt(sum / fl)
        }
        val sorted = e.sortedArray()
        val noise = sorted[(n * 0.2).toInt().coerceAtMost(n - 1)]
        val peak = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)]
        val thr = maxOf(noise * 2.5f, peak * 0.06f, 0.004f)
        val raw = BooleanArray(n) { e[it] > thr }
        val out = raw.copyOf()
        var last = -100
        for (i in 0 until n) {                                    // kurze Luecken (bis 100 ms) schliessen
            if (raw[i]) {
                if (i - last in 2..6) for (j in last + 1 until i) out[j] = true
                last = i
            }
        }
        return out
    }

    private fun vosk(ctx: Context, clips: List<Clip>, lang: VoskLang, onStage: (String, Float) -> Unit): List<Word> {
        val dir = VoskModels.ensure(ctx, lang) { onStage("Sprachmodell wird geladen ...", it) }
        onStage("Sprache wird erkannt ...", 0f)
        val model = Model(dir.absolutePath)
        val words = mutableListOf<Word>()
        val total = clips.sumOf { it.durMs }.coerceAtLeast(1L)
        try {
            for (c in clips) {
                val base = c.startMs
                val rec = Recognizer(model, 16000f)
                rec.setWords(true)
                fun take(json: String) {
                    val arr = JSONObject(json).optJSONArray("result") ?: return
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        words.add(Word(o.getString("word"), base + (o.getDouble("start") * 1000).toLong(), base + (o.getDouble("end") * 1000).toLong()))
                    }
                }
                AudioDecoder.decode(ctx, Uri.parse(c.uri), c.inMs, c.outMs,
                    { buf, n -> if (rec.acceptWaveForm(buf, n)) take(rec.result) },
                    { p -> onStage("Sprache wird erkannt ...", (base + p * c.durMs) / total) })
                take(rec.finalResult)
                rec.close()
            }
        } finally { model.close() }
        return words
    }

    /** Woerter zu gut lesbaren Untertitel-Zeilen zusammenfassen (Satzende, Pausen, Laenge). */
    private fun group(words: List<Word>): List<Subtitle> {
        val out = mutableListOf<Subtitle>()
        var cur = mutableListOf<Word>()
        fun flush() {
            if (cur.isEmpty()) return
            out.add(Subtitle(0, cur.first().startMs, cur.last().endMs, cur.joinToString(" ") { it.text }, cur.toList()))
            cur = mutableListOf()
        }
        for (w in words) {
            if (cur.isNotEmpty()) {
                val last = cur.last().text
                val sentenceEnd = last.endsWith(".") || last.endsWith("?") || last.endsWith("!") || last.endsWith("\u2026")
                val comma = last.endsWith(",") && cur.size >= 3
                val gap = w.startMs - cur.last().endMs
                val len = cur.sumOf { it.text.length + 1 }
                if (gap > 500 || sentenceEnd || comma || cur.size >= 6 || len + w.text.length > 32 || w.endMs - cur.first().startMs > 3500) flush()
            }
            cur.add(w)
        }
        flush()
        return out.mapIndexed { i, s ->
            val next = out.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            s.copy(endMs = minOf(s.endMs + 250, next))
        }
    }
}
