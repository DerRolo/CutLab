package de.cutlab.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors

/** Laedt Vorschaubilder (1 Bild pro Sekunde Quellvideo) im Hintergrund und cached sie. */
class ThumbCache(private val ctx: Context) {
    private val cache = LruCache<String, ImageBitmap>(300)
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val exec = Executors.newSingleThreadExecutor()
    private val retrievers = HashMap<String, MediaMetadataRetriever>()   // nur im exec-Thread benutzt

    var version by mutableIntStateOf(0); private set

    /** Liefert das Bild oder null (dann wird es geladen und der Aufrufer neu gezeichnet). */
    fun get(uri: String, sec: Int): ImageBitmap? {
        if (version < 0) return null           // nur zum Abonnieren des State
        val k = "$uri#$sec"
        cache.get(k)?.let { return it }
        synchronized(pending) {
            if (k in failed || !pending.add(k)) return null
        }
        exec.execute {
            var ok = false
            try {
                val r = retrievers.getOrPut(uri) { MediaMetadataRetriever().apply { setDataSource(ctx, Uri.parse(uri)) } }
                val f = r.getFrameAtTime(sec * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (f != null) {
                    val h = 120
                    val w = (f.width * h / f.height).coerceAtLeast(1)
                    cache.put(k, Bitmap.createScaledBitmap(f, w, h, true).asImageBitmap())
                    ok = true
                }
            } catch (_: Exception) {
            } finally {
                synchronized(pending) { pending.remove(k); if (!ok) failed.add(k) }
                if (ok) version++
            }
        }
        return null
    }

    fun release() {
        exec.execute { retrievers.values.forEach { try { it.release() } catch (_: Exception) {} }; retrievers.clear() }
        exec.shutdown()
    }
}
