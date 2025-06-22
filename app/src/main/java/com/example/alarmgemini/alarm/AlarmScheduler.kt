package com.example.alarmgemini.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import android.provider.Settings
import android.os.Build

interface AlarmScheduler {
    fun schedule(id: Int, time: LocalDateTime)
    fun cancel(id: Int)
}

class AlarmSchedulerImpl(
    private val context: Context
) : AlarmScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(id: Int, time: LocalDateTime) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Convert LocalDateTime to epoch milliseconds in the device's default zone
        val triggerAtMillis = time
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // Launch settings to request permission
            val settingsIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(settingsIntent)
            return
        }

        // If the time is in the past, schedule for the next day
        val adjustedTrigger = if (triggerAtMillis <= System.currentTimeMillis()) {
            // Add 24 hours
            triggerAtMillis + 24 * 60 * 60 * 1000
        } else triggerAtMillis

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                adjustedTrigger,
                pendingIntent
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
            // TODO: Consider guiding the user to grant SCHEDULE_EXACT_ALARM permission.
        }
    }

    override fun cancel(id: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
} 