package com.carcare.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

/** Add or edit a car: name, type from the reference library, odometer, last oil change. */
class CarEditActivity : Activity() {

    private var existing: Car? = null

    private lateinit var nameField: EditText
    private lateinit var yearField: EditText
    private lateinit var odoField: EditText
    private lateinit var typeValue: TextView
    private var oilKmField: EditText? = null
    private var oilDateValue: TextView? = null

    // Selected vehicle type
    private var brandId = ""
    private var modelId = ""
    private var genId = ""
    private var engineId = ""
    private var scheduleKey = ""
    private var refLabel = ""
    private var oilDate: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = intent.getStringExtra(EXTRA_CAR_ID)?.let { Store.car(this, it) }
        existing?.let {
            brandId = it.brandId; modelId = it.modelId; genId = it.genId; engineId = it.engineId
            scheduleKey = it.scheduleKey; refLabel = it.refLabel
        }
        val car = existing

        val root = vertical().apply { setPadding(dp(20), dp(24), dp(20), dp(32)) }
        root.add(label(getString(if (car == null) R.string.new_car else R.string.edit_car), 26f, C.TEXT, bold = true))

        root.add(sectionTitle(getString(R.string.car_name)), lp(top = dp(18)))
        nameField = field(getString(R.string.car_name_hint), car?.name ?: "")
        root.add(nameField, lp(top = dp(6)))

        root.add(sectionTitle(getString(R.string.car_type)), lp(top = dp(16)))
        val (typeRow, tv) = pickerRow(getString(R.string.choose), refLabel.ifEmpty { getString(R.string.not_chosen) }) { pickBrand() }
        typeValue = tv
        root.add(typeRow, lp(top = dp(6)))
        root.add(label(getString(R.string.car_type_hint), 12f, C.MUTED), lp(top = dp(4)))

        root.add(sectionTitle(getString(R.string.year)), lp(top = dp(16)))
        yearField = field("2025", car?.year?.takeIf { it > 0 }?.toString() ?: "", numeric = true)
        root.add(yearField, lp(top = dp(6)))

        root.add(sectionTitle(getString(R.string.current_odometer)), lp(top = dp(16)))
        odoField = field(getString(R.string.km), car?.odometer?.toString() ?: "", numeric = true)
        root.add(odoField, lp(top = dp(6)))

        if (car == null) {
            root.add(sectionTitle(getString(R.string.last_oil_optional)), lp(top = dp(20)))
            val f = field(getString(R.string.oil_km_hint), "", numeric = true)
            oilKmField = f
            root.add(f, lp(top = dp(6)))
            val (dateRow, dv) = pickerRow(getString(R.string.date), getString(R.string.not_chosen)) { pickOilDate() }
            oilDateValue = dv
            root.add(dateRow, lp(top = dp(8)))
            root.add(label(getString(R.string.last_oil_hint), 12f, C.MUTED), lp(top = dp(4)))
        }

        root.add(button(getString(R.string.save), C.ACCENT, C.ON_ACCENT) { save() }, lp(top = dp(26)))

        if (car != null) {
            root.add(button(getString(R.string.reset_schedule), C.SURFACE, C.TEXT) { confirmResetSchedule() }, lp(top = dp(10)))
            root.add(button(getString(R.string.delete_car), C.SURFACE, C.BAD) { confirmDelete() }, lp(top = dp(10)))
        }

        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(root) })
    }

    // ---------- Vehicle type: brand → model → generation → engine ----------

    private fun pickBrand() {
        val lib = Reference.get(this)
        val names = lib.brands.map { it.name.get(this) } + getString(R.string.other_brand)
        AlertDialog.Builder(this).setTitle(getString(R.string.brand))
            .setItems(names.toTypedArray()) { _, i ->
                if (i == lib.brands.size) pickGeneric() else pickModel(lib.brands[i])
            }.show()
    }

    private fun pickGeneric() {
        val lib = Reference.get(this)
        val keys = lib.schedules.keys.filter { it.startsWith("generic_") }.sorted()
        val names = keys.map { lib.schedules[it]!!.name.get(this) }
        AlertDialog.Builder(this).setTitle(getString(R.string.generic_schedule))
            .setItems(names.toTypedArray()) { _, i ->
                brandId = "other"; modelId = ""; genId = ""; engineId = ""
                scheduleKey = keys[i]
                refLabel = names[i]
                typeValue.text = refLabel
            }.show()
    }

    private fun pickModel(b: Reference.Brand) {
        val models = b.models.sortedBy { it.name.get(this) }
        AlertDialog.Builder(this).setTitle(b.name.get(this))
            .setItems(models.map { it.name.get(this) }.toTypedArray()) { _, i -> pickGen(b, models[i]) }
            .show()
    }

    private fun pickGen(b: Reference.Brand, m: Reference.Model) {
        if (m.gens.size == 1) { pickEngine(b, m, m.gens[0]); return }
        val labels = m.gens.map { g -> g.name.get(this) + "  (" + g.from + "–" + (g.to?.toString() ?: "") + ")" }
        AlertDialog.Builder(this).setTitle(m.name.get(this))
            .setItems(labels.toTypedArray()) { _, i -> pickEngine(b, m, m.gens[i]) }
            .show()
    }

    private fun pickEngine(b: Reference.Brand, m: Reference.Model, g: Reference.Gen) {
        if (g.engines.size == 1) { choose(b, m, g, g.engines[0]); return }
        AlertDialog.Builder(this).setTitle(getString(R.string.engine))
            .setItems(g.engines.map { it.name.get(this) }.toTypedArray()) { _, i -> choose(b, m, g, g.engines[i]) }
            .show()
    }

    private fun choose(b: Reference.Brand, m: Reference.Model, g: Reference.Gen, e: Reference.Engine) {
        brandId = b.id; modelId = m.id; genId = g.id; engineId = e.id; scheduleKey = e.schedule
        val parts = mutableListOf(b.name.get(this) + " " + m.name.get(this))
        if (g.id != "all") parts += g.name.get(this)
        if (e.id != "any") parts += e.name.get(this)
        refLabel = parts.joinToString(" · ")
        typeValue.text = refLabel
        if (nameField.text.isBlank()) nameField.setText(m.name.get(this))
        val sched = Reference.schedule(this, scheduleKey)
        if (sched != null && scheduleKey.startsWith("generic_")) {
            Toast.makeText(this, getString(R.string.uses_generic, sched.name.get(this)), Toast.LENGTH_LONG).show()
        }
    }

    private fun pickOilDate() {
        val d = LocalDate.ofEpochDay(oilDate ?: Fmt.today())
        DatePickerDialog(this, { _, y, mo, day ->
            oilDate = LocalDate.of(y, mo + 1, day).toEpochDay()
            oilDateValue?.text = Fmt.date(oilDate!!)
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    // ---------- Save ----------

    private fun save() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) { toast(R.string.err_name); return }
        if (scheduleKey.isEmpty()) { toast(R.string.err_type); return }
        val odo = Fmt.parseInt(odoField.text.toString())
        if (odo == null || odo < 0) { toast(R.string.err_odo); return }
        val year = Fmt.parseInt(yearField.text.toString()) ?: 0

        val old = existing
        if (old == null) {
            val schedule = Reference.schedule(this, scheduleKey)?.items ?: emptyList()
            val baselines = mutableMapOf<String, Baseline>()
            val oilKm = oilKmField?.text?.toString()?.let { Fmt.parseInt(it) }
            if (oilKm != null) baselines["engine_oil"] = Baseline(oilKm, oilDate ?: Fmt.today())
            val car = Car(
                id = newId(), name = name, year = year,
                brandId = brandId, modelId = modelId, genId = genId, engineId = engineId,
                refLabel = refLabel, scheduleKey = scheduleKey,
                odometer = odo, odoDate = Fmt.today(),
                schedule = schedule, baselines = baselines
            )
            Store.upsertCar(this, car)
        } else {
            val typeChanged = old.scheduleKey != scheduleKey
            val schedule = if (typeChanged) Reference.schedule(this, scheduleKey)?.items ?: old.schedule else old.schedule
            Store.upsertCar(
                this, old.copy(
                    name = name, year = year,
                    brandId = brandId, modelId = modelId, genId = genId, engineId = engineId,
                    refLabel = refLabel, scheduleKey = scheduleKey,
                    odometer = odo, odoDate = if (odo != old.odometer) Fmt.today() else old.odoDate,
                    schedule = schedule
                )
            )
        }
        finish()
    }

    private fun confirmResetSchedule() {
        val car = existing ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_schedule))
            .setMessage(getString(R.string.reset_schedule_msg))
            .setPositiveButton(getString(R.string.reset)) { _, _ ->
                val s = Reference.schedule(this, car.scheduleKey)
                if (s != null) {
                    Store.upsertCar(this, car.copy(schedule = s.items))
                    existing = Store.car(this, car.id)
                    toast(R.string.schedule_reset_done)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmDelete() {
        val car = existing ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_car))
            .setMessage(getString(R.string.delete_car_msg, car.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                Store.deleteCar(this, car.id)
                setResult(RESULT_FIRST_USER)
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
}
