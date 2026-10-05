package com.carcare.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Daily check (around 9 AM) that posts one notification per car that needs attention. */
object Reminders {
    const val CHANNEL = "maintenance_v1"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.timeInMillis, AlarmManager.INTERVAL_DAY, pending(ctx))
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun check(ctx: Context) {
        ensureChannel(ctx)
        Store.load(ctx, force = true)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return

        Store.cars(ctx).forEachIndexed { index, car ->
            val due = Due.all(ctx, car)
            val overdue = due.filter { it.state == DueState.OVERDUE }
            val soon = due.filter { it.state == DueState.SOON }
            val stale = Due.odoDaysOld(car) >= ODO_STALE_DAYS

            val lines = mutableListOf<String>()
            overdue.forEach { lines += "⚠ " + Reference.itemName(ctx, it.sched.item) + " — " + Due.describe(ctx, it) }
            soon.forEach { lines += "• " + Reference.itemName(ctx, it.sched.item) + " — " + Due.describe(ctx, it) }
            if (stale) lines += ctx.getString(R.string.notif_update_odo, Due.odoDaysOld(car).toString())
            if (lines.isEmpty()) {
                nm.cancel(1000 + index)
                return@forEachIndexed
            }

            val open = PendingIntent.getActivity(
                ctx, 1000 + index,
                Intent(ctx, CarActivity::class.java).putExtra(EXTRA_CAR_ID, car.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val title = if (overdue.isNotEmpty() || soon.isNotEmpty())
                ctx.getString(R.string.notif_title_due, car.name)
            else ctx.getString(R.string.notif_title_odo, car.name)

            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(lines.first())
                .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(1000 + index, n)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Reminders.check(context)
                Reference.checkForUpdate(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.schedule(context)
    }
}
