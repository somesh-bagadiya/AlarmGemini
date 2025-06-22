package com.example.alarmgemini.chat

import java.time.LocalDateTime

enum class Sender { USER, AI }

data class ChatMessage(
    val sender: Sender,
    val text: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
) 