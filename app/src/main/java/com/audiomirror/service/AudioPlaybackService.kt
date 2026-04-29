package com.audiomirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.audiomirror.AudioMode
import com.audiomirror.R
import com.audiomirror.activity.AudioMirrorActivity
import com.audiomirror.service.AudioStreamService.Companion.MODE_TWO_WAY
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

class AudioPlaybackService : Service() {

    private var mainSocket: Socket? = null
    private var twoWayServerSocket: ServerSocket? = null
    private var isRunning = false
    private var audioMode = AudioMode.MIC_ONLY

    private var playThread: Thread? = null
    private var micStreamThread: Thread? = null
    private var twoWayServerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra(EXTRA_HOST) ?: run { stopSelf(); return START_NOT_STICKY }
        val port = intent.getIntExtra(EXTRA_PORT, AudioStreamService.PORT)
        audioMode = AudioMode.fromKey(intent.getStringExtra(EXTRA_MODE))

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(host))
        startPlayback(host, port)
        return START_STICKY
    }

    private fun startPlayback(host: String, port: Int) {
        isRunning = true
        broadcastStatus(STATUS_CONNECTING)
        isServiceRunning = true

        playThread = Thread {
            try {
                val socket = Socket(host, port)
                mainSocket = socket
                Log.d(TAG, "Connected to $host:$port")
                broadcastStatus(STATUS_CONNECTED)

                val inputStream = socket.getInputStream()

                // Read header: mode byte + channel count
                val modeByte = inputStream.read()
                val channelCount = inputStream.read()

                if (channelCount == -1) {
                    Log.e(TAG, "Connection closed before header")
                    return@Thread
                }

                val sampleRate = 44100
                val channelConfig = if (channelCount == 2)
                    AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(4096)

                val usageAttr = if (audioMode == AudioMode.TWO_WAY_MIC)
                    AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
                val contentAttr = if (audioMode == AudioMode.TWO_WAY_MIC)
                    AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usageAttr)
                            .setContentType(contentAttr)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()

                // If two-way, start sending our mic back to Phone A
                if (modeByte == MODE_TWO_WAY.toByte()) {
                    startTwoWayMicServer()
                }

                val buf = ByteArray(bufferSize)
                while (isRunning && !socket.isClosed) {
                    val read = inputStream.read(buf, 0, buf.size)
                    if (read == -1) break
                    if (read > 0) track.write(buf, 0, read)
                }

                track.stop()
                track.release()
            } catch (e: IOException) {
                Log.e(TAG, "Playback error", e)
            } finally {
                safeClose(mainSocket)
                broadcastStatus(STATUS_STOPPED)
                if (isRunning) stopSelf()
            }
        }.also { it.start() }
    }

    // ── Two-way: Phone B starts a server so Phone A can connect and receive B's mic ──

    private fun startTwoWayMicServer() {
        twoWayServerThread = Thread {
            try {
                twoWayServerSocket = ServerSocket(AudioStreamService.TWO_WAY_PORT)
                Log.d(TAG, "Two-way mic server started on port ${AudioStreamService.TWO_WAY_PORT}")
                val socket = twoWayServerSocket?.accept() ?: return@Thread
                Log.d(TAG, "Phone A connected to receive our mic")
                streamMicToSocket(socket)
            } catch (e: IOException) {
                if (isRunning) Log.e(TAG, "twoWayServer error", e)
            }
        }.also { it.start() }
    }

    private fun streamMicToSocket(socket: Socket) {
        micStreamThread = Thread {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(4096)

            @Suppress("MissingPermission")
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, encoding, bufferSize
            )

            try {
                record.startRecording()
                val out = socket.getOutputStream()
                // Send header
                out.write(AudioStreamService.MODE_TWO_WAY.toInt())
                out.write(1)
                out.flush()
                val buf = ByteArray(bufferSize)
                while (isRunning && !socket.isClosed) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) out.write(buf, 0, read)
                }
            } catch (e: Exception) {
                Log.e(TAG, "streamMicToSocket error", e)
            } finally {
                record.stop()
                record.release()
                safeClose(socket)
            }
        }.also { it.start() }
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, status))
    }

    private fun safeClose(socket: Socket?) {
        try { socket?.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        safeClose(mainSocket)
        try { twoWayServerSocket?.close() } catch (_: Exception) {}
        playThread?.interrupt()
        micStreamThread?.interrupt()
        twoWayServerThread?.interrupt()
        broadcastStatus(STATUS_STOPPED)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Audio Playback", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(host: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, AudioMirrorActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_listening_title))
            .setContentText(getString(R.string.notif_listening_desc, host))
            .setSmallIcon(R.drawable.ic_audio_mirror)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.stop), stopPi)
            .setOngoing(true)
            .build()
    }

    companion object {
        @Volatile var isServiceRunning = false
        private const val TAG = "AudioPlaybackService"
        const val CHANNEL_ID = "audio_play_ch"
        const val NOTIFICATION_ID = 7881

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_MODE = "mode"
        const val EXTRA_STATUS = "status"

        const val ACTION_STOP = "com.audiomirror.PLAY_STOP"
        const val ACTION_STATUS = "com.audiomirror.PLAY_STATUS"

        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_STOPPED = "stopped"
    }
}
