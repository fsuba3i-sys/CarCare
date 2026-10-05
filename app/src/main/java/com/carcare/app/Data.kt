package com.carcare.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val EXTRA_CAR_ID = "car_id"
const val EXTRA_ENTRY_ID = "entry_id"
const val EXTRA_ITEM_ID = "item_id"

/** One maintenance item in a car's own schedule (copied from the reference, then editable). */
data class SchedItem(
    val item: String,
    val km: Int,          // 0 = no distance limit
    val months: Int,      // 0 = no time limit
    val enabled: Boolean = true,
    val source: String = "",
    val confidence: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("item", item); put("km", km); put("months", months); put("enabled", enabled)
        put("src", source); put("conf", confidence)
    }

    companion object {
        fun fromJson(o: JSONObject) = SchedItem(
            item = o.getString("item"),
            km = o.optInt("km", 0),
            months = o.optInt("months", 0),
            enabled = o.optBoolean("enabled", true),
            source = o.optString("src", ""),
            confidence = o.optString("conf", "")
        )
    }
}

/** "Last done" before the user started logging in the app. */
data class Baseline(val km: Int, val date: Long)

data class Car(
    val id: String,
    val name: String,
    val year: Int,
    val brandId: String,
    val modelId: String,
    val genId: String,
    val engineId: String,
    val refLabel: String,       // human text such as "Toyota Land Cruiser · 300 Series · 3.5L V6"
    val scheduleKey: String,
    val odometer: Int,
    val odoDate: Long,          // epoch day of the last odometer update
    val schedule: List<SchedItem>,
    val baselines: Map<String, Baseline> = emptyMap()
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("year", year)
        put("brand", brandId); put("model", modelId); put("gen", genId); put("engine", engineId)
        put("refLabel", refLabel); put("scheduleKey", scheduleKey)
        put("odo", odometer); put("odoDate", odoDate)
        put("schedule", JSONArray().apply { schedule.forEach { put(it.toJson()) } })
        put("baselines", JSONObject().apply {
            baselines.forEach { (k, b) -> put(k, JSONObject().put("km", b.km).put("date", b.date)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Car {
            val sArr = o.optJSONArray("schedule") ?: JSONArray()
            val bObj = o.optJSONObject("baselines") ?: JSONObject()
            val baselines = mutableMapOf<String, Baseline>()
            bObj.keys().forEach { k ->
                val b = bObj.getJSONObject(k)
                baselines[k] = Baseline(b.getInt("km"), b.getLong("date"))
            }
            return Car(
                id = o.getString("id"),
                name = o.optString("name"),
                year = o.optInt("year", 0),
                brandId = o.optString("brand"),
                modelId = o.optString("model"),
                genId = o.optString("gen"),
                engineId = o.optString("engine"),
                refLabel = o.optString("refLabel"),
                scheduleKey = o.optString("scheduleKey"),
                odometer = o.optInt("odo", 0),
                odoDate = o.optLong("odoDate", Fmt.today()),
                schedule = (0 until sArr.length()).map { SchedItem.fromJson(sArr.getJSONObject(it)) },
                baselines = baselines
            )
        }
    }
}

/** One row in the log: maintenance and parts in a single list. */
data class Entry(
    val id: String,
    val carId: String,
    val date: Long,
    val km: Int,
    val desc: String,
    val cost: Double,
    val items: List<String>,   // schedule items this entry completes (e.g. engine_oil)
    val photo: String?         // file name inside files/photos
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("car", carId); put("date", date); put("km", km)
        put("desc", desc); put("cost", cost)
        put("items", JSONArray(items)); put("photo", photo ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): Entry {
            val arr = o.optJSONArray("items") ?: JSONArray()
            return Entry(
                id = o.getString("id"),
                carId = o.getString("car"),
                date = o.getLong("date"),
                km = o.optInt("km", 0),
                desc = o.optString("desc"),
                cost = o.optDouble("cost", 0.0),
                items = (0 until arr.length()).map { arr.getString(it) },
                photo = o.optString("photo", "").ifEmpty { null }
            )
        }
    }
}

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/** All user data in one JSON file (files/data.json). Photos live in files/photos/. */
object Store {
    private const val FILE = "data.json"
    private var cars = mutableListOf<Car>()
    private var entries = mutableListOf<Entry>()
    private var loaded = false

    fun photosDir(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }
    fun dataFile(ctx: Context): File = File(ctx.filesDir, FILE)

    @Synchronized
    fun load(ctx: Context, force: Boolean = false) {
        if (loaded && !force) return
        cars = mutableListOf(); entries = mutableListOf()
        val f = dataFile(ctx)
        if (f.exists()) {
            try {
                val o = JSONObject(f.readText())
                val ca = o.optJSONArray("cars") ?: JSONArray()
                for (i in 0 until ca.length()) cars += Car.fromJson(ca.getJSONObject(i))
                val ea = o.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until ea.length()) entries += Entry.fromJson(ea.getJSONObject(i))
            } catch (_: Exception) {
            }
        }
        loaded = true
    }

    @Synchronized
    private fun save(ctx: Context) {
        val o = JSONObject()
        o.put("format", 1)
        o.put("cars", JSONArray().apply { cars.forEach { put(it.toJson()) } })
        o.put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(dataFile(ctx))
    }

    fun cars(ctx: Context): List<Car> { load(ctx); return cars.toList() }
    fun car(ctx: Context, id: String): Car? { load(ctx); return cars.firstOrNull { it.id == id } }

    fun upsertCar(ctx: Context, car: Car) {
        load(ctx)
        val i = cars.indexOfFirst { it.id == car.id }
        if (i >= 0) cars[i] = car else cars += car
        save(ctx)
    }

    fun deleteCar(ctx: Context, id: String) {
        load(ctx)
        entries.filter { it.carId == id }.forEach { e -> e.photo?.let { File(photosDir(ctx), it).delete() } }
        entries.removeAll { it.carId == id }
        cars.removeAll { it.id == id }
        save(ctx)
    }

    fun entries(ctx: Context, carId: String): List<Entry> {
        load(ctx)
        return entries.filter { it.carId == carId }.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.km })
    }

    fun allEntries(ctx: Context): List<Entry> { load(ctx); return entries.toList() }

    fun entry(ctx: Context, id: String): Entry? { load(ctx); return entries.firstOrNull { it.id == id } }

    fun upsertEntry(ctx: Context, e: Entry) {
        load(ctx)
        val i = entries.indexOfFirst { it.id == e.id }
        if (i >= 0) {
            val old = entries[i]
            if (old.photo != null && old.photo != e.photo) File(photosDir(ctx), old.photo).delete()
            entries[i] = e
        } else entries += e
        // A newer reading in the log moves the car's odometer forward.
        val car = cars.firstOrNull { it.id == e.carId }
        if (car != null && e.km > car.odometer) {
            val ci = cars.indexOf(car)
            cars[ci] = car.copy(odometer = e.km, odoDate = maxOf(car.odoDate, e.date))
        }
        save(ctx)
    }

    fun deleteEntry(ctx: Context, id: String) {
        load(ctx)
        entries.firstOrNull { it.id == id }?.photo?.let { File(photosDir(ctx), it).delete() }
        entries.removeAll { it.id == id }
        save(ctx)
    }
}
