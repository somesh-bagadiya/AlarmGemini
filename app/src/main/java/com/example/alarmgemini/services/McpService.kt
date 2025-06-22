package com.example.alarmgemini.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.alarmgemini.R

/**
 * Foreground service that will eventually host the MCP stdio server so an external LLM host can
 * call `create_alarm` and `delete_alarm` at runtime. For now this starts a foreground notification
 * and leaves a TODO for the actual server bootstrap once the MCP Kotlin SDK is available in the
 * project dependencies.
 */
class McpService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AlarmGemini Tools")
            .setContentText("MCP tools available for LLM host")
            .setOngoing(true)
            .build()
        // 0 is fine, we don't use the ID elsewhere
        startForeground(FOREGROUND_ID, notification)

        // TODO: integrate MCP stdio transport once dependency is added
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // TODO: stop MCP server when implemented
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AlarmGemini Tools",
                NotificationManager.IMPORTANCE_MIN
            )
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "alarmgemini_mcp"
        private const val FOREGROUND_ID = 42
    }
} 