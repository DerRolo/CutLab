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
    private var sig: List<Triple<String, Long, Long>> = emptyList()

    fun setClips(clips: List<Clip>, posMs: Long) {
        val s = clips.map { Triple(it.uri, it.inMs, it.outMs) }
        if (s == sig) return
        sig = s
        if (clips.isEmpty()) { player.clearMediaItems(); return }
        player.setMediaItems(clips.map {
            MediaItem.Builder().setUri(it.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(it.inMs).setEndPositionMs(it.outMs).build()
            ).build()
        })
        player.prepare()
        seek(clips, posMs)
    }

    fun seek(clips: List<Clip>, ms: Long) {
        var acc = 0L
        for ((i, c) in clips.withIndex()) {
            if (ms < acc + c.durMs || i == clips.lastIndex) {
                if (i < player.mediaItemCount) player.seekTo(i, (ms - acc).coerceIn(0L, c.durMs))
                return
            }
            acc += c.durMs
        }
    }

    fun pos(clips: List<Clip>): Long {
        val i = player.currentMediaItemIndex
        var acc = 0L
        for (k in 0 until minOf(i, clips.size)) acc += clips[k].durMs
        return acc + player.currentPosition.coerceAtLeast(0L)
    }

    fun toggle(clips: List<Clip>, total: Long) {
        if (clips.isEmpty()) return
        if (player.isPlaying) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED || pos(clips) >= total - 50) seek(clips, 0)
            player.play()
        }
    }

    fun syncAudio(items: List<AudioItem>, playing: Boolean, pos: Long) {
        val ids = items.map { it.id }.toSet()
        audio.keys.filter { it !in ids }.forEach { audio.remove(it)?.release() }
        for (a in items) {
            val ap = audio.getOrPut(a.id) {
                ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(a.uri)); prepare() }
            }
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
            volume = if (v.muted) 0f else 1f
        }
    }

    /** Haelt Overlay-Videos synchron zur Timeline; Player weit ausserhalb des Zeitfensters werden freigegeben. */
    fun syncVideos(items: List<VideoItem>, playing: Boolean, pos: Long) {
        val live = items.filter { pos >= it.startMs - 600 && pos < it.startMs + it.durMs + 300 }.map { it.id }.toSet()
        vids.keys.filter { it !in live }.forEach { vids.remove(it)?.release() }
        for (v in items) {
            if (v.id !in live) continue
            val pl = videoPlayer(v)
            pl.volume = if (v.muted) 0f else 1f
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
