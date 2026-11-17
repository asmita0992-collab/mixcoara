package com.mixcoara.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class MixcoaraForegroundService : Service {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    constructor() : super()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        acquireLocks()
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Mixcoara"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Transferencia segura en curso…"
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
        val max = intent?.getIntExtra(EXTRA_MAX, 100) ?: 100

        val notification = buildNotification(title, text, progress, max)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock =
                    pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Mixcoara::TransferWakeLock",
                    ).apply {
                        setReferenceCounted(false)
                    }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L) // Hasta 6 horas por operación
            }
        }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                wifiLock =
                    wm.createWifiLock(lockMode, "Mixcoara::TransferWifiLock").apply {
                        setReferenceCounted(false)
                    }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val progressChannel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Transferencias de Mixcoara en curso",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Muestra el avance en vivo de subidas y descargas cifradas."
                    setShowBadge(false)
                }
            val resultChannel =
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    "Resultados de transferencias de Mixcoara",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Avisa cuando concluye o falla una subida o descarga."
                }
            manager.createNotificationChannel(progressChannel)
            manager.createNotificationChannel(resultChannel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int,
    ): Notification {
        val openAppIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (progress >= 0) {
            builder.setProgress(max, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "mixcoara_transfers"
        private const val RESULT_CHANNEL_ID = "mixcoara_transfer_results"
        private const val NOTIFICATION_ID = 7001
        private const val RESULT_NOTIFICATION_ID = 7002
        private const val ACTION_UPDATE = "com.mixcoara.app.ACTION_UPDATE"
        private const val ACTION_STOP = "com.mixcoara.app.ACTION_STOP"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_MAX = "extra_max"

        fun update(context: Context, title: String, text: String, progress: Int = -1, max: Int = 100) {
            runCatching {
                val intent =
                    Intent(context, MixcoaraForegroundService::class.java).apply {
                        action = ACTION_UPDATE
                        putExtra(EXTRA_TITLE, title)
                        putExtra(EXTRA_TEXT, text)
                        putExtra(EXTRA_PROGRESS, progress)
                        putExtra(EXTRA_MAX, max)
                    }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent =
                    Intent(context, MixcoaraForegroundService::class.java).apply {
                        action = ACTION_STOP
                    }
                context.startService(intent)
            }
        }

        fun notifyFinished(context: Context, title: String, message: String, isError: Boolean = false) {
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val resultChannel =
                        NotificationChannel(
                            RESULT_CHANNEL_ID,
                            "Resultados de transferencias de Mixcoara",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        )
                    manager.createNotificationChannel(resultChannel)
                }
                val openAppIntent =
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                val pendingIntent =
                    PendingIntent.getActivity(
                        context,
                        1,
                        openAppIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val notification =
                    NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                        .setSmallIcon(
                            if (isError) android.R.drawable.stat_notify_error
                            else android.R.drawable.stat_sys_upload_done,
                        )
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build()
                manager.notify(RESULT_NOTIFICATION_ID, notification)
            }
        }
    }
}

