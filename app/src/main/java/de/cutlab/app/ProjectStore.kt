package de.cutlab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ProjectInfo(
    val id: String, val name: String, val modifiedMs: Long, val durMs: Long,
    val thumbUri: String?, val thumbSec: Int
)

/** Jedes Projekt liegt in filesDir/projects/<id>/ (project.json + media/). */
object ProjectStore {
    private fun root(ctx: Context) = File(ctx.filesDir, "projects").apply { mkdirs() }
    private fun dir(ctx: Context, id: String) = File(root(ctx), id).apply { mkdirs() }
    fun mediaDir(ctx: Context, id: String) = File(dir(ctx, id), "media").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx, id), "project.json")
    private fun currentFile(ctx: Context) = File(ctx.filesDir, "current.txt")

    fun newId(): String = java.util.UUID.randomUUID().toString()

    fun lastId(ctx: Context): String? =
        currentFile(ctx).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() && File(root(ctx), it).exists() }

    fun setLast(ctx: Context, id: String) { currentFile(ctx).writeText(id) }

    fun save(ctx: Context, id: String, p: Project) {
        val tmp = File(dir(ctx, id), "project.json.tmp")
        tmp.writeText(toJson(p).toString())
        tmp.renameTo(file(ctx, id))
    }

    fun load(ctx: Context, id: String): Project? = try {
        val f = file(ctx, id)
        if (f.exists()) fromJson(JSONObject(f.readText())) else null
    } catch (e: Exception) { null }

    fun delete(ctx: Context, id: String) { File(root(ctx), id).deleteRecursively() }

    fun list(ctx: Context): List<ProjectInfo> =
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            val f = File(d, "project.json")
            if (!f.exists()) null else try {
                val p = fromJson(JSONObject(f.readText()))
                val c = p.clips.firstOrNull()
                ProjectInfo(d.name, p.name, f.lastModified(), p.totalMs, c?.uri, ((c?.inMs ?: 0L) / 1000).toInt())
            } catch (e: Exception) { null }
        }.sortedByDescending { it.modifiedMs }

    private fun <T> arr(items: List<T>, f: (T) -> JSONObject) = JSONArray().also { a -> items.forEach { a.put(f(it)) } }

    private fun toJson(p: Project) = JSONObject().apply {
        put("name", p.name)
        put("aspect", p.aspect.name)
        put("subStyle", JSONObject().apply {
            put("kind", p.subStyle.kind.name); put("size", p.subStyle.size.toDouble()); put("y", p.subStyle.y.toDouble())
            put("color", p.subStyle.color); put("highlight", p.subStyle.highlight); put("x", p.subStyle.x.toDouble()); put("outline", p.subStyle.outline)
        })
        put("clips", arr(p.clips) { JSONObject().put("id", it.id).put("uri", it.uri).put("src", it.srcDurMs).put("in", it.inMs).put("out", it.outMs) })
        put("texts", arr(p.texts) {
            JSONObject().put("id", it.id).put("text", it.text).put("s", it.startMs).put("d", it.durMs)
                .put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("size", it.size.toDouble()).put("color", it.color).put("outline", it.outline)
        })
        put("images", arr(p.images) {
            JSONObject().put("id", it.id).put("uri", it.uri).put("s", it.startMs).put("d", it.durMs)
                .put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("scale", it.scale.toDouble())
        })
        put("videos", arr(p.videos) {
            JSONObject().put("id", it.id).put("uri", it.uri).put("src", it.srcDurMs).put("aspect", it.aspect.toDouble())
                .put("s", it.startMs).put("in", it.inMs).put("d", it.durMs).put("x", it.x.toDouble()).put("y", it.y.toDouble())
                .put("scale", it.scale.toDouble()).put("track", it.track).put("fade", it.fadeMs)
                .put("muted", it.muted).put("hasAudio", it.hasAudio)
        })
        put("audios", arr(p.audios) {
            JSONObject().put("id", it.id).put("uri", it.uri).put("name", it.name).put("src", it.srcDurMs)
                .put("s", it.startMs).put("in", it.inMs).put("d", it.durMs)
        })
        put("subs", arr(p.subs) { s ->
            JSONObject().put("id", s.id).put("s", s.startMs).put("e", s.endMs).put("text", s.text)
                .put("words", arr(s.words) { JSONObject().put("t", it.text).put("s", it.startMs).put("e", it.endMs) })
        })
    }

    private fun <T> list(a: JSONArray?, f: (JSONObject) -> T): List<T> =
        if (a == null) emptyList() else (0 until a.length()).map { f(a.getJSONObject(it)) }

    private fun fromJson(j: JSONObject): Project {
        val st = j.optJSONObject("subStyle")
        return Project(
            clips = list(j.optJSONArray("clips")) { Clip(it.getLong("id"), it.getString("uri"), it.getLong("src"), it.getLong("in"), it.getLong("out")) },
            texts = list(j.optJSONArray("texts")) {
                TextItem(it.getLong("id"), it.getString("text"), it.getLong("s"), it.getLong("d"),
                    it.getDouble("x").toFloat(), it.getDouble("y").toFloat(), it.getDouble("size").toFloat(), it.getInt("color"), it.optInt("outline", 0xFF000000.toInt()))
            },
            images = list(j.optJSONArray("images")) {
                ImageItem(it.getLong("id"), it.getString("uri"), it.getLong("s"), it.getLong("d"),
                    it.getDouble("x").toFloat(), it.getDouble("y").toFloat(), it.getDouble("scale").toFloat())
            },
            videos = list(j.optJSONArray("videos")) {
                VideoItem(it.getLong("id"), it.getString("uri"), it.getLong("src"), it.getDouble("aspect").toFloat(),
                    it.getLong("s"), it.getLong("in"), it.getLong("d"), it.getDouble("x").toFloat(), it.getDouble("y").toFloat(),
                    it.getDouble("scale").toFloat(), it.getInt("track"), it.getLong("fade"), it.getBoolean("muted"), it.optBoolean("hasAudio", true))
            },
            audios = list(j.optJSONArray("audios")) {
                AudioItem(it.getLong("id"), it.getString("uri"), it.getString("name"), it.getLong("src"), it.getLong("s"), it.getLong("in"), it.getLong("d"))
            },
            subs = list(j.optJSONArray("subs")) { s ->
                Subtitle(s.getLong("id"), s.getLong("s"), s.getLong("e"), s.getString("text"),
                    list(s.optJSONArray("words")) { Word(it.getString("t"), it.getLong("s"), it.getLong("e")) })
            },
            subStyle = if (st == null) SubStyle() else SubStyle(
                SubKind.valueOf(st.getString("kind")), st.getDouble("size").toFloat(), st.getDouble("y").toFloat(),
                st.getInt("color"), st.getInt("highlight"), st.optDouble("x", 0.5).toFloat(), st.optInt("outline", 0xFF000000.toInt())
            ),
            aspect = AspectPreset.valueOf(j.optString("aspect", AspectPreset.PORTRAIT.name)),
            name = j.optString("name", "Projekt")
        )
    }
}
