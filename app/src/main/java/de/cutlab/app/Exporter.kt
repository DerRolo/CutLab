package de.cutlab.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import java.io.File

/** Ein einziges Overlay in voller Bildgroesse; zeichnet Text/Bilder/Untertitel zum jeweiligen Zeitpunkt. */
@OptIn(UnstableApi::class)
class ProjectOverlay(
    private val project: Project, private val painter: OverlayPainter,
    private val w: Int, private val h: Int
) : BitmapOverlay() {
    private var lastKey: String? = null
    private var last: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1000
        val key = painter.key(project, t)
        val cached = last
        if (cached == null || key != lastKey) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            painter.draw(Canvas(bmp), w, h, t, project)
            last = bmp; lastKey = key
            return bmp
        }
        return cached
    }
}

@OptIn(UnstableApi::class)
class Exporter(private val ctx: Context, private val images: ImageCache) {
    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var frameSource: FrameSource? = null

    /** Muss auf dem Main-Thread aufgerufen werden. onDone(null) = Erfolg, sonst Fehlertext. */
    fun start(p: Project, onProgress: (Float) -> Unit, onDone: (String?) -> Unit) {
        val total = p.totalMs
        val cw = p.aspect.w
        val ch = p.aspect.h
        val fit = Presentation.createForWidthAndHeight(cw, ch, Presentation.LAYOUT_SCALE_TO_FIT)

        if (total <= 0L) { onDone("Das Projekt ist leer."); return }

        // Schwarzes Bild fuer Luecken auf der Hauptspur (und fuer Projekte ohne Hauptvideo)
        val black = File(ctx.cacheDir, "black_${cw}x$ch.png")
        if (!black.exists()) {
            val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF000000.toInt())
            black.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun blackItem(ms: Long): EditedMediaItem = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(black)).setImageDurationMs(ms).build()
        ).setFrameRate(30).build()

        // Hauptspur: nur Bild (Ton kommt unten pro Clip als eigene Audiospur mit korrektem Startpunkt)
        val vItems = ArrayList<EditedMediaItem>()
        var cursor = 0L
        for (c in p.clips.sortedBy { it.startMs }) {
            val start = maxOf(c.startMs, cursor)
            if (start > cursor) vItems.add(blackItem(start - cursor))
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inMs).setEndPositionMs(c.outMs).build()
            ).build()
            vItems.add(
                EditedMediaItem.Builder(mi)
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList<AudioProcessor>(), listOf<Effect>(fit)))
                    .build()
            )
            cursor = start + c.durMs
        }
        if (total > cursor) vItems.add(blackItem(total - cursor))
        val seqs = mutableListOf(EditedMediaItemSequence.Builder(*vItems.toTypedArray()).build())

        // Ton der Hauptclips
        for (c in p.clips) {
            if (!hasAudioTrack(c.uri)) continue
            val dur = minOf(c.durMs, total - c.startMs)
            if (dur < 100) continue
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inMs).setEndPositionMs(c.inMs + dur).build()
            ).build()
            val item = EditedMediaItem.Builder(mi).setRemoveVideo(true).build()
            val b = EditedMediaItemSequence.Builder()
            if (c.startMs > 0) b.addGap(c.startMs * 1000)
            b.addItem(item)
            seqs.add(b.build())
        }

        for (a in p.audios) {
            val dur = minOf(a.durMs, total - a.startMs)
            if (dur < 100) continue
            val mi = MediaItem.Builder().setUri(a.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a.inMs).setEndPositionMs(a.inMs + dur).build()
            ).build()
            val item = EditedMediaItem.Builder(mi).setRemoveVideo(true).build()
            val b = EditedMediaItemSequence.Builder()
            if (a.startMs > 0) b.addGap(a.startMs * 1000)
            b.addItem(item)
            seqs.add(b.build())
        }

        // Ton der Overlay-Videos
        for (v in p.videos) {
            if (v.muted || !v.hasAudio) continue
            val dur = minOf(v.durMs, total - v.startMs)
            if (dur < 100) continue
            val mi = MediaItem.Builder().setUri(v.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(v.inMs).setEndPositionMs(v.inMs + dur).build()
            ).build()
            val item = EditedMediaItem.Builder(mi).setRemoveVideo(true).build()
            val b = EditedMediaItemSequence.Builder()
            if (v.startMs > 0) b.addGap(v.startMs * 1000)
            b.addItem(item)
            seqs.add(b.build())
        }

        // Bilder der Overlay-Videos werden Bild fuer Bild in das Overlay gezeichnet
        val frames = FrameSource(ctx, cw)
        frameSource = frames
        val overlay = ProjectOverlay(p, OverlayPainter({ images.get(it) }, { v, ms -> frames.get(v, ms) }), cw, ch)
        val comp = Composition.Builder(seqs).setEffects(
            Effects(
                emptyList<AudioProcessor>(),
                listOf<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))
            )
        ).build()

        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val tf = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(10_000_000).build())
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    running = false
                    frameSource?.release()
                    Thread {
                        val err = try { saveToGallery(out); null } catch (e: Exception) { e.message ?: "Speichern fehlgeschlagen" }
                        out.delete()
                        handler.post { onDone(err) }
                    }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    running = false
                    frameSource?.release()
                    out.delete()
                    onDone(exportException.message ?: "Unbekannter Fehler")
                }
            })
            .build()
        transformer = tf
        running = true
        tf.start(comp, out.absolutePath)

        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                if (tf.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                handler.postDelayed(this, 300)
            }
        })
    }

    private fun hasAudioTrack(uri: String): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, Uri.parse(uri))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (e: Exception) { false } finally { r.release() }
    }

    fun cancel() {
        running = false
        transformer?.cancel()
    }

    private fun saveToGallery(file: File) {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "CutLab_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CutLab")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore-Eintrag nicht moeglich")
        r.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        v.clear(); v.put(MediaStore.Video.Media.IS_PENDING, 0)
        r.update(uri, v, null, null)
    }
}

/** Liefert einzelne Bilder aus Overlay-Videos fuer den Export (24 Bilder pro Sekunde). */
class FrameSource(private val ctx: Context, private val canvasW: Int) {
    private val rets = HashMap<Long, MediaMetadataRetriever>()
    private val last = HashMap<Long, Pair<Long, Bitmap>>()

    @Synchronized
    fun get(v: VideoItem, srcMs: Long): Bitmap? {
        val q = srcMs * 24 / 1000
        last[v.id]?.let { if (it.first == q) return it.second }
        val r = rets.getOrPut(v.id) { MediaMetadataRetriever().apply { setDataSource(ctx, Uri.parse(v.uri)) } }
        val dw = (v.scale * canvasW).toInt().coerceAtLeast(16)
        val dh = (dw / v.aspect).toInt().coerceAtLeast(16)
        val bmp = try {
            r.getScaledFrameAtTime(q * 1_000_000L / 24, MediaMetadataRetriever.OPTION_CLOSEST, dw, dh)
        } catch (e: Exception) { null }
        if (bmp == null) return last[v.id]?.second
        last[v.id] = q to bmp
        return bmp
    }

    @Synchronized
    fun release() {
        rets.values.forEach { try { it.release() } catch (_: Exception) {} }
        rets.clear(); last.clear()
    }
}
