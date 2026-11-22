package de.cutlab.app

import android.content.Context
import android.graphics.*
import android.net.Uri

/** Zeichnet Text, Bilder und Untertitel. Wird fuer Vorschau UND Export benutzt -> sieht identisch aus. */
class OverlayPainter(
    private val images: (String) -> Bitmap?,
    /** Nur beim Export: liefert das Bild eines Overlay-Videos (in der Vorschau spielen echte Player). */
    private val videoFrames: ((VideoItem, Long) -> Bitmap?)? = null
) {
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
        for (v in p.videos.sortedBy { it.track }) if (active(t, v.startMs, v.durMs)) drawVideo(c, w, h, v, t)
        for (im in p.images.sortedBy { it.lane }) if (active(t, im.startMs, im.durMs)) drawImage(c, w, h, im)
        for (tx in p.texts.sortedBy { it.lane }) if (active(t, tx.startMs, tx.durMs)) {
            val ws = tx.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            block(c, w, h, tx.id, ws, -1, tx.x * w, tx.y * h, tx.size, tx.color, tx.color, tx.outline, 1f)
        }
        val sub = p.subs.firstOrNull { t >= it.startMs && t < it.endMs } ?: return
        val words = subWords(sub)
        if (words.isEmpty()) return
        val st = p.subStyle
        val idx = activeIdx(words, t)
        when (st.kind) {
            SubKind.CLASSIC -> block(c, w, h, sub.id, words.map { it.text }, -1, st.x * w, st.y * h, st.size, st.color, st.highlight, st.outline, 1f)
            SubKind.KARAOKE -> block(c, w, h, sub.id, words.map { it.text }, idx, st.x * w, st.y * h, st.size, st.color, st.highlight, st.outline, 1f)
            SubKind.POP -> block(c, w, h, sub.id, listOf(words[idx].text), 0, st.x * w, st.y * h,
                st.size * 1.35f, st.highlight, st.highlight, st.outline, popScale(t - words[idx].startMs))
        }
    }

    /** Aendert sich nur, wenn sich das Bild aendert -> Export muss nicht jedes Frame neu zeichnen. */
    fun key(p: Project, t: Long): String {
        val sb = StringBuilder()
        for (x in p.texts) if (active(t, x.startMs, x.durMs)) sb.append('t').append(x.id)
        for (x in p.images) if (active(t, x.startMs, x.durMs)) sb.append('i').append(x.id)
        for (v in p.videos) if (active(t, v.startMs, v.durMs)) {
            sb.append('v').append(v.id).append(':').append((t - v.startMs) * 24 / 1000)
            if (v.fadeMs > 0) sb.append(':').append((videoAlpha(v, t) * 20).toInt())
        }
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

    fun videoAlpha(v: VideoItem, t: Long): Float {
        if (v.fadeMs <= 0) return 1f
        val f = v.fadeMs.toFloat()
        return minOf(1f, (t - v.startMs) / f, (v.startMs + v.durMs - t) / f).coerceIn(0f, 1f)
    }

    private fun drawVideo(c: Canvas, w: Int, h: Int, v: VideoItem, t: Long) {
        val dw = v.scale * w
        val dh = dw / v.aspect
        val r = RectF(v.x * w - dw / 2, v.y * h - dh / 2, v.x * w + dw / 2, v.y * h + dh / 2)
        bounds[v.id] = r                                   // Auswahlrahmen auch in der Vorschau
        val bmp = videoFrames?.invoke(v, v.inMs + (t - v.startMs)) ?: return
        bmpPaint.alpha = (videoAlpha(v, t) * 255).toInt()
        c.drawBitmap(bmp, null, r, bmpPaint)
        bmpPaint.alpha = 255
    }

    private fun drawImage(c: Canvas, w: Int, h: Int, im: ImageItem) {
        val bmp = images(im.uri) ?: return
        val dw = im.scale * w
        val dh = dw * bmp.height / bmp.width
        val r = RectF(im.x * w - dw / 2, im.y * h - dh / 2, im.x * w + dw / 2, im.y * h + dh / 2)
        c.drawBitmap(bmp, null, r, bmpPaint)
        bounds[im.id] = r
    }

    private class Piece(val text: String, val word: Int, val glue: Boolean)

    /** Trennt ein zu langes Wort mit Bindestrich (bevorzugt nach einem Vokal). */
    private fun hyphenate(word: String, maxW: Float): List<String> {
        if (fill.measureText(word) <= maxW) return listOf(word)
        val vowels = "aeiouyäöüAEIOUÄÖÜ"
        val hy = fill.measureText("-")
        val out = ArrayList<String>()
        var rest = word
        while (rest.isNotEmpty()) {
            if (fill.measureText(rest) <= maxW) { out.add(rest); break }
            var n = rest.length
            while (n > 1 && fill.measureText(rest.substring(0, n)) + hy > maxW) n--
            var k = n
            while (k > 2 && k > n - 3 && rest[k - 1] !in vowels) k--
            val cut = if (rest[k - 1] in vowels) k else n
            out.add(rest.substring(0, cut) + "-")
            rest = rest.substring(cut)
        }
        return out
    }

    private fun block(
        c: Canvas, w: Int, h: Int, id: Long, words: List<String>, active: Int,
        cx: Float, cy: Float, size: Float, base: Int, hi: Int, outline: Int, scale: Float
    ) {
        if (words.isEmpty()) return
        val px = size * h
        fill.textSize = px; stroke.textSize = px; stroke.strokeWidth = px * 0.2f; stroke.color = outline
        val space = fill.measureText(" ")
        val maxW = w * 0.88f

        val pieces = ArrayList<Piece>()
        words.forEachIndexed { wi, wd -> hyphenate(wd, maxW).forEachIndexed { k, part -> pieces.add(Piece(part, wi, k > 0)) } }
        val widths = pieces.map { fill.measureText(it.text) }

        val lines = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var curW = 0f
        for (i in pieces.indices) {
            val add = if (cur.isEmpty() || pieces[i].glue) widths[i] else space + widths[i]
            if (cur.isNotEmpty() && curW + add > maxW) { lines.add(cur); cur = ArrayList(); curW = 0f }
            curW += if (cur.isEmpty() || pieces[i].glue) widths[i] else space + widths[i]
            cur.add(i)
        }
        lines.add(cur)

        fun lineW(idxs: List<Int>): Float {
            var t = 0f
            idxs.forEachIndexed { k, i -> t += widths[i]; if (k > 0 && !pieces[i].glue) t += space }
            return t
        }
        val lh = px * 1.25f
        val total = lines.size * lh
        var maxLine = 0f
        lines.forEach { maxLine = maxOf(maxLine, lineW(it)) }

        // Block immer komplett im Bild halten
        val mx = w * 0.03f
        val cx2 = if (maxLine + 2 * mx < w) cx.coerceIn(maxLine / 2f + mx, w - maxLine / 2f - mx) else w / 2f
        val cy2 = if (total + 0.04f * h < h) cy.coerceIn(total / 2f + 0.02f * h, h - total / 2f - 0.02f * h) else h / 2f
        val top = cy2 - total / 2f

        c.save()
        if (scale != 1f) c.scale(scale, scale, cx2, cy2)
        lines.forEachIndexed { li, idxs ->
            var x = cx2 - lineW(idxs) / 2f
            val y = top + li * lh + px * 0.95f
            idxs.forEachIndexed { k, i ->
                if (k > 0 && !pieces[i].glue) x += space
                c.drawText(pieces[i].text, x, y, stroke)
                fill.color = if (pieces[i].word == active) hi else base
                c.drawText(pieces[i].text, x, y, fill)
                x += widths[i]
            }
        }
        c.restore()
        bounds[id] = RectF(cx2 - maxLine / 2f - px * 0.2f, top, cx2 + maxLine / 2f + px * 0.2f, top + total)
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
