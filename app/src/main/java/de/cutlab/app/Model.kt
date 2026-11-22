package de.cutlab.app

import android.graphics.Color

enum class Kind { CLIP, SUB, TEXT, IMAGE, AUDIO, VIDEO }
data class Selection(val kind: Kind, val id: Long)

/** Hauptspur: Videoclips hintereinander. */
data class Clip(val id: Long, val uri: String, val srcDurMs: Long, val inMs: Long, val outMs: Long) {
    val durMs: Long get() = outMs - inMs
}

data class TextItem(
    val id: Long, val text: String, val startMs: Long, val durMs: Long,
    val x: Float = 0.5f, val y: Float = 0.5f,
    val size: Float = 0.06f,          // Anteil der Bildhoehe
    val color: Int = Color.WHITE,
    val outline: Int = Color.BLACK,     // Konturfarbe (Alpha 0 = keine Kontur)
    val lane: Int = 0                   // Spur (Text 1, Text 2, ...)
)

data class ImageItem(
    val id: Long, val uri: String, val startMs: Long, val durMs: Long,
    val x: Float = 0.5f, val y: Float = 0.5f,
    val scale: Float = 0.4f,          // Breite als Anteil der Bildbreite
    val lane: Int = 0
)

/** Zusaetzliches Video auf einer Overlay-Spur (Bild-in-Bild, Ueberblenden). */
data class VideoItem(
    val id: Long, val uri: String, val srcDurMs: Long, val aspect: Float,
    val startMs: Long, val inMs: Long, val durMs: Long,
    val x: Float = 0.5f, val y: Float = 0.5f,
    val scale: Float = 0.5f,          // Breite als Anteil der Bildbreite
    val track: Int = 0,               // 0 = "Video 2", 1 = "Video 3" (hoehere Spur liegt oben)
    val fadeMs: Long = 0,             // Ein-/Ausblenden
    val muted: Boolean = false,
    val hasAudio: Boolean = true
)

data class AudioItem(
    val id: Long, val uri: String, val name: String, val srcDurMs: Long,
    val startMs: Long, val inMs: Long, val durMs: Long,
    val lane: Int = 0
)

data class Word(val text: String, val startMs: Long, val endMs: Long)
data class Subtitle(val id: Long, val startMs: Long, val endMs: Long, val text: String, val words: List<Word>)

enum class SubKind(val label: String) { CLASSIC("Klassisch"), KARAOKE("Karaoke"), POP("Pop-up") }

/** Gilt fuer ALLE Untertitel gleichzeitig. */
data class SubStyle(
    val kind: SubKind = SubKind.CLASSIC,
    val size: Float = 0.05f,
    val y: Float = 0.78f,
    val color: Int = Color.WHITE,
    val highlight: Int = 0xFFFFD400.toInt(),
    val x: Float = 0.5f,
    val outline: Int = Color.BLACK
)

enum class AspectPreset(val label: String, val w: Int, val h: Int) {
    PORTRAIT("9:16", 1080, 1920), LANDSCAPE("16:9", 1920, 1080), SQUARE("1:1", 1080, 1080);
    val ratio: Float get() = w.toFloat() / h
}

data class Project(
    val clips: List<Clip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val images: List<ImageItem> = emptyList(),
    val audios: List<AudioItem> = emptyList(),
    val videos: List<VideoItem> = emptyList(),
    val subs: List<Subtitle> = emptyList(),
    val subStyle: SubStyle = SubStyle(),
    val aspect: AspectPreset = AspectPreset.PORTRAIT,
    val name: String = "Neues Projekt"
) {
    val totalMs: Long get() = clips.sumOf { it.durMs }
}

fun evenWords(text: String, s: Long, e: Long): List<Word> {
    val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return emptyList()
    val step = (e - s).toDouble() / parts.size
    return parts.mapIndexed { i, p -> Word(p, s + (step * i).toLong(), s + (step * (i + 1)).toLong()) }
}

fun fmtTime(ms: Long): String = "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms % 1000 / 100)
