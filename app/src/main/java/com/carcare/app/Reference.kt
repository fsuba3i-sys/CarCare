package com.carcare.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The maintenance reference library (brands → models → generations → engines → schedule).
 * Shipped inside the app (assets/reference.json) and refreshed from GitHub when a newer
 * version is published, so new cars can be added without reinstalling the app.
 */
object Reference {

    const val UPDATE_URL = "https://raw.githubusercontent.com/fsuba3i-sys/CarCare/main/reference/reference.json"
    private const val CACHE = "reference.json"
    private const val PREFS = "reference"
    private const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

    data class Name(val en: String, val ar: String) {
        fun get(ctx: Context) = if (ctx.isArabic()) ar else en
    }

    data class Source(val title: String, val url: String)
    data class Engine(val id: String, val name: Name, val schedule: String)
    data class Gen(val id: String, val name: Name, val from: Int, val to: Int?, val engines: List<Engine>)
    data class Model(val id: String, val name: Name, val gens: List<Gen>)
    data class Brand(val id: String, val name: Name, val models: List<Model>)
    data class Schedule(val key: String, val name: Name, val note: Name?, val items: List<SchedItem>)

    data class Lib(
        val version: Int,
        val updated: String,
        val items: Map<String, Name>,
        val itemOrder: List<String>,
        val sources: Map<String, Source>,
        val schedules: Map<String, Schedule>,
        val brands: List<Brand>
    )

    @Volatile private var lib: Lib? = null

    fun get(ctx: Context): Lib {
        lib?.let { return it }
        synchronized(this) {
            lib?.let { return it }
            val asset = parse(ctx.assets.open("reference.json").bufferedReader().readText())
            val cached = try {
                File(ctx.filesDir, CACHE).takeIf { it.exists() }?.let { parse(it.readText()) }
            } catch (_: Exception) { null }
            val best = if (cached != null && cached.version > asset.version) cached else asset
            lib = best
            return best
        }
    }

    fun itemName(ctx: Context, id: String): String = get(ctx).items[id]?.get(ctx) ?: id

    fun schedule(ctx: Context, key: String): Schedule? = get(ctx).schedules[key]

    // ---------- Parsing ----------

    private fun name(o: JSONObject): Name = Name(o.optString("en"), o.optString("ar", o.optString("en")))

    fun parse(text: String): Lib {
        val root = JSONObject(text)

        val items = linkedMapOf<String, Name>()
        val itemArr = root.getJSONArray("items")
        for (i in 0 until itemArr.length()) {
            val o = itemArr.getJSONObject(i)
            items[o.getString("id")] = name(o)
        }

        val sources = mutableMapOf<String, Source>()
        root.optJSONObject("sources")?.let { s ->
            s.keys().forEach { k ->
                val o = s.getJSONObject(k)
                sources[k] = Source(o.optString("title"), o.optString("url"))
            }
        }

        val schedules = mutableMapOf<String, Schedule>()
        val sObj = root.getJSONObject("schedules")
        sObj.keys().forEach { k ->
            val o = sObj.getJSONObject(k)
            val arr = o.getJSONArray("items")
            val list = (0 until arr.length()).map { SchedItem.fromJson(arr.getJSONObject(it)) }
            val note = o.optJSONObject("note")?.let { name(it) }
            schedules[k] = Schedule(k, name(o), note, list)
        }

        val brands = mutableListOf<Brand>()
        val bArr = root.getJSONArray("brands")
        for (i in 0 until bArr.length()) {
            val b = bArr.getJSONObject(i)
            val defaultSchedule = b.optString("schedule", "generic_jp")
            val models = mutableListOf<Model>()
            val mArr = b.getJSONArray("models")
            for (j in 0 until mArr.length()) {
                val m = mArr.getJSONObject(j)
                val mSchedule = m.optString("schedule", defaultSchedule)
                val gens = mutableListOf<Gen>()
                val gArr = m.optJSONArray("gens") ?: JSONArray()
                for (g in 0 until gArr.length()) {
                    val go = gArr.getJSONObject(g)
                    val engines = mutableListOf<Engine>()
                    val eArr = go.optJSONArray("engines") ?: JSONArray()
                    for (e in 0 until eArr.length()) {
                        val eo = eArr.getJSONObject(e)
                        engines += Engine(eo.getString("id"), name(eo), eo.optString("schedule", mSchedule))
                    }
                    if (engines.isEmpty()) engines += Engine("any", Name("Any engine", "أي مكينة"), go.optString("schedule", mSchedule))
                    gens += Gen(
                        go.getString("id"), name(go), go.optInt("from", 2006),
                        if (go.isNull("to") || !go.has("to")) null else go.getInt("to"), engines
                    )
                }
                if (gens.isEmpty()) {
                    gens += Gen(
                        "all", Name("All years (2006+)", "كل السنوات (2006+)"), 2006, null,
                        listOf(Engine("any", Name("Any engine", "أي مكينة"), mSchedule))
                    )
                }
                models += Model(m.getString("id"), name(m), gens)
            }
            brands += Brand(b.getString("id"), name(b), models)
        }

        return Lib(
            version = root.getInt("version"),
            updated = root.optString("updated"),
            items = items,
            itemOrder = items.keys.toList(),
            sources = sources,
            schedules = schedules,
            brands = brands
        )
    }

    // ---------- Online update ----------

    /** Checks GitHub for a newer library. Safe to call often; it throttles itself. Runs on a background thread. */
    fun checkForUpdate(ctx: Context, force: Boolean = false, done: ((Result) -> Unit)? = null) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0)
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            done?.invoke(Result.SKIPPED)
            return
        }
        Thread {
            val result = try {
                val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.useCaches = false
                if (conn.responseCode != 200) {
                    Result.FAILED
                } else {
                    val text = conn.inputStream.bufferedReader().readText()
                    val remote = parse(text) // validates before saving
                    prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                    if (remote.version > get(app).version) {
                        File(app.filesDir, CACHE).writeText(text)
                        lib = remote
                        Result.UPDATED
                    } else Result.UP_TO_DATE
                }
            } catch (_: Exception) {
                Result.FAILED
            }
            done?.invoke(result)
        }.start()
    }

    enum class Result { UPDATED, UP_TO_DATE, FAILED, SKIPPED }
}
