package de.cutlab.app

import android.content.Context
import android.graphics.*
import android.net.Uri

/** Zeichnet Text, Bilder und Untertitel. Wird fuer Vorschau UND Export benutzt -> sieht identisch aus. */
class OverlayPainter(private val images: (String) -> Bitmap?) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE
        color = Color.BLACK; strokeJoin = Paint.Join.ROUND
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Rahmen der zuletzt gezeichneten Elemente (fuer Auswahlrahmen in der Vorschau). */
    val bounds = HashMap<Long, RectF>()

    private fun active(t: Long, s: Long, d: Long) = t >= s && t < s + d
    private fun subWords(s: Subtitle) = s.words.ifEmpty { evenWords(s.text, s.startMs, s.endMs) }
    private fun activeIdx(w: List<Word>, t: Long): Int = w.indexOfLast { t >= it.startMs }.coerceAtLeast(0)

    fun draw(c: Canvas, w: Int, h: Int, t: Long, p: Project) {
        bounds.clear()
        for (im in p.images) if (active(t, im.startMs, im.durMs)) drawImage(c, w, h, im)
        for (tx in p.texts) if (active(t, tx.startMs, tx.durMs)) {
            val ws = tx.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            block(c, w, h, tx.id, ws, -1, tx.x * w, tx.y * h, tx.size, tx.color, tx.color, 1f)
        }
        val sub = p.subs.firstOrNull { t >= it.startMs && t < it.endMs } ?: return
        val words = subWords(sub)
        if (words.isEmpty()) return
        val st = p.subStyle
        val idx = activeIdx(words, t)
        when (st.kind) {
            SubKind.CLASSIC -> block(c, w, h, sub.id, words.map { it.text }, -1, w / 2f, st.y * h, st.size, st.color, st.highlight, 1f)
            SubKind.KARAOKE -> block(c, w, h, sub.id, words.map { it.text }, idx, w / 2f, st.y * h, st.size, st.color, st.highlight, 1f)
            SubKind.POP -> block(c, w, h, sub.id, listOf(words[idx].text), 0, w / 2f, st.y * h,
                st.size * 1.35f, st.highlight, st.highlight, popScale(t - words[idx].startMs))
        }
    }

    /** Aendert sich nur, wenn sich das Bild aendert -> Export muss nicht jedes Frame neu zeichnen. */
    fun key(p: Project, t: Long): String {
        val sb = StringBuilder()
        for (x in p.texts) if (active(t, x.startMs, x.durMs)) sb.append('t').append(x.id)
        for (x in p.images) if (active(t, x.startMs, x.durMs)) sb.append('i').append(x.id)
        val s = p.subs.firstOrNull { t >= it.startMs && t < it.endMs }
        if (s != null) {
            val words = subWords(s)
            if (words.isNotEmpty()) {
                val idx = activeIdx(words, t)
                sb.append('s').append(s.id).append(':').append(idx)
                if (p.subStyle.kind == SubKind.POP) sb.append(':').append((t - words[idx].startMs).coerceIn(0, 160) / 40)
            }
        }
        return sb.toString()
    }

    private fun popScale(ageMs: Long): Float {
        val k = (ageMs / 150f).coerceIn(0f, 1f)
        val c1 = 1.70158f; val c3 = c1 + 1
        val e = 1 + c3 * (k - 1) * (k - 1) * (k - 1) + c1 * (k - 1) * (k - 1)
        return 0.5f + 0.5f * e
    }

    private fun drawImage(c: Canvas, w: Int, h: Int, im: ImageItem) {
        val bmp = images(im.uri) ?: return
        val dw = im.scale * w
        val dh = dw * bmp.height / bmp.width
        val r = RectF(im.x * w - dw / 2, im.y * h - dh / 2, im.x * w + dw / 2, im.y * h + dh / 2)
        c.drawBitmap(bmp, null, r, bmpPaint)
        bounds[im.id] = r
    }

    private fun block(
        c: Canvas, w: Int, h: Int, id: Long, words: List<String>, active: Int,
        cx: Float, cy: Float, size: Float, base: Int, hi: Int, scale: Float
    ) {
        if (words.isEmpty()) return
        val px = size * h
        fill.textSize = px; stroke.textSize = px; stroke.strokeWidth = px * 0.2f
        val space = fill.measureText(" ")
        val maxW = w * 0.9f
        val widths = words.map { fill.measureText(it) }
        val lines = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var curW = 0f
        for (i in words.indices) {
            val add = if (cur.isEmpty()) widths[i] else space + widths[i]
            if (cur.isNotEmpty() && curW + add > maxW) { lines.add(cur); cur = ArrayList(); curW = 0f }
            curW += if (cur.isEmpty()) widths[i] else space + widths[i]
            cur.add(i)
        }
        lines.add(cur)
        val lh = px * 1.25f
        val top = cy - lines.size * lh / 2f
        var maxLine = 0f
        c.save()
        if (scale != 1f) c.scale(scale, scale, cx, cy)
        lines.forEachIndexed { li, idxs ->
            val lw = idxs.sumOf { widths[it].toDouble() }.toFloat() + space * (idxs.size - 1)
            maxLine = maxOf(maxLine, lw)
            var x = cx - lw / 2f
            val y = top + li * lh + px * 0.95f
            for (i in idxs) {
                c.drawText(words[i], x, y, stroke)
                fill.color = if (i == active) hi else base
                c.drawText(words[i], x, y, fill)
                x += widths[i] + space
            }
        }
        c.restore()
        bounds[id] = RectF(cx - maxLine / 2f - px * 0.2f, top, cx + maxLine / 2f + px * 0.2f, top + lines.size * lh)
    }
}

class ImageCache(private val ctx: Context) {
    private val map = HashMap<String, Bitmap>()

    @Synchronized
    fun get(uri: String): Bitmap? {
        map[uri]?.let { return it }
        val bmp = try {
            val src = ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))
            ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = maxOf(info.size.width, info.size.height)
                if (m > 1600) dec.setTargetSampleSize(m / 1600 + 1)
            }
        } catch (e: Exception) { null }
        if (bmp != null) map[uri] = bmp
        return bmp
    }
}
