package com.carcare.app

import android.content.Context
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Reminder thresholds. */
const val SOON_KM = 500
const val SOON_DAYS = 30
const val ODO_STALE_DAYS = 14

enum class DueState { OVERDUE, SOON, OK, UNKNOWN }

data class DueInfo(
    val sched: SchedItem,
    val lastKm: Int?,
    val lastDate: Long?,
    val nextKm: Int?,
    val nextDate: Long?,
    val kmLeft: Int?,
    val daysLeft: Long?,
    val state: DueState
)

object Due {

    /** Latest time each item was done: from the log, or from the baseline the user entered. */
    fun lastDone(car: Car, entries: List<Entry>, item: String): Pair<Int, Long>? {
        val fromLog = entries.filter { item in it.items }.maxWithOrNull(compareBy<Entry>({ it.date }, { it.km }))
        val base = car.baselines[item]
        return when {
            fromLog == null && base == null -> null
            fromLog == null -> base!!.km to base.date
            base == null -> fromLog.km to fromLog.date
            fromLog.date >= base.date -> fromLog.km to fromLog.date
            else -> base.km to base.date
        }
    }

    fun info(car: Car, entries: List<Entry>, s: SchedItem): DueInfo {
        val last = lastDone(car, entries, s.item)
            ?: return DueInfo(s, null, null, null, null, null, null, DueState.UNKNOWN)
        val (lastKm, lastDate) = last
        val nextKm = if (s.km > 0) lastKm + s.km else null
        val nextDate = if (s.months > 0) LocalDate.ofEpochDay(lastDate).plusMonths(s.months.toLong()).toEpochDay() else null
        val kmLeft = nextKm?.let { it - car.odometer }
        val daysLeft = nextDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.ofEpochDay(it)) }

        val state = when {
            (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> DueState.OVERDUE
            (kmLeft != null && kmLeft <= SOON_KM) || (daysLeft != null && daysLeft <= SOON_DAYS) -> DueState.SOON
            else -> DueState.OK
        }
        return DueInfo(s, lastKm, lastDate, nextKm, nextDate, kmLeft, daysLeft, state)
    }

    fun all(ctx: Context, car: Car): List<DueInfo> {
        val entries = Store.entries(ctx, car.id)
        return car.schedule.filter { it.enabled }.map { info(car, entries, it) }
            .sortedWith(compareBy<DueInfo>({ it.state.ordinal }, { it.kmLeft ?: Int.MAX_VALUE }))
    }

    fun odoDaysOld(car: Car): Long = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(car.odoDate), LocalDate.now())

    fun color(state: DueState): Int = when (state) {
        DueState.OVERDUE -> C.BAD
        DueState.SOON -> C.WARN
        DueState.OK -> C.OK
        DueState.UNKNOWN -> C.MUTED
    }

    /** One line such as "Due in 1,200 km or 45 days" / "Overdue by 300 km". */
    fun describe(ctx: Context, d: DueInfo): String {
        if (d.state == DueState.UNKNOWN) return ctx.getString(R.string.due_no_record)
        val parts = mutableListOf<String>()
        d.kmLeft?.let {
            parts += if (it >= 0) ctx.getString(R.string.due_in_km, Fmt.km(it))
            else ctx.getString(R.string.due_over_km, Fmt.km(-it))
        }
        d.daysLeft?.let {
            parts += if (it >= 0) ctx.getString(R.string.due_in_days, it.toString())
            else ctx.getString(R.string.due_over_days, (-it).toString())
        }
        return parts.joinToString(ctx.getString(R.string.or_sep))
    }

    /** "At 52,000 km · 2027-03-01" */
    fun target(ctx: Context, d: DueInfo): String {
        val parts = mutableListOf<String>()
        d.nextKm?.let { parts += ctx.getString(R.string.at_km, Fmt.km(it)) }
        d.nextDate?.let { parts += Fmt.date(it) }
        return parts.joinToString("  ·  ")
    }

    fun intervalText(ctx: Context, s: SchedItem): String {
        val parts = mutableListOf<String>()
        if (s.km > 0) parts += ctx.getString(R.string.every_km, Fmt.km(s.km))
        if (s.months > 0) parts += ctx.getString(R.string.every_months, s.months.toString())
        return parts.joinToString(ctx.getString(R.string.or_sep))
    }
}
