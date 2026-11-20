package de.cutlab.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
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

object SubtitleGenerator {
    /** Blockierend -> in Dispatchers.Default/IO aufrufen. IDs werden vom ViewModel vergeben. */
    fun generate(ctx: Context, clips: List<Clip>, lang: VoskLang, onStage: (String, Float) -> Unit): List<Subtitle> {
        val dir = VoskModels.ensure(ctx, lang) { onStage("Sprachmodell wird geladen ...", it) }
        onStage("Sprache wird erkannt ...", 0f)
        val model = Model(dir.absolutePath)
        val words = mutableListOf<Word>()
        val total = clips.sumOf { it.durMs }.coerceAtLeast(1L)
        try {
            var offset = 0L
            for (c in clips) {
                val base = offset
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
                offset += c.durMs
            }
        } finally { model.close() }
        return group(words)
    }

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
                val gap = w.startMs - cur.last().endMs
                val len = cur.sumOf { it.text.length + 1 }
                if (gap > 700 || cur.size >= 6 || len + w.text.length > 32 || w.endMs - cur.first().startMs > 3500) flush()
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
