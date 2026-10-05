package com.carcare.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

/** Add or edit one log row (maintenance or part): date, odometer, description, cost, items done, photo. */
class EntryActivity : Activity() {

    companion object {
        private const val REQ_CAMERA = 21
        private const val REQ_GALLERY = 22
    }

    private lateinit var car: Car
    private var existing: Entry? = null

    private var date: Long = Fmt.today()
    private val items = linkedSetOf<String>()
    private var photo: String? = null
    private var pendingCameraFile: File? = null

    private lateinit var dateValue: TextView
    private lateinit var kmField: EditText
    private lateinit var descField: EditText
    private lateinit var costField: EditText
    private lateinit var itemsValue: TextView
    private lateinit var photoBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = intent.getStringExtra(EXTRA_CAR_ID)?.let { Store.car(this, it) } ?: run { finish(); return }
        existing = intent.getStringExtra(EXTRA_ENTRY_ID)?.let { Store.entry(this, it) }
        val e = existing
        if (e != null) {
            date = e.date; items.addAll(e.items); photo = e.photo
        } else {
            intent.getStringExtra(EXTRA_ITEM_ID)?.let { items += it }
        }
        savedInstanceState?.getString("cameraFile")?.let { pendingCameraFile = File(it) }
        savedInstanceState?.getString("photo")?.let { photo = it }

        val root = vertical().apply { setPadding(dp(20), dp(24), dp(20), dp(32)) }
        root.add(label(getString(if (e == null) R.string.new_entry else R.string.edit_entry), 26f, C.TEXT, bold = true))
        root.add(label(car.name, 14f, C.MUTED), lp(top = dp(2)))

        root.add(sectionTitle(getString(R.string.description)), lp(top = dp(18)))
        val defaultDesc = if (e == null && items.isNotEmpty()) items.joinToString("، ") { Reference.itemName(this, it) } else ""
        descField = field(getString(R.string.description_hint), e?.desc ?: defaultDesc)
        root.add(descField, lp(top = dp(6)))

        root.add(sectionTitle(getString(R.string.date)), lp(top = dp(14)))
        val (dRow, dv) = pickerRow(getString(R.string.date), Fmt.date(date)) { pickDate() }
        dateValue = dv
        root.add(dRow, lp(top = dp(6)))

        root.add(sectionTitle(getString(R.string.odometer)), lp(top = dp(14)))
        kmField = field(getString(R.string.km), (e?.km ?: car.odometer).toString(), numeric = true)
        root.add(kmField, lp(top = dp(6)))

        root.add(sectionTitle(getString(R.string.cost_sar)), lp(top = dp(14)))
        costField = field("0", e?.cost?.let { if (it == 0.0) "" else Fmt.money(it).replace(",", "") } ?: "", decimal = true)
        root.add(costField, lp(top = dp(6)))

        root.add(sectionTitle(getString(R.string.items_done)), lp(top = dp(14)))
        val (iRow, iv) = pickerRow(getString(R.string.choose), "") { pickItems() }
        itemsValue = iv
        root.add(iRow, lp(top = dp(6)))
        root.add(label(getString(R.string.items_done_hint), 12f, C.MUTED), lp(top = dp(4)))
        renderItems()

        root.add(sectionTitle(getString(R.string.photo)), lp(top = dp(14)))
        photoBox = vertical()
        root.add(photoBox, lp(top = dp(6)))
        renderPhoto()

        root.add(button(getString(R.string.save), C.ACCENT, C.ON_ACCENT) { save() }, lp(top = dp(26)))
        if (e != null) root.add(button(getString(R.string.delete), C.SURFACE, C.BAD) { confirmDelete() }, lp(top = dp(10)))

        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(root) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraFile?.let { outState.putString("cameraFile", it.path) }
        photo?.let { outState.putString("photo", it) }
    }

    private fun pickDate() {
        val d = LocalDate.ofEpochDay(date)
        DatePickerDialog(this, { _, y, m, day ->
            date = LocalDate.of(y, m + 1, day).toEpochDay()
            dateValue.text = Fmt.date(date)
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    // ---------- Items done (resets the matching reminder) ----------

    private fun allItemIds(): List<String> {
        val inSchedule = car.schedule.map { it.item }
        val rest = Reference.get(this).itemOrder.filter { it !in inSchedule }
        return inSchedule + rest
    }

    private fun pickItems() {
        val ids = allItemIds()
        val names = ids.map { Reference.itemName(this, it) }.toTypedArray()
        val checked = ids.map { it in items }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.items_done))
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val hadDefault = descField.text.toString() == items.joinToString("، ") { Reference.itemName(this, it) }
                items.clear()
                ids.forEachIndexed { i, id -> if (checked[i]) items += id }
                if (descField.text.isBlank() || hadDefault) {
                    descField.setText(items.joinToString("، ") { Reference.itemName(this, it) })
                }
                renderItems()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun renderItems() {
        itemsValue.text = if (items.isEmpty()) getString(R.string.none) else items.size.toString()
    }

    // ---------- Photo ----------

    private fun renderPhoto() {
        photoBox.removeAllViews()
        val p = photo
        if (p != null) {
            val f = File(Store.photosDir(this), p)
            val img = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.let { setImageBitmap(it) }
                background = roundRect(C.SURFACE, dp(14).toFloat())
                clipToOutline = true
                setOnClickListener { showFull(f) }
            }
            photoBox.add(img, LinearLayout.LayoutParams(MATCH, dp(220)))
            photoBox.add(button(getString(R.string.remove_photo), C.SURFACE, C.BAD) {
                // The file itself is deleted when the entry is saved.
                photo = null
                renderPhoto()
            }, lp(top = dp(8)))
        } else {
            val row = horizontal()
            row.add(button(getString(R.string.camera), C.SURFACE, C.TEXT) { openCamera() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            row.add(button(getString(R.string.gallery), C.SURFACE, C.TEXT) { openGallery() }, LinearLayout.LayoutParams(0, WRAP, 1f))
            photoBox.add(row)
        }
    }

    private fun showFull(f: File) {
        val img = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(BitmapFactory.decodeFile(f.path))
        }
        AlertDialog.Builder(this).setView(img).setPositiveButton(getString(R.string.ok), null).show()
    }

    private fun openCamera() {
        val f = File(Store.photosDir(this), "cam_" + System.currentTimeMillis() + ".jpg")
        pendingCameraFile = f
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_camera), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(i, getString(R.string.gallery)), REQ_GALLERY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAMERA) pendingCameraFile?.delete()
            return
        }
        val saved = when (requestCode) {
            REQ_CAMERA -> pendingCameraFile?.takeIf { it.exists() && it.length() > 0 }?.let { Photos.saveFromFile(this, it) }
            REQ_GALLERY -> data?.data?.let { Photos.saveFromUri(this, it) }
            else -> null
        }
        pendingCameraFile = null
        if (saved != null) {
            // Discard a previous unsaved photo taken in this screen.
            val prev = photo
            if (prev != null && prev != existing?.photo) File(Store.photosDir(this), prev).delete()
            photo = saved
            renderPhoto()
        } else {
            Toast.makeText(this, getString(R.string.photo_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Save / delete ----------

    private fun save() {
        val km = Fmt.parseInt(kmField.text.toString())
        if (km == null || km < 0) { toast(R.string.err_odo); return }
        val costText = costField.text.toString().trim()
        val cost = if (costText.isEmpty()) 0.0 else Fmt.parseDouble(costText)
        if (cost == null || cost < 0) { toast(R.string.err_cost); return }
        val desc = descField.text.toString().trim()
        if (desc.isEmpty() && items.isEmpty()) { toast(R.string.err_desc); return }

        val entry = Entry(
            id = existing?.id ?: newId(),
            carId = car.id,
            date = date,
            km = km,
            desc = desc,
            cost = cost,
            items = items.toList(),
            photo = photo
        )
        Store.upsertEntry(this, entry)
        finish()
    }

    private fun confirmDelete() {
        val e = existing ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_entry))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                Store.deleteEntry(this, e.id)
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
}
