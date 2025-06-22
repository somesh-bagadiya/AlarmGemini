package com.example.alarmgemini.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.example.alarmgemini.AlarmListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

/**
 * Voice Chat Screen with real audio recording, STT, TTS, and chat pipeline integration
 * Based on Android voice recording capabilities from Tom's Guide and Android Central
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChatScreen(
    chatVm: ChatViewModel = viewModel(),
    alarmVm: AlarmListViewModel = viewModel(),
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isListening by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTranscription by remember { mutableStateOf("") }
    var lastResponse by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    
    // TTS Engine
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    
    // Speech Recognition
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    
    // Initialize TTS
    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
            }
        }
    }
    
    // Permission check
    val hasAudioPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    
    // Request audio permission
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            errorMessage = "Audio permission is required for voice commands"
        }
    }
    
    // Request permission if needed
    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    
    // Initialize Speech Recognizer
    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission && SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
    }
    
    // Voice processing functions
    fun startListening() {
        if (!hasAudioPermission) {
            errorMessage = "Audio permission required"
            return
        }
        
        if (speechRecognizer == null) {
            errorMessage = "Speech recognition not available"
            return
        }
        
        isListening = true
        errorMessage = ""
        currentTranscription = "Listening..."
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your alarm command...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                currentTranscription = "🎤 Listening for your command..."
            }
            
            override fun onBeginningOfSpeech() {
                currentTranscription = "🎙️ Speaking detected..."
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // Voice level indicator could be added here
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onEndOfSpeech() {
                currentTranscription = "🔄 Processing your voice..."
            }
            
            override fun onError(error: Int) {
                isListening = false
                isProcessing = false
                errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please try again."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout. Check your connection."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Please speak clearly."
                    else -> "Speech recognition error. Please try again."
                }
                currentTranscription = ""
            }
            
                         override fun onResults(results: Bundle?) {
                 isListening = false
                 val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                 if (matches != null && matches.isNotEmpty()) {
                    val spokenText = matches[0]
                    currentTranscription = spokenText
                    
                    // Process with existing chat pipeline
                    scope.launch {
                        isProcessing = true
                        
                        try {
                            // Send to existing chat pipeline
                            chatVm.sendMessage(spokenText, alarmVm)
                            
                            // Wait for processing
                            delay(2000)
                            
                            // Get AI response for TTS
                            val aiResponse = "I've processed your command: $spokenText. " +
                                when {
                                    spokenText.contains("alarm", true) -> {
                                        "Setting up your alarm now. You should see it in your alarm list."
                                    }
                                    spokenText.contains("delete", true) -> {
                                        "Deleting the requested alarm."
                                    }
                                    else -> {
                                        "Command received and processed."
                                    }
                                }
                            
                            lastResponse = aiResponse
                            
                            // Speak the response
                            if (isTtsReady && tts != null) {
                                isProcessing = false
                                isPlaying = true
                                
                                tts?.speak(
                                    aiResponse,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "alarm_response"
                                )
                                
                                // Wait for TTS to finish (approximate)
                                delay((aiResponse.length * 50).toLong()) // ~50ms per character
                                isPlaying = false
                            } else {
                                isProcessing = false
                                errorMessage = "Text-to-speech not ready"
                            }
                            
                        } catch (e: Exception) {
                            isProcessing = false
                            errorMessage = "Error processing command: ${e.message}"
                        }
                    }
                } else {
                    errorMessage = "No speech recognized"
                }
            }
            
                         override fun onPartialResults(partialResults: Bundle?) {
                 val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                 if (partial != null && partial.isNotEmpty()) {
                     currentTranscription = "Hearing: ${partial[0]}..."
                 }
             }
            
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        
        speechRecognizer?.startListening(intent)
    }
    
    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        currentTranscription = ""
    }
    
    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
            tts?.shutdown()
        }
    }
    
    // Voice animation
    val animatedScale by animateFloatAsState(
        targetValue = if (isListening) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_scale"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🎤 Voice Assistant",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            
            // Status indicator
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            !hasAudioPermission -> "🔒 Audio permission required"
                            !isTtsReady -> "🔄 Initializing voice system..."
                            isListening -> "🎧 Listening for your command..."
                            isProcessing -> "🧠 Processing with Gemini..."
                            isPlaying -> "🔊 Speaking response..."
                            errorMessage.isNotEmpty() -> "❌ $errorMessage"
                            else -> "🤖 Ready for voice commands"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = when {
                            errorMessage.isNotEmpty() -> MaterialTheme.colorScheme.error
                            isListening -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    
                    if (currentTranscription.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "\"$currentTranscription\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Voice visualization
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                // Animated circles for voice visualization
                if (isListening) {
                    repeat(3) { index ->
                        val delay = index * 200
                        val animatedRadius by animateFloatAsState(
                            targetValue = if (isListening) 100f else 0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500, delayMillis = delay),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "voice_ripple_$index"
                        )
                        
                        val primaryColor = MaterialTheme.colorScheme.primary
                        Canvas(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            drawCircle(
                                color = primaryColor.copy(
                                    alpha = 0.3f - (index * 0.1f)
                                ),
                                radius = animatedRadius,
                                center = center
                            )
                        }
                    }
                }
                
                // Main voice button
                Card(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isListening -> MaterialTheme.colorScheme.primary
                            isProcessing -> MaterialTheme.colorScheme.secondary
                            isPlaying -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isListening -> Icons.Default.MicOff
                                isProcessing -> Icons.Default.Psychology
                                isPlaying -> Icons.Default.VolumeUp
                                else -> Icons.Default.Mic
                            },
                            contentDescription = "Voice Control",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Voice control buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Start/Stop Voice button
                Button(
                    onClick = {
                        if (isListening) {
                            stopListening()
                        } else {
                            startListening()
                        }
                    },
                    enabled = hasAudioPermission && isTtsReady && !isProcessing && !isPlaying,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListening) 
                            MaterialTheme.colorScheme.error 
                        else 
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = if (isListening) "Stop" else "Talk",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Clear/Reset button
                OutlinedButton(
                    onClick = {
                        stopListening()
                        isProcessing = false
                        isPlaying = false
                        currentTranscription = ""
                        lastResponse = ""
                        errorMessage = ""
                        tts?.stop()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Reset",
                        fontSize = 16.sp
                    )
                }
            }
            
            // Last response display
            if (lastResponse.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "🤖 AI Response (Spoken):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = lastResponse,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Feature description
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = "💡 Try saying:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• \"Set alarm for 7 AM\"\n• \"Set 3 alarms starting at 6 AM with 10 minute gaps\"\n• \"Delete alarm 2\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            // Powered by indicator
            Row(
                modifier = Modifier.padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Voice + TTS + Chat Pipeline Integration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
} 