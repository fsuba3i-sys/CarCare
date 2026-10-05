package com.carcare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Photos: shrink to max 1600 px, fix rotation, save as JPEG inside the app. */
object Photos {
    private const val MAX = 1600

    fun saveFromUri(ctx: Context, uri: Uri): String? = try {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        saveBytes(ctx, bytes)
    } catch (_: Exception) { null }

    fun saveFromFile(ctx: Context, file: File): String? = try {
        val name = saveBytes(ctx, file.readBytes())
        file.delete()
        name
    } catch (_: Exception) { null }

    private fun saveBytes(ctx: Context, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX * 2 || bounds.outHeight / sample > MAX * 2) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val scale = MAX.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)

        val rotation = try {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (rotation != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        }

        val name = "p_" + System.currentTimeMillis() + ".jpg"
        File(Store.photosDir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return name
    }

    fun thumbnail(ctx: Context, name: String, sizePx: Int): Bitmap? {
        val f = File(Store.photosDir(ctx), name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Full backup = one .zip with data.json and all photos. */
object Backup {

    fun write(ctx: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val data = Store.dataFile(ctx)
            zip.putNextEntry(ZipEntry("data.json"))
            if (data.exists()) data.inputStream().use { it.copyTo(zip) } else zip.write("{}".toByteArray())
            zip.closeEntry()
            Store.photosDir(ctx).listFiles()?.forEach { f ->
                zip.putNextEntry(ZipEntry("photos/" + f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Returns false if the file is not a valid backup. Replaces all current data on success. */
    fun restore(ctx: Context, input: InputStream): Boolean {
        val tmpDir = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var dataText: String? = null
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val n = e.name
                if (n == "data.json") {
                    dataText = zip.readBytes().toString(Charsets.UTF_8)
                } else if (n.startsWith("photos/") && !e.isDirectory) {
                    val safe = File(n).name // never write outside the folder
                    File(tmpDir, safe).outputStream().use { zip.copyTo(it) }
                }
                e = zip.nextEntry
            }
        }
        val text = dataText ?: return false
        try { JSONObject(text).getJSONArray("cars") } catch (_: Exception) { return false }

        val photos = Store.photosDir(ctx)
        photos.listFiles()?.forEach { it.delete() }
        tmpDir.listFiles()?.forEach { it.copyTo(File(photos, it.name), overwrite = true) }
        tmpDir.deleteRecursively()
        Store.dataFile(ctx).writeText(text)
        Store.load(ctx, force = true)
        return true
    }
}

/** Minimal .xlsx writer (no libraries): one sheet with the full log. Opens in Excel and Google Sheets. */
object Xlsx {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    fun write(ctx: Context, out: OutputStream) {
        val cars = Store.cars(ctx).associateBy { it.id }
        val rows = mutableListOf<List<Any>>()
        rows += listOf(
            ctx.getString(R.string.col_car), ctx.getString(R.string.col_date), ctx.getString(R.string.col_km),
            ctx.getString(R.string.col_desc), ctx.getString(R.string.col_items), ctx.getString(R.string.col_oil), ctx.getString(R.string.col_cost)
        )
        Store.allEntries(ctx)
            .sortedWith(compareBy<Entry>({ cars[it.carId]?.name ?: "" }, { it.date }))
            .forEach { e ->
                rows += listOf(
                    cars[e.carId]?.name ?: "",
                    Fmt.date(e.date),
                    e.km,
                    e.desc,
                    e.items.joinToString("، ") { Reference.itemName(ctx, it) },
                    listOf(e.oilName, e.oilGrade).filter { it.isNotEmpty() }.joinToString(" "),
                    e.cost
                )
            }

        val sheet = StringBuilder()
        sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sheet.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (ctx.isArabic()) sheet.append("""<sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews>""")
        sheet.append("""<cols><col min="1" max="1" width="18" customWidth="1"/><col min="2" max="3" width="13" customWidth="1"/><col min="4" max="5" width="40" customWidth="1"/><col min="6" max="6" width="20" customWidth="1"/><col min="7" max="7" width="12" customWidth="1"/></cols>""")
        sheet.append("<sheetData>")
        rows.forEachIndexed { r, row ->
            sheet.append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                val style = if (r == 0) """ s="1"""" else ""
                when (v) {
                    is Number -> sheet.append("""<c r="$ref"$style><v>$v</v></c>""")
                    else -> sheet.append("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0"/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>"""

        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Log" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet.toString()
        )
        ZipOutputStream(out).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
