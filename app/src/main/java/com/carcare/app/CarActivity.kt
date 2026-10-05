package com.carcare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** One car: odometer, oil card, upcoming maintenance, and the full log with a total. */
class CarActivity : Activity() {

    private lateinit var carId: String
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        carId = intent.getStringExtra(EXTRA_CAR_ID) ?: run { finish(); return }
        root = vertical().apply { setPadding(dp(20), dp(24), dp(20), dp(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(root) })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CAR_ID)?.let { carId = it }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val car = Store.car(this, carId) ?: run { finish(); return }
        val entries = Store.entries(this, car.id)
        val due = Due.all(this, car)
        root.removeAllViews()

        // Header
        val header = horizontal()
        header.add(label(car.name, 28f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        header.add(label(getString(R.string.edit), 15f, C.ACCENT, bold = true).apply {
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener {
                startActivity(Intent(this@CarActivity, CarEditActivity::class.java).putExtra(EXTRA_CAR_ID, car.id))
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        root.add(header)
        val sub = listOfNotNull(car.year.takeIf { it > 0 }?.toString(), car.refLabel.ifEmpty { null }).joinToString(" · ")
        if (sub.isNotEmpty()) root.add(label(sub, 13f, C.MUTED), lp(top = dp(2)))

        // Odometer card
        val odo = card()
        val odoTop = horizontal()
        val odoTexts = vertical()
        odoTexts.add(label(getString(R.string.odometer), 13f, C.MUTED, bold = true))
        odoTexts.add(label(Fmt.km(car.odometer) + " " + getString(R.string.km), 30f, C.TEXT, bold = true))
        val days = Due.odoDaysOld(car)
        odoTexts.add(
            label(
                if (days == 0L) getString(R.string.updated_today) else getString(R.string.updated_days_ago, days.toString()),
                12f, if (days >= ODO_STALE_DAYS) C.WARN else C.MUTED
            )
        )
        odoTop.add(odoTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        odoTop.add(smallButton(getString(R.string.update)) { updateOdometer(car) }, LinearLayout.LayoutParams(WRAP, WRAP))
        odo.add(odoTop)
        root.add(odo, lp(top = dp(16)))

        // Oil card
        val oil = due.firstOrNull { it.sched.item == "engine_oil" }
        if (oil != null) {
            val c = card().apply { background = roundRect(C.SURFACE, dp(18).toFloat(), Due.color(oil.state), dp(1)) }
            val top = horizontal()
            top.add(label(getString(R.string.oil_card_title), 17f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            top.add(smallButton(getString(R.string.log_oil)) { openEntry(null, "engine_oil") }, LinearLayout.LayoutParams(WRAP, WRAP))
            c.add(top)
            if (oil.lastDate != null && oil.lastKm != null) {
                c.add(kv(getString(R.string.last_change), Fmt.date(oil.lastDate)), lp(top = dp(10)))
                c.add(kv(getString(R.string.odometer_then), Fmt.km(oil.lastKm) + " " + getString(R.string.km)), lp(top = dp(4)))
                c.add(kv(getString(R.string.driven_since), Fmt.km(maxOf(0, car.odometer - oil.lastKm)) + " " + getString(R.string.km)), lp(top = dp(4)))
                c.add(kv(getString(R.string.next_change), Due.target(this, oil)), lp(top = dp(4)))
            }
            val lastOil = entries.firstOrNull { "engine_oil" in it.items }
            if (lastOil != null && (lastOil.oilName.isNotEmpty() || lastOil.oilGrade.isNotEmpty())) {
                val used = listOf(lastOil.oilName, lastOil.oilGrade).filter { it.isNotEmpty() }.joinToString(" · ")
                c.add(kv(getString(R.string.oil_used), used), lp(top = dp(4)))
            }
            Reference.schedule(this, car.scheduleKey)?.oil?.let { spec ->
                val parts = mutableListOf<String>()
                if (spec.grade.isNotEmpty()) parts += spec.grade
                if (spec.spec.isNotEmpty()) parts += spec.spec
                if (spec.liters > 0) parts += getString(R.string.liters, Fmt.money(spec.liters))
                if (parts.isNotEmpty()) c.add(kv(getString(R.string.oil_recommended), parts.joinToString(" · ")), lp(top = dp(4)))
            }
            c.add(label(Due.describe(this, oil), 15f, Due.color(oil.state), bold = true), lp(top = dp(10)))
            root.add(c, lp(top = dp(12)))
        }

        // Upcoming maintenance
        root.add(sectionTitle(getString(R.string.upcoming)), lp(top = dp(24)))
        val list = card().apply { setPadding(dp(16), dp(6), dp(16), dp(6)) }
        val others = due.filter { it.sched.item != "engine_oil" }
        if (others.isEmpty()) list.add(label(getString(R.string.no_schedule), 14f, C.MUTED).apply { setPadding(0, dp(10), 0, dp(10)) })
        others.forEachIndexed { i, d ->
            val row = horizontal().apply {
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { itemOptions(car, d) }
            }
            val dot = android.view.View(this).apply { background = roundRect(Due.color(d.state), dp(5).toFloat()) }
            row.add(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
            val texts = vertical()
            texts.add(label(Reference.itemName(this, d.sched.item), 15f, C.TEXT, bold = true))
            texts.add(label(Due.describe(this, d), 13f, Due.color(d.state)))
            texts.add(label(Due.intervalText(this, d.sched), 12f, C.MUTED))
            row.add(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.add(label("›", 22f, C.MUTED), LinearLayout.LayoutParams(WRAP, WRAP))
            list.add(row)
            if (i < others.size - 1) list.add(android.view.View(this).apply { setBackgroundColor(C.SURFACE2) }, LinearLayout.LayoutParams(MATCH, 1))
        }
        root.add(list, lp(top = dp(8)))

        // Log
        val logHeader = horizontal()
        logHeader.add(sectionTitle(getString(R.string.log)), LinearLayout.LayoutParams(0, WRAP, 1f))
        root.add(logHeader, lp(top = dp(24)))
        root.add(button("+  " + getString(R.string.add_entry), C.ACCENT, C.ON_ACCENT) { openEntry(null, null) }, lp(top = dp(8)))

        val total = entries.sumOf { it.cost }
        val totalCard = horizontal().apply {
            background = roundRect(C.SURFACE2, dp(14).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        totalCard.add(label(getString(R.string.total_spent), 15f, C.MUTED, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        totalCard.add(label(Fmt.money(total) + " " + getString(R.string.sar), 18f, C.TEXT, bold = true), LinearLayout.LayoutParams(WRAP, WRAP))
        root.add(totalCard, lp(top = dp(10)))

        if (entries.isEmpty()) {
            root.add(label(getString(R.string.no_entries), 14f, C.MUTED).apply {
                gravity = Gravity.CENTER
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
        }
        for (e in entries) root.add(entryRow(e), lp(top = dp(8)))
    }

    private fun entryRow(e: Entry): LinearLayout {
        val row = horizontal().apply {
            background = roundRect(C.SURFACE, dp(16).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { openEntry(e.id, null) }
        }
        if (e.photo != null) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                Photos.thumbnail(this@CarActivity, e.photo, dp(56))?.let { setImageBitmap(it) }
                background = roundRect(C.SURFACE2, dp(10).toFloat())
                clipToOutline = true
            }
            row.add(img, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        }
        val texts = vertical()
        texts.add(label(e.desc.ifEmpty { getString(R.string.no_description) }, 15f, C.TEXT, bold = true))
        texts.add(label(Fmt.date(e.date) + "  ·  " + Fmt.km(e.km) + " " + getString(R.string.km), 12f, C.MUTED))
        if (e.items.isNotEmpty()) {
            texts.add(label(e.items.joinToString("، ") { Reference.itemName(this, it) }, 12f, C.ACCENT))
        }
        val oilText = listOf(e.oilName, e.oilGrade).filter { it.isNotEmpty() }.joinToString(" · ")
        if (oilText.isNotEmpty()) texts.add(label(oilText, 12f, C.MUTED))
        row.add(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.add(label(Fmt.money(e.cost), 16f, C.TEXT, bold = true).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(WRAP, WRAP))
        return row
    }

    private fun kv(k: String, v: String): LinearLayout {
        val r = horizontal()
        r.add(label(k, 14f, C.MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.add(label(v, 14f, C.TEXT, bold = true), LinearLayout.LayoutParams(WRAP, WRAP))
        return r
    }

    private fun smallButton(text: String, onClick: () -> Unit) = label(text, 14f, C.ON_ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        background = roundRect(C.ACCENT, dp(12).toFloat())
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun openEntry(entryId: String?, item: String?) {
        val i = Intent(this, EntryActivity::class.java).putExtra(EXTRA_CAR_ID, carId)
        entryId?.let { i.putExtra(EXTRA_ENTRY_ID, it) }
        item?.let { i.putExtra(EXTRA_ITEM_ID, it) }
        startActivity(i)
    }

    // ---------- Odometer ----------

    private fun updateOdometer(car: Car) {
        val f = field(getString(R.string.km), car.odometer.toString(), numeric = true)
        val box = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_odometer))
            .setView(box)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val v = Fmt.parseInt(f.text.toString())
                when {
                    v == null -> toast(R.string.err_odo)
                    v < car.odometer -> {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.odo_lower_confirm))
                            .setPositiveButton(getString(R.string.save)) { _, _ -> saveOdo(car, v) }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show()
                    }
                    else -> saveOdo(car, v)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
        f.requestFocus()
        f.selectAll()
    }

    private fun saveOdo(car: Car, v: Int) {
        Store.upsertCar(this, car.copy(odometer = v, odoDate = Fmt.today()))
        render()
    }

    // ---------- Schedule item actions ----------

    private fun itemOptions(car: Car, d: DueInfo) {
        val name = Reference.itemName(this, d.sched.item)
        val opts = arrayOf(
            getString(R.string.mark_done),
            getString(R.string.set_last_done),
            getString(R.string.edit_interval),
            getString(R.string.show_source)
        )
        AlertDialog.Builder(this).setTitle(name)
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> openEntry(null, d.sched.item)
                    1 -> setLastDone(car, d.sched)
                    2 -> editInterval(car, d.sched)
                    3 -> showSource(d.sched)
                }
            }.show()
    }

    private fun setLastDone(car: Car, s: SchedItem) {
        val kmField = field(getString(R.string.odometer_then), car.baselines[s.item]?.km?.toString() ?: "", numeric = true)
        var date = car.baselines[s.item]?.date ?: Fmt.today()
        val (dateRow, dateValue) = pickerRow(getString(R.string.date), Fmt.date(date)) {}
        dateRow.setOnClickListener {
            val ld = java.time.LocalDate.ofEpochDay(date)
            android.app.DatePickerDialog(this, { _, y, m, dd ->
                date = java.time.LocalDate.of(y, m + 1, dd).toEpochDay()
                dateValue.text = Fmt.date(date)
            }, ld.year, ld.monthValue - 1, ld.dayOfMonth).show()
        }
        val box = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.add(label(getString(R.string.set_last_done_hint), 13f, C.MUTED))
        box.add(kmField, lp(top = dp(10)))
        box.add(dateRow, lp(top = dp(8)))
        AlertDialog.Builder(this)
            .setTitle(Reference.itemName(this, s.item))
            .setView(box)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val km = Fmt.parseInt(kmField.text.toString())
                if (km == null) { toast(R.string.err_odo); return@setPositiveButton }
                Store.upsertCar(this, car.copy(baselines = car.baselines + (s.item to Baseline(km, date))))
                render()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun editInterval(car: Car, s: SchedItem) {
        val kmF = field(getString(R.string.interval_km), if (s.km > 0) s.km.toString() else "", numeric = true)
        val moF = field(getString(R.string.interval_months), if (s.months > 0) s.months.toString() else "", numeric = true)
        val sw = themedSwitch(s.enabled)
        val swRow = horizontal()
        swRow.add(label(getString(R.string.reminder_on), 15f, C.TEXT), LinearLayout.LayoutParams(0, WRAP, 1f))
        swRow.add(sw, LinearLayout.LayoutParams(WRAP, WRAP))
        val box = vertical().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.add(label(getString(R.string.interval_hint), 13f, C.MUTED))
        box.add(kmF, lp(top = dp(10)))
        box.add(moF, lp(top = dp(8)))
        box.add(swRow, lp(top = dp(10)))
        AlertDialog.Builder(this)
            .setTitle(Reference.itemName(this, s.item))
            .setView(box)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val km = Fmt.parseInt(kmF.text.toString()) ?: 0
                val mo = Fmt.parseInt(moF.text.toString()) ?: 0
                val updated = s.copy(km = km, months = mo, enabled = sw.isChecked, confidence = "user", source = "")
                Store.upsertCar(this, car.copy(schedule = car.schedule.map { if (it.item == s.item) updated else it }))
                render()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSource(s: SchedItem) {
        val lib = Reference.get(this)
        val conf = when (s.confidence) {
            "official" -> getString(R.string.conf_official)
            "secondary" -> getString(R.string.conf_secondary)
            "user" -> getString(R.string.conf_user)
            else -> getString(R.string.conf_estimated)
        }
        val src = lib.sources[s.source]
        val msg = StringBuilder()
        msg.append(Due.intervalText(this, s)).append("\n\n")
        msg.append(getString(R.string.confidence)).append(": ").append(conf)
        if (src != null) msg.append("\n\n").append(getString(R.string.source)).append(": ").append(src.title).append("\n").append(src.url)
        AlertDialog.Builder(this)
            .setTitle(Reference.itemName(this, s.item))
            .setMessage(msg.toString())
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
}
