package com.aurora.music.webserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aurora.music.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuroraWebServerService : Service() {

    companion object {
        private const val TAG = "AuroraWebServerService"
        const val CHANNEL_ID = "aurora_web_server_channel"
        const val NOTIFICATION_ID = 5001
        const val ACTION_START = "com.aurora.music.START_WEB_SERVER"
        const val ACTION_STOP = "com.aurora.music.STOP_WEB_SERVER"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _serverUrl = MutableStateFlow<String?>(null)
        val serverUrl = _serverUrl.asStateFlow()

        private val _activePort = MutableStateFlow(8080)
        val activePort = _activePort.asStateFlow()

        var activeServer: AuroraWebServer? = null
            private set
    }

    private var storageBridge: WebServerStorageBridge? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopServer()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                (applicationContext as? com.aurora.music.AuroraApplication)?.container?.settingsStore?.setWebServerEnabled(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            startServer()
        }

        return START_STICKY
    }

    private fun startServer() {
        if (activeServer?.isServerRunning() == true) return

        acquireLocks()

        val bridge = WebServerStorageBridge(applicationContext)
        storageBridge = bridge
        val server = AuroraWebServer(applicationContext, bridge, port = 8080)
        val started = server.start()

        if (started) {
            activeServer = server
            _isRunning.value = true
            _activePort.value = server.activePort
            val ip = NetworkUtils.getLocalIpAddress(applicationContext)
            val token = bridge.getAccessToken()
            val url = if (token.isNotBlank()) "http://$ip:${server.activePort}/?token=$token" else "http://$ip:${server.activePort}"
            _serverUrl.value = url

            val notification = buildNotification(url)
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Aurora Web Server Service started: $url")
        } else {
            releaseLocks()
            stopSelf()
        }
    }

    private fun stopServer() {
        activeServer?.stop()
        activeServer = null
        _isRunning.value = false
        _serverUrl.value = null
        releaseLocks()
        Log.i(TAG, "Aurora Web Server Service stopped")
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aurora:WebServerWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours max safeguard
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Aurora:WebServerWifiLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake/wifi locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (_: Exception) {}
    }

    private fun buildNotification(url: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AuroraWebServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aurora Web Studio Active")
            .setContentText("Stream audio at $url")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aurora Web Streaming Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the active Wi-Fi streaming server notification and URL"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
