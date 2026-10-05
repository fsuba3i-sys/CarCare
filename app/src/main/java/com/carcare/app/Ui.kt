package com.carcare.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dark palette with a teal accent. */
object C {
    val BG = Color.parseColor("#0F1215")
    val SURFACE = Color.parseColor("#1A1F24")
    val SURFACE2 = Color.parseColor("#262D34")
    val TEXT = Color.parseColor("#E9EDF1")
    val MUTED = Color.parseColor("#8E99A4")
    val ACCENT = Color.parseColor("#3DB8A5")
    val ON_ACCENT = Color.parseColor("#06201C")
    val OK = Color.parseColor("#4CD38A")
    val WARN = Color.parseColor("#F2B84B")
    val BAD = Color.parseColor("#FF6464")
}

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun roundRect(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

fun lp(width: Int = MATCH, height: Int = WRAP, weight: Float = 0f, top: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height, weight).apply { topMargin = top }

fun Context.vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.label(text: CharSequence, sizeSp: Float, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

fun Context.button(text: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
    label(text, 16f, fg, bold = true).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        val r = dp(14).toFloat()
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            roundRect(bg, r),
            roundRect(Color.WHITE, r)
        )
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.card(): LinearLayout = vertical().apply {
    background = roundRect(C.SURFACE, dp(18).toFloat())
    setPadding(dp(18), dp(16), dp(18), dp(16))
}

fun Context.field(hint: String, value: String = "", numeric: Boolean = false, decimal: Boolean = false): EditText =
    EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(C.TEXT)
        setHintTextColor(C.MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = roundRect(C.SURFACE, dp(14).toFloat())
        setPadding(dp(16), dp(14), dp(16), dp(14))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        inputType = when {
            decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            numeric -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
    }

/** A tappable row that looks like a form field and shows its current value. */
fun Context.pickerRow(title: String, value: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
    val row = horizontal().apply {
        background = roundRect(C.SURFACE, dp(14).toFloat())
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }
    row.addView(label(title, 15f, C.MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
    val v = label(value, 15f, C.ACCENT, bold = true)
    row.addView(v, LinearLayout.LayoutParams(WRAP, WRAP))
    return row to v
}

fun Context.themedSwitch(checked: Boolean): Switch = Switch(this).apply {
    isChecked = checked
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(C.ACCENT, C.MUTED))
    trackTintList = ColorStateList(states, intArrayOf(Color.argb(110, 61, 184, 165), Color.argb(70, 142, 153, 164)))
}

fun Context.sectionTitle(text: String): TextView = label(text, 14f, C.MUTED, bold = true).apply {
    setPadding(dp(4), 0, dp(4), 0)
}

fun LinearLayout.add(v: View, params: LinearLayout.LayoutParams = lp()): View {
    addView(v, params)
    return v
}

// ---------- Formatting (Western digits everywhere so numbers stay readable in both languages) ----------

object Fmt {
    private val nf = NumberFormat.getIntegerInstance(Locale.US)
    private val money = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun km(v: Int): String = nf.format(v.toLong())
    fun money(v: Double): String = money.format(v)
    fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFmt)
    fun today(): Long = LocalDate.now().toEpochDay()

    fun parseInt(s: String): Int? = s.trim().replace(",", "").replace("٬", "").let { toWestern(it) }.toIntOrNull()
    fun parseDouble(s: String): Double? = toWestern(s.trim().replace(",", "").replace("٫", ".")).toDoubleOrNull()

    /** Accept Arabic-Indic digits typed on an Arabic keyboard. */
    private fun toWestern(s: String): String = buildString {
        for (ch in s) {
            append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    else -> ch
                }
            )
        }
    }
}

/** True when the UI is currently Arabic. */
fun Context.isArabic(): Boolean = resources.configuration.locales[0].language == "ar"
