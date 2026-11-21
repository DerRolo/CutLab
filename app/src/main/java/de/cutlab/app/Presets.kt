package de.cutlab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Benannte Untertitel-Vorlagen (Stil, Groesse, Position, Farben) - gelten fuer alle Projekte. */
data class SubPreset(val name: String, val style: SubStyle)

object PresetStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "sub_presets.json")

    /** Liefert Vorlagen und den Namen der Standard-Vorlage fuer neue Projekte (oder null). */
    fun load(ctx: Context): Pair<List<SubPreset>, String?> = try {
        val f = file(ctx)
        if (!f.exists()) emptyList<SubPreset>() to null else {
            val j = JSONObject(f.readText())
            val a = j.optJSONArray("presets") ?: JSONArray()
            val list = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                SubPreset(
                    o.getString("name"),
                    SubStyle(
                        SubKind.valueOf(o.getString("kind")), o.getDouble("size").toFloat(), o.getDouble("y").toFloat(),
                        o.getInt("color"), o.getInt("highlight"), o.optDouble("x", 0.5).toFloat()
                    )
                )
            }
            list to j.optString("default", "").ifEmpty { null }
        }
    } catch (e: Exception) { emptyList<SubPreset>() to null }

    fun save(ctx: Context, list: List<SubPreset>, default: String?) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("name", it.name).put("kind", it.style.kind.name).put("size", it.style.size.toDouble())
                    .put("y", it.style.y.toDouble()).put("x", it.style.x.toDouble()).put("color", it.style.color).put("highlight", it.style.highlight)
            )
        }
        file(ctx).writeText(JSONObject().put("presets", a).put("default", default ?: "").toString())
    }
}
