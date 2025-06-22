package com.example.alarmgemini.ai

import java.time.LocalTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern
import android.util.Log

/**
 * Enhanced Agentic NLP Parser for complex alarm commands.
 * 
 * Capabilities:
 * - Multi-alarm creation: "set 5 alarms in 5 minutes"
 * - System time awareness: Uses current time for all calculations
 * - Intelligent spacing: Automatic interval calculation
 * - Complex patterns: "create backup alarms every 10 minutes"
 */
object SimpleNlp {

    // Multi-alarm patterns: "set 5 alarms in 5 minutes", "create 3 alarms starting at 6 AM with 10 minute gaps", "set 2 alarms 5 mins apart at 10 am"
    private val multiAlarmRegex = Pattern.compile(
        """(?:set|create)\s+(\d+)\s+alarms?\s+(?:(?:in\s+(\d+)\s+(?:minute|minutes|min|mins))|(?:starting\s+at\s+(.+?)\s+with\s+(\d+)\s+(?:minute|minutes|min|mins)\s+(?:gap|gaps|interval|intervals)?)|(?:(\d+)\s+(?:minute|minutes|min|mins)\s+apart\s+at\s+(.+)))""",
        Pattern.CASE_INSENSITIVE
    )

    // Backup alarm patterns: "create backup alarms every 10 minutes"
    private val backupAlarmRegex = Pattern.compile(
        """(?:create|set)\s+backup\s+alarms?\s+every\s+(\d+)\s+(?:minute|minutes|min|mins)(?:\s+(?:for|starting)\s+(.+?))?""",
        Pattern.CASE_INSENSITIVE
    )

    // Enhanced relative time patterns
    private val relativeTimeRegex = Pattern.compile(
        """(?:(?:in\s+)?(\d+)\s+(?:hour|hours|hr|hrs)(?:\s+and\s+(\d+)\s+(?:minute|minutes|min|mins))?)|(?:(?:in\s+)?(\d+)\s+(?:minute|minutes|min|mins))|(?:(\d+)\s+(?:minute|minutes|min|mins)\s+from\s+now)""",
        Pattern.CASE_INSENSITIVE
    )

    // Absolute time patterns (enhanced)
    private val absoluteTimeRegex = Pattern.compile(
        """(?:(1[0-2]|0?[1-9])(:([0-5][0-9]))?\s*(am|pm))|(2[0-3]|[0-1]?[0-9]):([0-5][0-9])""", 
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Agentic command result containing multiple alarms and metadata
     */
    data class AgenticCommand(
        val type: CommandType,
        val alarms: List<LocalTime>,
        val description: String,
        val intervals: List<Int> = emptyList() // in minutes
    )

    enum class CommandType {
        SINGLE_ALARM,
        MULTI_ALARM_SEQUENCE,
        BACKUP_ALARMS,
        RECURRING_PATTERN
    }

    /**
     * Main agentic parsing function that handles complex commands
     */
    fun parseAgenticCommand(text: String): AgenticCommand? {
        val cleanText = text.lowercase(Locale.US)
        Log.d("SimpleNlp", "Parsing agentic command: '$cleanText'")
        
        // Check for multi-alarm commands first
        val multiAlarm = tryParseMultiAlarm(cleanText)
        if (multiAlarm != null) {
            Log.d("SimpleNlp", "Successfully parsed multi-alarm: ${multiAlarm.description}")
            return multiAlarm
        }
        
        // Check for backup alarm patterns
        val backupAlarm = tryParseBackupAlarms(cleanText)
        if (backupAlarm != null) {
            Log.d("SimpleNlp", "Successfully parsed backup alarm: ${backupAlarm.description}")
            return backupAlarm
        }
        
        // Fallback to single alarm
        val singleTime = tryParseAlarm(cleanText)
        if (singleTime != null) {
            Log.d("SimpleNlp", "Fallback to single alarm: ${singleTime.format(DateTimeFormatter.ofPattern("h:mm a"))}")
            return AgenticCommand(
                type = CommandType.SINGLE_ALARM,
                alarms = listOf(singleTime),
                description = "Single alarm at ${singleTime.format(DateTimeFormatter.ofPattern("h:mm a"))}"
            )
        }
        
        Log.d("SimpleNlp", "No agentic command found in: '$cleanText'")
        return null
    }

    /**
     * Parse multi-alarm commands like "set 5 alarms in 5 minutes"
     */
    private fun tryParseMultiAlarm(text: String): AgenticCommand? {
        Log.d("SimpleNlp", "Trying to parse multi-alarm from: '$text'")
        val matcher = multiAlarmRegex.matcher(text)
        if (!matcher.find()) {
            Log.d("SimpleNlp", "Multi-alarm regex did not match")
            return null
        }

        val count = matcher.group(1)?.toIntOrNull() ?: return null
        
        Log.d("SimpleNlp", "Multi-alarm match groups: count=$count, group2=${matcher.group(2)}, group3=${matcher.group(3)}, group4=${matcher.group(4)}, group5=${matcher.group(5)}, group6=${matcher.group(6)}")
        
        return when {
            matcher.group(2) != null -> {
                // Pattern: "set 5 alarms in 5 minutes"
                val totalMinutes = matcher.group(2)!!.toInt()
                val startTime = LocalTime.now()
                val interval = if (count > 1) totalMinutes / (count - 1) else totalMinutes
                
                val alarms = (0 until count).map { i ->
                    startTime.plusMinutes((i * interval).toLong())
                }
                
                AgenticCommand(
                    type = CommandType.MULTI_ALARM_SEQUENCE,
                    alarms = alarms,
                    description = "$count alarms over $totalMinutes minutes (${interval}min intervals)",
                    intervals = List(count - 1) { interval }
                )
            }
            matcher.group(3) != null && matcher.group(4) != null -> {
                // Pattern: "set 3 alarms starting at 6 AM with 10 minute gaps"
                val startTimeStr = matcher.group(3)!!
                val interval = matcher.group(4)!!.toInt()
                val startTime = tryParseAbsoluteTime(startTimeStr) ?: return null
                
                val alarms = (0 until count).map { i ->
                    startTime.plusMinutes((i * interval).toLong())
                }
                
                AgenticCommand(
                    type = CommandType.MULTI_ALARM_SEQUENCE,
                    alarms = alarms,
                    description = "$count alarms starting at ${startTime.format(DateTimeFormatter.ofPattern("h:mm a"))} with ${interval}min intervals",
                    intervals = List(count - 1) { interval }
                )
            }
            matcher.group(5) != null && matcher.group(6) != null -> {
                // Pattern: "set 2 alarms 5 mins apart at 10 am"
                val interval = matcher.group(5)!!.toInt()
                val startTimeStr = matcher.group(6)!!
                val startTime = tryParseAbsoluteTime(startTimeStr) ?: return null
                
                val alarms = (0 until count).map { i ->
                    startTime.plusMinutes((i * interval).toLong())
                }
                
                AgenticCommand(
                    type = CommandType.MULTI_ALARM_SEQUENCE,
                    alarms = alarms,
                    description = "$count alarms ${interval} minutes apart starting at ${startTime.format(DateTimeFormatter.ofPattern("h:mm a"))}",
                    intervals = List(count - 1) { interval }
                )
            }
            else -> null
        }
    }

    /**
     * Parse backup alarm patterns like "create backup alarms every 10 minutes"
     */
    private fun tryParseBackupAlarms(text: String): AgenticCommand? {
        val matcher = backupAlarmRegex.matcher(text)
        if (!matcher.find()) return null

        val interval = matcher.group(1)?.toIntOrNull() ?: return null
        val startTimeStr = matcher.group(2)
        
        val startTime = if (startTimeStr != null) {
            tryParseAbsoluteTime(startTimeStr) ?: LocalTime.now().plusMinutes(5)
        } else {
            LocalTime.now().plusMinutes(5) // Default: start in 5 minutes
        }
        
        // Create 3 backup alarms by default
        val count = 3
        val alarms = (0 until count).map { i ->
            startTime.plusMinutes((i * interval).toLong())
        }
        
        return AgenticCommand(
            type = CommandType.BACKUP_ALARMS,
            alarms = alarms,
            description = "$count backup alarms every ${interval} minutes starting at ${startTime.format(DateTimeFormatter.ofPattern("h:mm a"))}",
            intervals = List(count - 1) { interval }
        )
    }

    /**
     * Enhanced single alarm parsing with system time awareness
     */
    fun tryParseAlarm(text: String): LocalTime? {
        val cleanText = text.lowercase(Locale.US)
        
        // First try relative time parsing (with system time awareness)
        val relativeTime = tryParseRelativeTime(cleanText)
        if (relativeTime != null) return relativeTime
        
        // Fallback to absolute time parsing
        return tryParseAbsoluteTime(cleanText)
    }

    /**
     * Enhanced relative time parsing with hours and minutes support
     */
    private fun tryParseRelativeTime(text: String): LocalTime? {
        val matcher = relativeTimeRegex.matcher(text)
        if (!matcher.find()) return null

        val now = LocalTime.now()
        
        return when {
            matcher.group(1) != null -> {
                // Hour pattern: "in 2 hours" or "in 1 hour and 30 minutes"
                val hours = matcher.group(1)!!.toInt()
                val minutes = matcher.group(2)?.toIntOrNull() ?: 0
                now.plusHours(hours.toLong()).plusMinutes(minutes.toLong())
            }
            matcher.group(3) != null -> {
                // Minute pattern: "in 30 minutes"
                val minutes = matcher.group(3)!!.toInt()
                now.plusMinutes(minutes.toLong())
            }
            matcher.group(4) != null -> {
                // "X minutes from now" pattern
                val minutes = matcher.group(4)!!.toInt()
                now.plusMinutes(minutes.toLong())
            }
            else -> null
        }
    }

    /**
     * Enhanced absolute time parsing
     */
    private fun tryParseAbsoluteTime(text: String): LocalTime? {
        val matcher = absoluteTimeRegex.matcher(text)
        if (!matcher.find()) return null

        return when {
            matcher.group(1) != null -> {
                // 12-hour format with optional minutes and am/pm
                val hour12 = matcher.group(1)!!.toInt()
                val minutes = matcher.group(3)?.toIntOrNull() ?: 0
                val ampm = matcher.group(4)
                val hour24 = when {
                    ampm == "pm" && hour12 != 12 -> hour12 + 12
                    ampm == "am" && hour12 == 12 -> 0
                    else -> hour12
                }
                LocalTime.of(hour24, minutes)
            }
            else -> {
                // 24-hour format HH:MM
                val hour24 = matcher.group(5)?.toIntOrNull() ?: return null
                val minutes = matcher.group(6)?.toIntOrNull() ?: 0
                LocalTime.of(hour24, minutes)
            }
        }
    }

    /**
     * System time context for debugging and logging
     */
    fun getCurrentSystemTime(): String {
        val now = LocalDateTime.now()
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    /**
     * Generate intelligent alarm spacing suggestions
     */
    fun suggestOptimalSpacing(count: Int, totalMinutes: Int): List<Int> {
        return when {
            count <= 1 -> emptyList()
            count == 2 -> listOf(totalMinutes)
            else -> {
                val interval = totalMinutes / (count - 1)
                List(count - 1) { interval }
            }
        }
    }
} 