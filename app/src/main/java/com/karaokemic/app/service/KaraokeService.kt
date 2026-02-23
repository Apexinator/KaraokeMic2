package com.karaokemic.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.karaokemic.app.audio.AudioProcessor
import com.karaokemic.app.bluetooth.BluetoothAudioRouter
import com.karaokemic.app.ui.MainActivity
import kotlin.concurrent.thread

class KaraokeService : Service() {

    companion object {
        const val CHANNEL_ID = "KaraokeChannel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.karaokemic.START"
        const val ACTION_STOP = "com.karaokemic.STOP"

        // Broadcast actions to update UI
        const val BROADCAST_STATUS = "com.karaokemic.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
        const val EXTRA_DB_LEVEL = "db_level"
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@KaraokeService
    }

    private val binder = LocalBinder()
    val audioProcessor = AudioProcessor()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var bluetoothRouter: BluetoothAudioRouter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var isRunning = false

    private val sampleRate = 44100
    private val bufferSize by lazy {
        AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096) * 2
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bluetoothRouter = BluetoothAudioRouter(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startKaraoke()
            ACTION_STOP -> {
                stopKaraoke()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun startKaraoke() {
        if (isRunning) return

        val bt = bluetoothRouter ?: return

        if (!bt.isBluetoothA2dpConnected()) {
            broadcastError("No Bluetooth speaker connected.\nPlease connect a BT speaker first.")
            return
        }

        startForeground(NOTIF_ID, buildNotification("🎤 Karaoke is active"))

        // Acquire wake lock to keep CPU alive
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KaraokeMic::WakeLock")
            .apply { acquire(3 * 60 * 60 * 1000L) } // 3 hours max

        bt.setupForKaraoke()

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)         // Routes to A2DP BT
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO) // Stereo for BT speaker
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                broadcastError("Microphone unavailable. Check app permissions.")
                stopSelf()
                return
            }

            isRunning = true
            audioRecord?.startRecording()
            audioTrack?.play()

            broadcastStatus("running")
            startAudioLoop()

        } catch (e: SecurityException) {
            broadcastError("Microphone permission denied.")
            stopSelf()
        } catch (e: Exception) {
            broadcastError("Audio init failed: ${e.message}")
            stopSelf()
        }
    }

    private fun startAudioLoop() {
        thread(name = "KaraokeAudioThread", isDaemon = true) {
            val inBuf = ShortArray(bufferSize / 2)
            val vuInterval = sampleRate / 30 // ~30fps VU meter updates
            var vuCounter = 0
            var vuPeak = 0f

            Log.d("Karaoke", "Audio loop started, bufferSize=$bufferSize")

            while (isRunning) {
                val read = audioRecord?.read(inBuf, 0, inBuf.size) ?: break
                if (read <= 0) continue

                // Process audio (reverb, echo, gain)
                val processed = audioProcessor.process(inBuf, read)

                // Convert mono → stereo for Bluetooth A2DP
                val stereo = monoToStereo(processed, read)
                audioTrack?.write(stereo, 0, stereo.size)

                // VU meter calculation
                vuCounter += read
                val peak = processed.take(read).maxOfOrNull { kotlin.math.abs(it.toInt()) }?.toFloat() ?: 0f
                if (peak > vuPeak) vuPeak = peak

                if (vuCounter >= vuInterval) {
                    val db = if (vuPeak > 0) 20 * kotlin.math.log10(vuPeak / 32767f) else -60f
                    broadcastDbLevel(db)
                    vuCounter = 0
                    vuPeak = 0f
                }
            }

            Log.d("Karaoke", "Audio loop ended")
        }
    }

    fun stopKaraoke() {
        isRunning = false
        audioProcessor.reset()

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null

        bluetoothRouter?.release()
        wakeLock?.release()
        wakeLock = null

        broadcastStatus("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun isRunning() = isRunning

    private fun monoToStereo(mono: ShortArray, size: Int): ShortArray {
        val stereo = ShortArray(size * 2)
        for (i in 0 until size) {
            stereo[i * 2] = mono[i]
            stereo[i * 2 + 1] = mono[i]
        }
        return stereo
    }

    private fun buildNotification(text: String = "Karaoke Mic Running"): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, KaraokeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎤 Karaoke Mic")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Karaoke Mic Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Karaoke mic background service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
        })
    }

    private fun broadcastError(msg: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, "error")
            putExtra(EXTRA_ERROR, msg)
        })
    }

    private fun broadcastDbLevel(db: Float) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, "vu")
            putExtra(EXTRA_DB_LEVEL, db)
        })
    }

    override fun onDestroy() {
        stopKaraoke()
        super.onDestroy()
    }
}
