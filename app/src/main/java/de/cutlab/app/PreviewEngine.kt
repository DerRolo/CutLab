package de.cutlab.app

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/** Spielt die Hauptspur ab (ExoPlayer-Playlist) und synchronisiert Musikspuren dazu. */
class PreviewEngine(private val ctx: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    private val audio = HashMap<Long, ExoPlayer>()
    private val vids = HashMap<Long, ExoPlayer>()          // Player der Overlay-Videos
    private var sorted: List<Clip> = emptyList()
    private var sig: List<List<Any>> = emptyList()
    private var lastPos = -1L
    private var lastIdx = -2

    /** Spielliste der Hauptspur (nach Startzeit sortiert). Luecken kennt der Player nicht, die steuert sync(). */
    fun setClips(clips: List<Clip>, posMs: Long) {
        val s = clips.sortedBy { it.startMs }
        val key = s.map { listOf<Any>(it.uri, it.inMs, it.outMs, it.startMs) }
        sorted = s
        lastPos = -1L
        if (key == sig) return
        sig = key
        if (s.isEmpty()) { player.clearMediaItems(); return }
        player.setMediaItems(s.map {
            MediaItem.Builder().setUri(it.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(it.inMs).setEndPositionMs(it.outMs).build()
            ).build()
        })
        player.prepare()
    }

    /** Stellt den Player passend zur Timeline-Position ein; in Luecken steht er still (die Vorschau zeigt dort Schwarz). */
    fun sync(pos: Long, playing: Boolean) {
        val i = sorted.indexOfFirst { pos >= it.startMs && pos < it.startMs + it.durMs }
        if (i < 0) {
            if (player.playWhenReady) player.pause()
            lastPos = pos; lastIdx = -1
            return
        }
        if (!playing && !player.playWhenReady && pos == lastPos && i == lastIdx) return
        lastPos = pos; lastIdx = i
        val c = sorted[i]
        val vol = minOf(1f, dbToLinear(c.gainDb))
        if (player.volume != vol) player.volume = vol
        val want = pos - c.startMs
        if (player.currentMediaItemIndex != i || abs(player.currentPosition - want) > (if (playing) 400L else 40L)) player.seekTo(i, want)
        if (playing) { if (!player.playWhenReady) player.play() } else if (player.playWhenReady) player.pause()
    }

    fun syncAudio(items: List<AudioItem>, playing: Boolean, pos: Long) {
        val ids = items.map { it.id }.toSet()
        audio.keys.filter { it !in ids }.forEach { audio.remove(it)?.release() }
        for (a in items) {
            val ap = audio.getOrPut(a.id) {
                ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(a.uri)); prepare() }
            }
            ap.volume = minOf(1f, dbToLinear(a.gainDb))
            val inside = pos >= a.startMs && pos < a.startMs + a.durMs
            if (playing && inside) {
                val want = a.inMs + (pos - a.startMs)
                if (!ap.isPlaying) { ap.seekTo(want); ap.play() }
                else if (abs(ap.currentPosition - want) > 400) ap.seekTo(want)
            } else if (ap.isPlaying) ap.pause()
        }
    }

    /** Player fuer ein Overlay-Video (wird bei Bedarf angelegt). */
    fun videoPlayer(v: VideoItem): ExoPlayer = vids.getOrPut(v.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(v.uri)); prepare()
            volume = if (v.muted) 0f else minOf(1f, dbToLinear(v.gainDb))
        }
    }

    /** Haelt Overlay-Videos synchron zur Timeline; Player weit ausserhalb des Zeitfensters werden freigegeben. */
    fun syncVideos(items: List<VideoItem>, playing: Boolean, pos: Long) {
        val live = items.filter { pos >= it.startMs - 600 && pos < it.startMs + it.durMs + 300 }.map { it.id }.toSet()
        vids.keys.filter { it !in live }.forEach { vids.remove(it)?.release() }
        for (v in items) {
            if (v.id !in live) continue
            val pl = videoPlayer(v)
            pl.volume = if (v.muted) 0f else minOf(1f, dbToLinear(v.gainDb))
            val inside = pos >= v.startMs && pos < v.startMs + v.durMs
            val want = v.inMs + (pos - v.startMs).coerceAtLeast(0L)
            if (playing && inside) {
                if (!pl.isPlaying) { pl.seekTo(want); pl.play() }
                else if (abs(pl.currentPosition - want) > 400) pl.seekTo(want)
            } else {
                if (pl.isPlaying) pl.pause()
                if (abs(pl.currentPosition - want) > 60) pl.seekTo(want)
            }
        }
    }

    fun release() {
        player.release()
        vids.values.forEach { it.release() }
        vids.clear()
        audio.values.forEach { it.release() }
        audio.clear()
    }
}
