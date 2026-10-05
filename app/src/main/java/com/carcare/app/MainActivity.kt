package com.carcare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_BACKUP = 11
        private const val REQ_RESTORE = 12
        private const val REQ_EXCEL = 13
    }

    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reminders.ensureChannel(this)
        Reminders.schedule(this)
        Reference.checkForUpdate(this)

        val root = vertical().apply { setPadding(dp(20), dp(24), dp(20), dp(32)) }

        val header = horizontal()
        header.add(label(getString(R.string.app_name), 30f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val menuBtn = label("⋮", 28f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        header.add(menuBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        root.add(header)
        root.add(label(getString(R.string.subtitle), 14f, C.MUTED), lp(top = dp(2)))

        listBox = vertical()
        root.add(listBox, lp(top = dp(18)))

        root.add(button("+  " + getString(R.string.add_car), C.ACCENT, C.ON_ACCENT) {
            startActivity(Intent(this, CarEditActivity::class.java))
        }, lp(top = dp(16)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.BG)
            isFillViewport = true
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val cars = Store.cars(this)
        if (cars.isEmpty()) {
            listBox.add(label(getString(R.string.no_cars), 15f, C.MUTED).apply {
                gravity = Gravity.CENTER
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                setPadding(0, dp(48), 0, dp(48))
            })
            return
        }
        for (car in cars) {
            val due = Due.all(this, car)
            val c = card().apply {
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, CarActivity::class.java).putExtra(EXTRA_CAR_ID, car.id))
                }
            }
            val top = horizontal()
            top.add(label(car.name, 22f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            top.add(label(Fmt.km(car.odometer) + " " + getString(R.string.km), 15f, C.MUTED), LinearLayout.LayoutParams(WRAP, WRAP))
            c.add(top)
            val sub = listOfNotNull(car.year.takeIf { it > 0 }?.toString(), car.refLabel.ifEmpty { null }).joinToString(" · ")
            if (sub.isNotEmpty()) c.add(label(sub, 13f, C.MUTED), lp(top = dp(2)))

            // Oil line
            val oil = due.firstOrNull { it.sched.item == "engine_oil" }
            if (oil != null) {
                val text = getString(R.string.oil) + ": " + Due.describe(this, oil)
                c.add(label(text, 14f, Due.color(oil.state), bold = true), lp(top = dp(10)))
            }
            // Most urgent other item
            val urgent = due.filter { it.sched.item != "engine_oil" && (it.state == DueState.OVERDUE || it.state == DueState.SOON) }
            if (urgent.isNotEmpty()) {
                val first = urgent.first()
                var t = Reference.itemName(this, first.sched.item) + ": " + Due.describe(this, first)
                if (urgent.size > 1) t += "  " + getString(R.string.plus_more, (urgent.size - 1).toString())
                c.add(label(t, 14f, Due.color(first.state)), lp(top = dp(4)))
            }
            if (Due.odoDaysOld(car) >= ODO_STALE_DAYS) {
                c.add(label(getString(R.string.odo_stale, Due.odoDaysOld(car).toString()), 13f, C.WARN), lp(top = dp(4)))
            }
            listBox.add(c, lp(top = dp(10)))
        }
    }

    // ---------- Menu ----------

    private fun showMenu(anchor: TextView) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, getString(R.string.menu_language))
        pm.menu.add(0, 2, 1, getString(R.string.menu_backup))
        pm.menu.add(0, 3, 2, getString(R.string.menu_restore))
        pm.menu.add(0, 4, 3, getString(R.string.menu_excel))
        pm.menu.add(0, 5, 4, getString(R.string.menu_update_ref))
        pm.menu.add(0, 6, 5, getString(R.string.menu_about_ref))
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> chooseLanguage()
                2 -> create(REQ_BACKUP, "application/zip", "CarCare-backup-" + Fmt.date(Fmt.today()) + ".zip")
                3 -> pick(REQ_RESTORE)
                4 -> create(REQ_EXCEL, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "CarCare-log-" + Fmt.date(Fmt.today()) + ".xlsx")
                5 -> updateReference()
                6 -> aboutReference()
            }
            true
        }
        pm.show()
    }

    private fun chooseLanguage() {
        val options = arrayOf(getString(R.string.lang_system), "العربية", "English")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_language))
            .setItems(options) { _, which ->
                val tags = when (which) { 1 -> "ar"; 2 -> "en"; else -> "" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getSystemService(LocaleManager::class.java).applicationLocales =
                        if (tags.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tags)
                } else {
                    Toast.makeText(this, getString(R.string.lang_old_android), Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun create(req: Int, mime: String, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, req)
    }

    private fun pick(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, req)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_BACKUP -> {
                    contentResolver.openOutputStream(uri)!!.use { Backup.write(this, it) }
                    toast(R.string.backup_done)
                }
                REQ_EXCEL -> {
                    contentResolver.openOutputStream(uri)!!.use { Xlsx.write(this, it) }
                    toast(R.string.excel_done)
                }
                REQ_RESTORE -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.restore_confirm_title))
                        .setMessage(getString(R.string.restore_confirm_msg))
                        .setPositiveButton(getString(R.string.restore)) { _, _ ->
                            val ok = try {
                                contentResolver.openInputStream(uri)!!.use { Backup.restore(this, it) }
                            } catch (_: Exception) { false }
                            toast(if (ok) R.string.restore_done else R.string.restore_failed)
                            render()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error) + ": " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateReference() {
        toast(R.string.ref_checking)
        Reference.checkForUpdate(this, force = true) { r ->
            runOnUiThread {
                toast(
                    when (r) {
                        Reference.Result.UPDATED -> R.string.ref_updated
                        Reference.Result.UP_TO_DATE -> R.string.ref_up_to_date
                        else -> R.string.ref_failed
                    }
                )
            }
        }
    }

    private fun aboutReference() {
        val lib = Reference.get(this)
        val models = lib.brands.sumOf { it.models.size }
        val msg = getString(R.string.ref_about, lib.version.toString(), lib.updated, lib.brands.size.toString(), models.toString())
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_about_ref))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
}
