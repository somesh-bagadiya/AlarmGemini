package com.example.alarmgemini

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import com.example.alarmgemini.alarm.AlarmScheduler
import com.example.alarmgemini.alarm.AlarmSchedulerImpl
import java.util.Locale

/** Temporary UI model until Room database is added */
data class AlarmUiModel(
    val id: Int,
    val dateTime: LocalDateTime,
    val enabled: Boolean,
    val recurringDays: List<DayOfWeek> = emptyList()
) {
    val time: LocalTime get() = dateTime.toLocalTime()
}

class AlarmListViewModel(application: Application) : AndroidViewModel(application) {
    private val _alarms = MutableStateFlow(
        listOf(
            AlarmUiModel(1, LocalDateTime.now().withHour(5).withMinute(45), true, listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            )),
            AlarmUiModel(2, LocalDateTime.now().withHour(6).withMinute(45), false),
            AlarmUiModel(3, LocalDateTime.now().withHour(8).withMinute(0), false),
        )
    )
    val alarms: StateFlow<List<AlarmUiModel>> = _alarms

    private val scheduler: AlarmScheduler = AlarmSchedulerImpl(application)

    fun toggle(id: Int, enabled: Boolean) {
        _alarms.value = _alarms.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }

        val alarm = _alarms.value.first { it.id == id }
        if (enabled) {
            scheduler.schedule(id, alarm.dateTime)
        } else {
            scheduler.cancel(id)
        }
    }

    fun updateTime(id: Int, newTime: LocalDateTime) {
        _alarms.value = _alarms.value.map {
            if (it.id == id) it.copy(dateTime = newTime) else it
        }

        val alarm = _alarms.value.first { it.id == id }
        if (alarm.enabled) scheduler.schedule(id, alarm.dateTime)
    }

    fun addAlarm(time: LocalTime, days: List<DayOfWeek>): Int {
        val nextId = (_alarms.value.maxOfOrNull { it.id } ?: 0) + 1
        val dateTime = LocalDateTime.now()
            .withHour(time.hour)
            .withMinute(time.minute)
            .withSecond(0)
            .withNano(0)

        val newAlarm = AlarmUiModel(nextId, dateTime, enabled = true, recurringDays = days)
        _alarms.value = _alarms.value + newAlarm

        scheduler.schedule(nextId, newAlarm.dateTime)
        return nextId
    }

    /**
     * Helper used by MCP / function-calling layer. Accepts a 24-hour [time], optional ISO-8601
     * weekday strings (MON..SUN). Returns the newly created alarm ID so that the caller can send
     * it back to Gemini.
     */
    fun addAlarmFromTool(time: LocalTime, recurrence: List<String>?, label: String?): Int {
        val days: List<DayOfWeek> = recurrence?.mapNotNull { code ->
            runCatching { DayOfWeek.valueOf(code.uppercase(Locale.US)) }.getOrNull()
        } ?: emptyList()

        val nextId = (_alarms.value.maxOfOrNull { it.id } ?: 0) + 1
        val dateTime = LocalDateTime.now()
            .withHour(time.hour)
            .withMinute(time.minute)
            .withSecond(0)
            .withNano(0)

        val newAlarm = AlarmUiModel(nextId, dateTime, enabled = true, recurringDays = days)
        _alarms.value = _alarms.value + newAlarm

        scheduler.schedule(nextId, newAlarm.dateTime)

        // TODO: label is not yet persisted; will be stored once Room DB is introduced.
        return nextId
    }

    /**
     * Cancel and remove alarm by ID.
     * @return true if an alarm with [id] existed and was removed, false otherwise.
     */
    fun deleteAlarmById(id: Int): Boolean {
        val existed = _alarms.value.any { it.id == id }
        if (existed) {
            _alarms.value = _alarms.value.filterNot { it.id == id }
            scheduler.cancel(id)
        }
        return existed
    }

    /**
     * Bulk delete operations for AI commands like "delete all except last"
     */
    fun deleteAllExceptLast(): Int {
        val sortedAlarms = _alarms.value.sortedBy { it.id }
        if (sortedAlarms.size <= 1) return 0
        
        val toDelete = sortedAlarms.dropLast(1)
        toDelete.forEach { alarm ->
            scheduler.cancel(alarm.id)
        }
        
        _alarms.value = _alarms.value.filter { alarm -> 
            toDelete.none { it.id == alarm.id }
        }
        
        return toDelete.size
    }

    /**
     * Delete multiple alarms by IDs
     */
    fun deleteAlarmsByIds(ids: List<Int>): Int {
        var deletedCount = 0
        ids.forEach { id ->
            val existed = _alarms.value.any { it.id == id }
            if (existed) {
                _alarms.value = _alarms.value.filterNot { it.id == id }
                scheduler.cancel(id)
                deletedCount++
            }
        }
        return deletedCount
    }

    /**
     * Delete all alarms
     */
    fun deleteAllAlarms(): Int {
        val count = _alarms.value.size
        _alarms.value.forEach { alarm ->
            scheduler.cancel(alarm.id)
        }
        _alarms.value = emptyList()
        return count
    }
} 