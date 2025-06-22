package com.example.alarmgemini

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.alarmgemini.alarm.AlarmScheduler
import com.example.alarmgemini.alarm.AlarmSchedulerImpl
import java.time.LocalDateTime

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val scheduler: AlarmScheduler = AlarmSchedulerImpl(application)

    fun scheduleAlarm(time: LocalDateTime) {
        scheduler.schedule(0, time)
    }

    fun cancelAlarm() {
        scheduler.cancel(0)
    }
} 