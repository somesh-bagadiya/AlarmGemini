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
            
            // For agentic commands, try immediate parsing for UI feedback
            val agenticCommand = SimpleNlp.parseAgenticCommand(text)
            if (agenticCommand != null) {
                return agenticCommand.alarms.firstOrNull()
            }
            
            // Return parsed time for immediate UI updates if it's a simple alarm set command
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
            // First, try to execute agentic command immediately
            val agenticCommand = SimpleNlp.parseAgenticCommand(text)
            if (agenticCommand != null) {
                Log.d("ChatViewModel", "Found agentic command in user input: ${agenticCommand.type}")
                val createdIds = mutableListOf<Int>()
                
                agenticCommand.alarms.forEach { alarmTime ->
                    val id = alarmVm.addAlarm(alarmTime, emptyList())
                    createdIds.add(id)
                }
                
                val alarmSummary = agenticCommand.alarms.mapIndexed { index, time ->
                    "#${createdIds[index]}: ${time.format(DateTimeFormatter.ofPattern("h:mm a"))}"
                }.joinToString(", ")
                
                return@withContext "Perfect! I understand you want: ${agenticCommand.description}\n\n" +
                    "🤖 **Agentic Result:**\n" +
                    "✅ Created ${agenticCommand.alarms.size} alarms\n" +
                    "📋 ${agenticCommand.description}\n" +
                    "🔔 Alarms: $alarmSummary"
            }
            
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
                
                For simple single alarm commands, use: ACTION: SET_ALARM [time]
                For complex commands, I will handle them automatically.
                For deletions, use: ACTION: DELETE_ALARM [id] or ACTION: DELETE_ALL
                
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
            
            // Parse AI response for actions, passing the original user command
            return@withContext executeActionWithUserCommand(aiResponse, text, alarmVm)
            
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Direct Gemini API error", e)
            throw e
        }
    }
    
    private fun executeActionWithUserCommand(
        aiResponse: String, 
        userCommand: String,
        alarmVm: com.example.alarmgemini.AlarmListViewModel
    ): String {
        Log.d("ChatViewModel", "Executing action for AI response: $aiResponse")
        Log.d("ChatViewModel", "Original user command: $userCommand")
        
        // Fallback to traditional action parsing in AI response
        val actionPattern = Regex("ACTION: (\\w+)(?:\\s+(.+))?")
        val actionMatch = actionPattern.find(aiResponse)
        
        if (actionMatch != null) {
            val action = actionMatch.groupValues[1]
            val parameter = actionMatch.groupValues[2]
            
            Log.d("ChatViewModel", "Found action: $action with parameter: $parameter")
            
            when (action) {
                "SET_ALARM" -> {
                    val time = SimpleNlp.tryParseAlarm(parameter.ifEmpty { userCommand })
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
                "DELETE_ALL" -> {
                    val deletedCount = alarmVm.deleteAllAlarms()
                    return aiResponse.replace(actionPattern, "\n\n✅ Deleted all $deletedCount alarms")
                }
            }
        }
        
        // If no action found, try to parse the user command for simple commands
        val simpleTime = SimpleNlp.tryParseAlarm(userCommand)
        if (simpleTime != null && userCommand.lowercase().contains("set") && userCommand.lowercase().contains("alarm")) {
            val id = alarmVm.addAlarm(simpleTime, emptyList())
            val timeStr = simpleTime.format(DateTimeFormatter.ofPattern("h:mm a"))
            return aiResponse + "\n\n🤖 **Agentic Result:**\n✅ Alarm set for $timeStr (ID: #$id)"
        }
        
        // Return AI response without action markers
        return aiResponse.replace(actionPattern, "").trim()
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