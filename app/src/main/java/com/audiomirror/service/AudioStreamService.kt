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
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.audiomirror.AudioMode
import com.audiomirror.R
import com.audiomirror.activity.AudioMirrorActivity
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

class AudioStreamService : Service() {

    private var serverSocket: ServerSocket? = null
    private var twoWayServerSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var mediaProjection: MediaProjection? = null
    private var audioManager: AudioManager? = null
    private var originalVolume = -1

    private var serverThread: Thread? = null
    private var streamThread: Thread? = null
    private var twoWayServerThread: Thread? = null
    private var twoWayPlayThread: Thread? = null

    private var isRunning = false
    private var audioMode = AudioMode.MIC_ONLY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Already running — ignore duplicate start
        if (isRunning) return START_STICKY

        audioMode = AudioMode.fromKey(intent?.getStringExtra(EXTRA_MODE))
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        else null

        if (resultCode != -1 && data != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Mute source phone if earbuds mode
        if (audioMode == AudioMode.MUTE_SOURCE) {
            originalVolume = audioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager!!.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Save running state
        isServiceRunning = true
        saveState(intent?.getStringExtra(EXTRA_QR_CONTENT) ?: "")

        startServer()
        return START_STICKY
    }

    private fun startServer() {
        isRunning = true
        broadcastStatus(STATUS_WAITING)

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Server started port=$PORT mode=$audioMode")

                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        clientSocket = socket
                        Log.d(TAG, "Client connected: ${socket.inetAddress.hostAddress}")
                        broadcastStatus(STATUS_CONNECTED)
                        handleClient(socket)
                    } catch (e: IOException) {
                        if (isRunning) Log.e(TAG, "Accept error", e)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Server error", e)
            } finally {
                broadcastStatus(STATUS_STOPPED)
            }
        }.also { it.start() }
    }

    private fun handleClient(socket: Socket) {
        when (audioMode) {
            AudioMode.DEVICE_ONLY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null)
                    streamSystemAudio(socket)
                else streamMicAudio(socket)
            }
            AudioMode.MIC_ONLY -> streamMicAudio(socket)
            AudioMode.MUTE_SOURCE -> streamMicAudio(socket) // source muted, stream mic
            AudioMode.DEVICE_AND_MIC -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null)
                    streamMixedAudio(socket)
                else streamMicAudio(socket)
            }
            AudioMode.TWO_WAY_MIC -> handleTwoWayMic(socket)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun streamSystemAudio(socket: Socket) {
        val projection = mediaProjection ?: return
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(4096)

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(encoding).setSampleRate(sampleRate)
                .setChannelMask(channelConfig).build())
            .setBufferSizeInBytes(bufferSize).build()

        streamThread = Thread {
            try {
                record.startRecording()
                val out = socket.getOutputStream()
                out.write(MODE_STREAM_ONLY.toInt()); out.write(2); out.flush()
                val buf = ByteArray(bufferSize)
                while (isRunning && !socket.isClosed) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) out.write(buf, 0, read)
                }
            } catch (e: Exception) { Log.e(TAG, "streamSystem error", e) }
            finally { record.stop(); record.release(); safeClose(socket); broadcastStatus(STATUS_WAITING) }
        }.also { it.start() }
    }

    private fun streamMicAudio(socket: Socket) {
        streamThread = Thread {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(4096)
            @Suppress("MissingPermission")
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, encoding, bufferSize)
            try {
                record.startRecording()
                val out = socket.getOutputStream()
                out.write(MODE_STREAM_ONLY.toInt()); out.write(1); out.flush()
                val buf = ByteArray(bufferSize)
                while (isRunning && !socket.isClosed) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) out.write(buf, 0, read)
                }
            } catch (e: Exception) { Log.e(TAG, "streamMic error", e) }
            finally { record.stop(); record.release(); safeClose(socket); broadcastStatus(STATUS_WAITING) }
        }.also { it.start() }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun streamMixedAudio(socket: Socket) {
        val projection = mediaProjection ?: run { streamMicAudio(socket); return }
        val sampleRate = 44100
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding).coerceAtLeast(4096)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()

        val deviceRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(bufferSize).build()

        @Suppress("MissingPermission")
        val micRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding, bufferSize)

        streamThread = Thread {
            try {
                deviceRecord.startRecording(); micRecord.startRecording()
                val out = socket.getOutputStream()
                out.write(MODE_STREAM_ONLY.toInt()); out.write(1); out.flush()
                val devBuf = ByteArray(bufferSize); val micBuf = ByteArray(bufferSize); val mixBuf = ByteArray(bufferSize)
                while (isRunning && !socket.isClosed) {
                    val devRead = deviceRecord.read(devBuf, 0, bufferSize)
                    val micRead = micRecord.read(micBuf, 0, bufferSize)
                    val toMix = minOf(devRead, micRead).takeIf { it > 0 } ?: continue
                    mixPcm16(devBuf, micBuf, mixBuf, toMix)
                    out.write(mixBuf, 0, toMix)
                }
            } catch (e: Exception) { Log.e(TAG, "streamMixed error", e) }
            finally {
                deviceRecord.stop(); deviceRecord.release()
                micRecord.stop(); micRecord.release()
                safeClose(socket); broadcastStatus(STATUS_WAITING)
            }
        }.also { it.start() }
    }

    private fun handleTwoWayMic(socket: Socket) {
        streamThread = Thread {
            val sampleRate = 44100
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            @Suppress("MissingPermission")
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            try {
                record.startRecording()
                val out = socket.getOutputStream()
                out.write(MODE_TWO_WAY.toInt()); out.write(1); out.flush()
                val buf = ByteArray(bufferSize)
                while (isRunning && !socket.isClosed) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) out.write(buf, 0, read)
                }
            } catch (e: Exception) { Log.e(TAG, "twoWay out error", e) }
            finally { record.stop(); record.release(); safeClose(socket); broadcastStatus(STATUS_WAITING) }
        }.also { it.start() }

        twoWayServerThread = Thread {
            try {
                twoWayServerSocket = ServerSocket(TWO_WAY_PORT)
                val backSocket = twoWayServerSocket?.accept() ?: return@Thread
                playIncomingAudio(backSocket.getInputStream())
            } catch (e: IOException) { if (isRunning) Log.e(TAG, "twoWay back error", e) }
        }.also { it.start() }
    }

    private fun playIncomingAudio(inputStream: InputStream) {
        twoWayPlayThread = Thread {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build()
            try {
                track.play()
                inputStream.read(); inputStream.read() // skip header
                val buf = ByteArray(bufferSize)
                while (isRunning) {
                    val read = inputStream.read(buf, 0, buf.size)
                    if (read == -1) break
                    if (read > 0) track.write(buf, 0, read)
                }
            } catch (e: Exception) { Log.e(TAG, "playIncoming error", e) }
            finally { track.stop(); track.release() }
        }.also { it.start() }
    }

    private fun mixPcm16(buf1: ByteArray, buf2: ByteArray, out: ByteArray, size: Int) {
        var i = 0
        while (i < size - 1) {
            val s1 = ((buf1[i + 1].toInt() shl 8) or (buf1[i].toInt() and 0xFF)).toShort().toInt()
            val s2 = ((buf2[i + 1].toInt() shl 8) or (buf2[i].toInt() and 0xFF)).toShort().toInt()
            val mixed = (s1 + s2).coerceIn(-32768, 32767)
            out[i] = (mixed and 0xFF).toByte()
            out[i + 1] = ((mixed shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun saveState(qrContent: String) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean(PREF_SERVICE_RUNNING, true)
            .putString(PREF_QR_CONTENT, qrContent)
            .apply()
    }

    private fun clearState() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean(PREF_SERVICE_RUNNING, false)
            .putString(PREF_QR_CONTENT, "")
            .apply()
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, status))
    }

    private fun safeClose(socket: Socket?) { try { socket?.close() } catch (_: Exception) {} }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isServiceRunning = false

        // Restore volume if muted
        if (audioMode == AudioMode.MUTE_SOURCE && originalVolume >= 0) {
            try { audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) } catch (_: Exception) {}
        }

        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { twoWayServerSocket?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        streamThread?.interrupt()
        twoWayServerThread?.interrupt()
        twoWayPlayThread?.interrupt()
        serverThread?.interrupt()
        clearState()
        broadcastStatus(STATUS_STOPPED)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Audio Mirror Streaming", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, AudioStreamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, AudioMirrorActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_streaming_title))
            .setContentText(getString(R.string.notif_streaming_desc))
            .setSmallIcon(R.drawable.ic_audio_mirror)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.stop), stopPi)
            .setOngoing(true).build()
    }

    companion object {
        private const val TAG = "AudioStreamService"
        const val PORT = 45679
        const val TWO_WAY_PORT = 45680
        const val CHANNEL_ID = "audio_stream_ch"
        const val NOTIFICATION_ID = 7880

        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_STATUS = "status"
        const val EXTRA_QR_CONTENT = "qr_content"

        const val ACTION_STOP = "com.audiomirror.STREAM_STOP"
        const val ACTION_STATUS = "com.audiomirror.STREAM_STATUS"

        const val STATUS_WAITING = "waiting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_STOPPED = "stopped"

        const val MODE_STREAM_ONLY: Byte = 0x01
        const val MODE_TWO_WAY: Byte = 0x02

        const val PREF_SERVICE_RUNNING = "stream_service_running"
        const val PREF_QR_CONTENT = "stream_qr_content"

        // Static flag — survives activity recreation
        @Volatile var isServiceRunning = false
    }
}
