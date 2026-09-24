package com.example.aiwebtabautomator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutomationService : Service() {
    companion object {
        private const val CHANNEL_ID = "automation_bridge"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, AutomationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inboxJob: Job? = null
    private var httpServer: LocalHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        BridgeRepository.ensureDirectories(this)

        if (AppPrefs.isHttpEnabled(this)) {
            httpServer = LocalHttpServer(onCommand = ::acceptCommand).also {
                it.start(serviceScope)
            }
        }
        startInboxMonitorIfEnabled()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AppPrefs.isHttpEnabled(this) && httpServer == null) {
            httpServer = LocalHttpServer(onCommand = ::acceptCommand).also {
                it.start(serviceScope)
            }
        }
        if (AppPrefs.isFileBridgeEnabled(this)) startInboxMonitorIfEnabled()
        return START_STICKY
    }

    private fun startInboxMonitorIfEnabled() {
        if (inboxJob?.isActive == true) return
        inboxJob = serviceScope.launch {
            while (isActive) {
                FileBridge.consumeInbox(this@AutomationService, ::acceptCommand)
                // The HTTP path is event driven; file fallback is deliberately low frequency.
                delay(3_000)
            }
        }
    }

    private fun acceptCommand(command: AutomationCommand) {
        CommandStore.enqueue(this, command)
        AutomationBus.emit(command)
    }

    override fun onDestroy() {
        inboxJob?.cancel()
        httpServer?.stop()
        httpServer = null
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Automation bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Receives local Termux commands and queues automation work"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Local Termux bridge is running")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
