package com.example.alarmgemini.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.example.alarmgemini.BuildConfig
import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.example.alarmgemini.ai.SimpleNlp
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        Log.d("ChatViewModel", "=== ChatViewModel INIT START ===")
        try {
            // Check API key first
            val apiKey = BuildConfig.GEMINI_API_KEY
            Log.d("ChatViewModel", "API key length: ${apiKey.length}")
            
            if (apiKey.isBlank()) {
                Log.e("ChatViewModel", "API key is blank!")
                _messages.value = listOf(
                    ChatMessage(
                        Sender.AI, 
                        "⚠️ Gemini API key not configured. Please add GEMINI_API_KEY to gradle.properties"
                    )
                )
            } else {
                _messages.value = listOf(
                    ChatMessage(
                        Sender.AI, 
                        "Hello! I'm your AI alarm assistant powered by Gemini via direct REST API. I can help you set, modify, and delete alarms using natural language. Try saying 'set alarm for 7 AM' or 'delete alarm 3'."
                    )
                )
                Log.d("ChatViewModel", "Direct Gemini API integration initialized successfully")
            }
            
            Log.d("ChatViewModel", "=== ChatViewModel INIT SUCCESS ===")
        } catch (e: Exception) {
            Log.e("ChatViewModel", "=== ChatViewModel INIT FAILED ===", e)
            _messages.value = listOf(
                ChatMessage(Sender.AI, "Error initializing chat: ${e.message}. Using fallback mode.")
            )
        }
    }

    /**
     * Sends a user message and processes alarm commands using direct Gemini API.
     */
    fun sendMessage(
        text: String,
        alarmVm: com.example.alarmgemini.AlarmListViewModel
    ): LocalTime? {
        Log.d("ChatViewModel", "sendMessage called with: $text")
        if (text.isBlank()) return null
        
        try {
            // Add user message
            _messages.value = _messages.value + ChatMessage(Sender.USER, text)
            
            viewModelScope.launch {
                try {
                    val reply = processWithDirectGeminiAPI(text, alarmVm)
                    _messages.value = _messages.value + ChatMessage(Sender.AI, reply)
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error in sendMessage", e)
                    // Fallback to basic processing
                    val fallbackReply = processCommandSafe(text, alarmVm)
                    _messages.value = _messages.value + ChatMessage(
                        Sender.AI,
                        "⚠️ Gemini API error: ${e.message}\n\nFallback: $fallbackReply"
                    )
                }
            }
            
            // Return parsed time for immediate UI updates if it's an alarm set command
            return if (text.lowercase().contains("set") && text.lowercase().contains("alarm")) {
                SimpleNlp.tryParseAlarm(text)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Fatal error in sendMessage", e)
            return null
        }
    }
    
    private suspend fun processWithDirectGeminiAPI(
        text: String,
        alarmVm: com.example.alarmgemini.AlarmListViewModel
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return@withContext "Gemini API key not configured"
        
        try {
            // Enhanced agentic prompt with system time awareness
            val currentTime = SimpleNlp.getCurrentSystemTime()
            val prompt = """
                You are an advanced AI assistant for an intelligent alarm app. 
                
                Current system time: $currentTime
                User said: "$text"
                Current alarms: ${getCurrentAlarmsInfo(alarmVm)}
                
                I can handle complex commands like:
                • Multi-alarm creation: "set 5 alarms in 5 minutes"
                • Backup alarms: "create backup alarms every 10 minutes"  
                • Relative timing: "set alarm in 2 hours and 30 minutes"
                • Absolute timing: "set 3 alarms starting at 6 AM with 10 minute gaps"
                • Smart scheduling with conflict detection and optimization
                
                Analyze the user's request and respond naturally. If setting alarms, be specific about:
                - How many alarms will be created
                - What times they'll be set for
                - Any intelligent spacing or intervals
                
                For complex multi-alarm commands, use these action formats:
                ACTION: SET_MULTI_ALARM [count] [start_time] [interval_minutes]
                ACTION: SET_BACKUP_ALARMS [interval_minutes] [start_time?]
                ACTION: SET_ALARM [time]
                ACTION: DELETE_ALARM [id]
                ACTION: DELETE_ALL_EXCEPT_LAST
                ACTION: DELETE_ALL
                ACTION: NONE
                
                Be conversational and explain what you're doing intelligently.
            """.trimIndent()
            
            // Create request body for Gemini REST API
            val requestBody = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }
            
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw IOException("Unexpected response code: ${response.code}")
            }
            
            val responseBody = response.body?.string() ?: throw IOException("Empty response body")
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            
            val candidates = jsonResponse["candidates"]?.jsonArray
            val firstCandidate = candidates?.firstOrNull()?.jsonObject
            val content = firstCandidate?.get("content")?.jsonObject
            val parts = content?.get("parts")?.jsonArray
            val firstPart = parts?.firstOrNull()?.jsonObject
            val aiResponse = firstPart?.get("text")?.jsonPrimitive?.content
                ?: "I couldn't process that request."
            
            Log.d("ChatViewModel", "Direct Gemini API response: $aiResponse")
            
            // Parse AI response for actions
            return@withContext executeAction(aiResponse, alarmVm)
            
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Direct Gemini API error", e)
            throw e
        }
    }
    
    private fun executeAction(aiResponse: String, alarmVm: com.example.alarmgemini.AlarmListViewModel): String {
        // First try agentic command parsing
        val agenticCommand = SimpleNlp.parseAgenticCommand(aiResponse)
        if (agenticCommand != null) {
            return executeAgenticCommand(agenticCommand, alarmVm, aiResponse)
        }
        
        // Fallback to traditional action parsing
        val actionPattern = Regex("ACTION: (\\w+)(?:\\s+(.+))?")
        val actionMatch = actionPattern.find(aiResponse)
        
        if (actionMatch != null) {
            val action = actionMatch.groupValues[1]
            val parameter = actionMatch.groupValues[2]
            
            when (action) {
                "SET_MULTI_ALARM" -> {
                    return handleMultiAlarmAction(parameter, alarmVm, aiResponse, actionPattern)
                }
                "SET_BACKUP_ALARMS" -> {
                    return handleBackupAlarmsAction(parameter, alarmVm, aiResponse, actionPattern)
                }
                "SET_ALARM" -> {
                    val time = SimpleNlp.tryParseAlarm(parameter)
                    if (time != null) {
                        val id = alarmVm.addAlarm(time, emptyList())
                        val timeStr = time.format(DateTimeFormatter.ofPattern("h:mm a"))
                        return aiResponse.replace(actionPattern, "\n\n✅ Alarm set for $timeStr (ID: #$id)")
                    }
                }
                "DELETE_ALARM" -> {
                    val id = parameter.toIntOrNull()
                    if (id != null) {
                        val success = alarmVm.deleteAlarmById(id)
                        val status = if (success) "✅ Deleted" else "❌ Not found"
                        return aiResponse.replace(actionPattern, "\n\n$status alarm #$id")
                    }
                }
                "DELETE_ALL_EXCEPT_LAST" -> {
                    val deletedCount = alarmVm.deleteAllExceptLast()
                    return aiResponse.replace(actionPattern, "\n\n✅ Deleted $deletedCount alarms, kept the most recent one")
                }
                "DELETE_ALL" -> {
                    val deletedCount = alarmVm.deleteAllAlarms()
                    return aiResponse.replace(actionPattern, "\n\n✅ Deleted all $deletedCount alarms")
                }
            }
        }
        
        // Return AI response without action markers
        return aiResponse.replace(actionPattern, "").trim()
    }

    private fun executeAgenticCommand(
        command: SimpleNlp.AgenticCommand, 
        alarmVm: com.example.alarmgemini.AlarmListViewModel, 
        aiResponse: String
    ): String {
        val createdIds = mutableListOf<Int>()
        
        command.alarms.forEach { alarmTime ->
            val id = alarmVm.addAlarm(alarmTime, emptyList())
            createdIds.add(id)
        }
        
        val alarmSummary = command.alarms.mapIndexed { index, time ->
            "#${createdIds[index]}: ${time.format(DateTimeFormatter.ofPattern("h:mm a"))}"
        }.joinToString(", ")
        
        val actionMarker = Regex("ACTION: \\w+.*")
        return aiResponse.replace(actionMarker, "") + 
            "\n\n🤖 **Agentic Result:**\n" +
            "✅ Created ${command.alarms.size} alarms\n" +
            "📋 ${command.description}\n" +
            "🔔 Alarms: $alarmSummary"
    }

    private fun handleMultiAlarmAction(
        parameter: String, 
        alarmVm: com.example.alarmgemini.AlarmListViewModel, 
        aiResponse: String, 
        actionPattern: Regex
    ): String {
        val parts = parameter.split(" ")
        if (parts.size >= 3) {
            val count = parts[0].toIntOrNull() ?: return aiResponse
            val startTimeStr = parts[1]
            val interval = parts[2].toIntOrNull() ?: return aiResponse
            
            val startTime = SimpleNlp.tryParseAlarm(startTimeStr) ?: return aiResponse
            val createdIds = mutableListOf<Int>()
            
            repeat(count) { i ->
                val alarmTime = startTime.plusMinutes((i * interval).toLong())
                val id = alarmVm.addAlarm(alarmTime, emptyList())
                createdIds.add(id)
            }
            
            val alarmSummary = createdIds.mapIndexed { index, id ->
                val time = startTime.plusMinutes((index * interval).toLong())
                "#$id: ${time.format(DateTimeFormatter.ofPattern("h:mm a"))}"
            }.joinToString(", ")
            
            return aiResponse.replace(actionPattern, "\n\n✅ Created $count alarms with ${interval}min intervals\n🔔 $alarmSummary")
        }
        return aiResponse
    }

    private fun handleBackupAlarmsAction(
        parameter: String, 
        alarmVm: com.example.alarmgemini.AlarmListViewModel, 
        aiResponse: String, 
        actionPattern: Regex
    ): String {
        val parts = parameter.split(" ")
        if (parts.isNotEmpty()) {
            val interval = parts[0].toIntOrNull() ?: return aiResponse
            val startTime = if (parts.size > 1) {
                SimpleNlp.tryParseAlarm(parts[1]) ?: LocalTime.now().plusMinutes(5)
            } else {
                LocalTime.now().plusMinutes(5)
            }
            
            val count = 3 // Default backup count
            val createdIds = mutableListOf<Int>()
            
            repeat(count) { i ->
                val alarmTime = startTime.plusMinutes((i * interval).toLong())
                val id = alarmVm.addAlarm(alarmTime, emptyList())
                createdIds.add(id)
            }
            
            val alarmSummary = createdIds.mapIndexed { index, id ->
                val time = startTime.plusMinutes((index * interval).toLong())
                "#$id: ${time.format(DateTimeFormatter.ofPattern("h:mm a"))}"
            }.joinToString(", ")
            
            return aiResponse.replace(actionPattern, "\n\n✅ Created $count backup alarms every ${interval} minutes\n🔔 $alarmSummary")
        }
        return aiResponse
    }
    
    private fun getCurrentAlarmsInfo(alarmVm: com.example.alarmgemini.AlarmListViewModel): String {
        val alarms = alarmVm.alarms.value
        return if (alarms.isEmpty()) {
            "No alarms set"
        } else {
            alarms.joinToString(", ") { 
                "#${it.id}: ${it.time.format(DateTimeFormatter.ofPattern("h:mm a"))} ${if (it.enabled) "(ON)" else "(OFF)"}"
            }
        }
    }
    
    private fun processCommandSafe(
        text: String,
        alarmVm: com.example.alarmgemini.AlarmListViewModel
    ): String {
        try {
            val lowerText = text.lowercase()
            
            // First try agentic command parsing
            val agenticCommand = SimpleNlp.parseAgenticCommand(text)
            if (agenticCommand != null) {
                val createdIds = mutableListOf<Int>()
                agenticCommand.alarms.forEach { alarmTime ->
                    val id = alarmVm.addAlarm(alarmTime, emptyList())
                    createdIds.add(id)
                }
                
                val alarmSummary = agenticCommand.alarms.mapIndexed { index, time ->
                    "#${createdIds[index]}: ${time.format(DateTimeFormatter.ofPattern("h:mm a"))}"
                }.joinToString(", ")
                
                return "🤖 **Fallback Agentic Processing:**\n" +
                    "✅ Created ${agenticCommand.alarms.size} alarms\n" +
                    "📋 ${agenticCommand.description}\n" +
                    "🔔 Alarms: $alarmSummary"
            }
            
            // Handle delete/remove alarm commands
            if (lowerText.contains("delete alarm") || lowerText.contains("remove alarm")) {
                val idMatch = Regex("\\b(\\d+)\\b").find(text)
                return if (idMatch != null) {
                    val id = idMatch.value.toInt()
                    val success = alarmVm.deleteAlarmById(id)
                    if (success) {
                        "I've deleted alarm #$id for you. ✅"
                    } else {
                        "I couldn't find alarm #$id. Please check the alarm list and try again."
                    }
                } else {
                    "Please specify which alarm to delete by its ID number (e.g., 'delete alarm 1')."
                }
            }
            
            // Handle set alarm commands
            if (lowerText.contains("set") && lowerText.contains("alarm")) {
                val parsedTime = SimpleNlp.tryParseAlarm(text)
                if (parsedTime != null) {
                    val id = alarmVm.addAlarm(parsedTime, emptyList())
                    return "I've set an alarm for ${parsedTime.format(DateTimeFormatter.ofPattern("h:mm a"))} (ID: #$id). 🔔"
                } else {
                    return "I couldn't understand the time. Please try saying something like 'set alarm for 7:30 AM' or 'set 5 alarms in 5 minutes'."
                }
            }
            
            // General conversation with agentic capabilities mentioned
            return "I understand you said: \"$text\". I have agentic capabilities and can handle complex commands like:\n" +
                "• 'set 5 alarms in 5 minutes' - Multi-alarm sequences\n" +
                "• 'create backup alarms every 10 minutes' - Intelligent backup scheduling\n" +
                "• 'set alarm in 2 hours' - Relative time with system awareness\n" +
                "• 'delete alarm 1' - Individual alarm management"
            
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error in processCommandSafe", e)
            return "I encountered an error processing your request: ${e.message}"
        }
    }
} 