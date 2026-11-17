package com.acite.swdw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.acite.swdw.MainActivity
import com.acite.swdw.R
import com.acite.swdw.client.SwdClientEngine
import com.acite.swdw.model.ConnectionState
import kotlinx.coroutines.*

class AudioStreamService : Service() {

    companion object {
        const val CHANNEL_ID = "swd_audio_stream"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.acite.swdw.action.START"
        const val ACTION_TOGGLE_PLAY = "com.acite.swdw.action.TOGGLE_PLAY"
        const val ACTION_TOGGLE_MIC = "com.acite.swdw.action.TOGGLE_MIC"
        const val ACTION_STOP = "com.acite.swdw.action.STOP"

        val engine = SwdClientEngine()

        fun start(context: Context) {
            val intent = Intent(context, AudioStreamService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioStreamService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var notifyObserverJob: Job? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var coverBitmap: Bitmap? = null
    private var playbackStartTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        coverBitmap = createCoverBitmap()

        mediaSession = MediaSessionCompat(this, "SWD_MediaSession").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    playbackStartTime = System.currentTimeMillis()
                    engine.startPlayback()
                    updateNotification()
                }

                override fun onPause() {
                    engine.stopPlayback()
                    updateNotification()
                }

                override fun onStop() {
                    engine.disconnect()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    if (action == ACTION_TOGGLE_MIC) {
                        engine.toggleMic()
                        updateNotification()
                    }
                }
            })
        }

        notifyObserverJob = serviceScope.launch {
            var lastPlaying = engine.isPlaying
            var lastMic = engine.isMicStreaming
            var lastLatency = engine.latencyMs
            var lastState = engine.connectionState
            var lastHeartbeatText = engine.heartbeatElapsedText

            while (isActive) {
                delay(1000)
                val currentHeartbeatText = engine.heartbeatElapsedText
                if (lastPlaying != engine.isPlaying ||
                    lastMic != engine.isMicStreaming ||
                    lastLatency != engine.latencyMs ||
                    lastState != engine.connectionState ||
                    lastHeartbeatText != currentHeartbeatText
                ) {
                    lastPlaying = engine.isPlaying
                    lastMic = engine.isMicStreaming
                    lastLatency = engine.latencyMs
                    lastState = engine.connectionState
                    lastHeartbeatText = currentHeartbeatText
                    updateNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> {
                if (!engine.isPlaying) {
                    playbackStartTime = System.currentTimeMillis()
                }
                engine.togglePlayback()
                updateNotification()
            }
            ACTION_TOGGLE_MIC -> {
                engine.toggleMic()
                updateNotification()
            }
            ACTION_STOP -> {
                engine.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }

        return START_STICKY
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePlayIntent = Intent(this, AudioStreamService::class.java).apply {
            action = ACTION_TOGGLE_PLAY
        }
        val togglePlayPendingIntent = PendingIntent.getService(
            this, 1, togglePlayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleMicIntent = Intent(this, AudioStreamService::class.java).apply {
            action = ACTION_TOGGLE_MIC
        }
        val toggleMicPendingIntent = PendingIntent.getService(
            this, 2, toggleMicIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 3, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = engine.isPlaying
        if (isPlaying && playbackStartTime == 0L) {
            playbackStartTime = System.currentTimeMillis()
        } else if (!isPlaying) {
            playbackStartTime = 0L
        }

        val heartbeatStr = if (engine.lastHeartbeatElapsedSecs >= 0) {
            engine.heartbeatElapsedText
        } else {
            "待检测"
        }

        val elapsedSecs = if (isPlaying && playbackStartTime > 0L) {
            (System.currentTimeMillis() - playbackStartTime) / 1000L
        } else 0L
        val streamTimeStr = "%02d:%02d".format(elapsedSecs / 60, elapsedSecs % 60)

        val title = when (engine.connectionState) {
            ConnectionState.CONNECTED -> "Arch Linux 实时混音 (${engine.sampleRate / 1000}kHz)"
            ConnectionState.OFFLINE -> "⚠️ 服务端已掉线 (Arch Linux)"
            ConnectionState.CONNECTING -> "正在连接 Arch Linux 宿主..."
            ConnectionState.DISCONNECTED -> "Arch Linux 待连接"
            ConnectionState.ERROR -> "UDP 直连异常"
        }

        val artist = when (engine.connectionState) {
            ConnectionState.CONNECTED -> "心跳: $heartbeatStr · 延时: ${engine.latencyMs}ms · 已推流: $streamTimeStr"
            ConnectionState.OFFLINE -> "心跳超时 (>4s) · 上次心跳: $heartbeatStr"
            ConnectionState.CONNECTING -> "正在向服务端 192.168.50.1 建立直连..."
            ConnectionState.DISCONNECTED -> "未连接 · 点击播放开启直连"
            ConnectionState.ERROR -> "UDP 直连异常"
        }

        val album = when {
            engine.isPlaying && engine.isMicStreaming -> "回放中 + 移动麦克风推流 (48kHz)"
            engine.isPlaying -> "正在低延迟播放 PipeWire 系统混音 | 双声道立体声"
            engine.isMicStreaming -> "移动麦克风推流中 (48kHz)"
            else -> "已连接待机 · 点击下方按钮开启收听"
        }

        val playbackPosition = if (isPlaying && playbackStartTime > 0L) {
            (System.currentTimeMillis() - playbackStartTime).coerceAtLeast(0L)
        } else 0L

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                playbackPosition,
                1.0f
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_TOGGLE_MIC,
                    if (engine.isMicStreaming) "关闭麦克风" else "开启麦克风",
                    if (engine.isMicStreaming) R.drawable.ic_mic_off else R.drawable.ic_mic
                ).build()
            )
            .build()
        mediaSession.setPlaybackState(playbackState)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, coverBitmap)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0L) // 0 for Live Stream
            .build()
        mediaSession.setMetadata(metadata)

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(stopPendingIntent)

        val playActionTitle = if (engine.isPlaying) "暂停" else "播放"
        val micActionTitle = if (engine.isMicStreaming) "关麦" else "开麦"

        val micAction = NotificationCompat.Action(
            if (engine.isMicStreaming) R.drawable.ic_mic_off else R.drawable.ic_mic,
            micActionTitle,
            toggleMicPendingIntent
        )
        val playAction = NotificationCompat.Action(
            if (engine.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
            playActionTitle,
            togglePlayPendingIntent
        )
        val stopAction = NotificationCompat.Action(
            R.drawable.ic_stop,
            "断开",
            stopPendingIntent
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(coverBitmap)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("心跳: $heartbeatStr")
            .setContentIntent(openAppPendingIntent)
            .setStyle(mediaStyle)
            .addAction(micAction)       // index 0
            .addAction(playAction)      // index 1
            .addAction(stopAction)      // index 2
            .setColor(0xFFFF8DA1.toInt()) // KataHana Sakura Pink accent
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createCoverBitmap(): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Gradient background: Dark Slate & Arch Midnight Blue
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(
                    Color.parseColor("#0C1118"),
                    Color.parseColor("#121B27"),
                    Color.parseColor("#173753")
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Arch Blue circular ambient glow behind logo
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size * 0.5f, size * 0.42f, size * 0.45f,
                intArrayOf(Color.parseColor("#661793D1"), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(size * 0.5f, size * 0.42f, size * 0.45f, glowPaint)

        // Inner rounded border
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#331793D1")
        }
        val rect = RectF(20f, 20f, size - 20f, size - 20f)
        canvas.drawRoundRect(rect, 32f, 32f, strokePaint)

        // Draw Official Arch Linux Logo in center
        try {
            val archRaw = BitmapFactory.decodeResource(resources, R.drawable.ic_arch_logo)
            if (archRaw != null) {
                val logoWidth = 230f
                val logoHeight = 230f
                val left = (size - logoWidth) / 2f
                val top = size * 0.42f - logoHeight / 2f
                val destRect = RectF(left, top, left + logoWidth, top + logoHeight)
                val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(archRaw, null, destRect, bmpPaint)
            }
        } catch (_: Exception) {}

        // Arch Linux Brand Label
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1793D1")
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.15f
            setShadowLayer(14f, 0f, 3f, Color.parseColor("#991793D1"))
        }
        canvas.drawText("ARCH LINUX", size * 0.5f, size * 0.76f, titlePaint)

        // Subtitle badge "PIPEWIRE STREAM · 96kHz"
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B3FFFFFF")
            textSize = 20f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.1f
        }
        canvas.drawText("PIPEWIRE STREAM · 96kHz", size * 0.5f, size * 0.84f, badgePaint)

        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SWD 实时音频传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SWD PipeWire 音频流后台服务"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swd:AudioStreamWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12 hours timeout
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notifyObserverJob?.cancel()
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (::mediaSession.isInitialized) {
                mediaSession.isActive = false
                mediaSession.release()
            }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
