package com.example.alarmgemini.alarm

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scheduler: AlarmScheduler = AlarmSchedulerImpl(context)

    @Test
    fun schedule_shouldRegisterAlarm() {
        val id = 99
        val time = LocalDateTime.now().plusMinutes(1)
        scheduler.schedule(id, time)

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = Shadows.shadowOf(alarmManager)
        val next = shadow.nextScheduledAlarm
        assertNotNull("Alarm should be scheduled", next)
    }

    @Test
    fun cancel_shouldRemoveAlarm() {
        val id = 100
        val time = LocalDateTime.now().plusMinutes(1)
        scheduler.schedule(id, time)
        scheduler.cancel(id)

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = Shadows.shadowOf(alarmManager)
        // After cancel, shadow nextScheduledAlarm becomes null
        assertNull("Alarm should be cancelled", shadow.nextScheduledAlarm)
    }
} 