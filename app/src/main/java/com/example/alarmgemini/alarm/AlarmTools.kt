package com.example.alarmgemini.alarm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.alarmgemini.AlarmListViewModel

/**
 * Kotlin data models that mirror the JSON argument objects used by Gemini
 * function-calling / MCP. These classes are `@Serializable` so they can be
 * (de)serialised with kotlinx.serialization or any JSON library.
 */
@Serializable
data class CreateAlarmArgs(
    /** HH:mm string, 24-hour clock. Example "07:30" */
    @SerialName("time") val time: String,
    /** Optional ISO-8601 weekday codes, e.g. ["MON","TUE"]. */
    @SerialName("recurrence") val recurrence: List<String>? = null,
    /** If provided, schedule multiple alarms starting from [time]. */
    @SerialName("count") val count: Int? = null,
    /** Gap between the multiple alarms, minutes. */
    @SerialName("gapMinutes") val gapMinutes: Int? = null,
    /** Human-readable label to show in the UI/notification. */
    @SerialName("label") val label: String? = null,
)

@Serializable
data class DeleteAlarmArgs(
    /** ID of the alarm previously returned by `create_alarm`. */
    @SerialName("id") val id: Int,
)

/**
 * Convenience object that converts the decoded arguments into calls to
 * [AlarmScheduler].  This keeps the MCP / tool bridge thin:
 *
 * 1. Gemini decides which tool to invoke and supplies JSON args.
 * 2. The SDK deserialises that JSON into one of the *Args data classes.
 * 3. We forward to the existing business logic (ViewModel + Scheduler).
 */
object AlarmToolHandler {

    private val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun handleCreate(args: CreateAlarmArgs, viewModel: AlarmListViewModel): List<Int> {
        val baseTime = runCatching { LocalTime.parse(args.time, formatter) }
            .getOrElse { throw IllegalArgumentException("Invalid time format: ${args.time}") }

        val ids = mutableListOf<Int>()
        val total = args.count ?: 1
        val gap = args.gapMinutes ?: 0
        repeat(total) { index ->
            val time = baseTime.plusMinutes((gap * index).toLong())
            val id = viewModel.addAlarmFromTool(time, args.recurrence, args.label)
            ids += id
        }
        return ids
    }

    fun handleDelete(args: DeleteAlarmArgs, viewModel: AlarmListViewModel) {
        viewModel.deleteAlarmById(args.id)
    }
} 