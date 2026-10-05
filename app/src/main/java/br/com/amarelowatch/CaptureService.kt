package br.com.amarelowatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.atomic.AtomicLong

class CaptureService : Service(), ScreenCapturer.Listener {

    private var capturer: ScreenCapturer? = null
    private var server: StreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val framesTotal = AtomicLong(0)
    private val bytesTotal = AtomicLong(0)

    private var lastFrames = 0L
    private var lastBytes = 0L
    private var lastSampleAt = 0L

    private val statsTick = object : Runnable {
        override fun run() {
            if (!Bridge.current().streaming) return
            val now = System.currentTimeMillis()
            val seconds = ((now - lastSampleAt).coerceAtLeast(500L)) / 1000f
            val fps = ((framesTotal.get() - lastFrames) / seconds).toInt()
            val kbps = (((bytesTotal.get() - lastBytes) * 8L) / seconds / 1000L).toInt()
            lastFrames = framesTotal.get()
            lastBytes = bytesTotal.get()
            lastSampleAt = now
            Bridge.update {
                it.copy(
                    clients = server?.clientCount ?: 0,
                    fps = fps,
                    kbps = kbps,
                )
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(getString(R.string.notif_stop))
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val token = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode == Int.MIN_VALUE || token == null) {
            Bridge.log("Token de captura ausente")
            Bridge.update { it.copy(starting = false, error = "Permissão de captura não concedida") }
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )

        acquireWakeLock()

        val config = Settings.load(this)

        if (server == null) {
            val created = StreamServer(loadPlayerHtml(), log = { message -> postToUi { Bridge.log(message) } })
            val port = try {
                created.start(config.port)
            } catch (t: Throwable) {
                Bridge.log("Falha no servidor: ${t.message}")
                postToUi {
                    Bridge.update { state -> state.copy(starting = false, error = "Sem porta TCP livre") }
                }
                shutdown("Servidor não iniciou")
                return START_NOT_STICKY
            }
            server = created
            val url = Net.url(Net.localIp(this) ?: "IP-DO-CELULAR", port)
            postToUi { Bridge.update { it.copy(port = port, url = url) } }
            Bridge.log("Endereço: $url")
        }

        val activeServer = server ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        val newCapturer = ScreenCapturer(
            context = this,
            resultCode = resultCode,
            tokenData = token,
            config = config,
            listener = this,
        )
        capturer = newCapturer

        framesTotal.set(0)
        bytesTotal.set(0)
        lastFrames = 0
        lastBytes = 0
        lastSampleAt = System.currentTimeMillis()

        postToUi { Bridge.update { it.copy(starting = false, streaming = true, error = null) } }
        Bridge.log("Transmissão iniciada")

        newCapturer.start()
        mainHandler.removeCallbacks(statsTick)
        mainHandler.postDelayed(statsTick, 1000)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------ listener

    override fun onFrame(jpeg: ByteArray) {
        framesTotal.incrementAndGet()
        bytesTotal.addAndGet(jpeg.size.toLong())
        server?.publish(jpeg)
    }

    override fun onLog(message: String) {
        postToUi { Bridge.log(message) }
    }

    override fun onStopped(reason: String) {
        postToUi {
            Bridge.update { it.copy(streaming = false, clients = 0, error = reason) }
            if (reason.isNotBlank()) shutdown(reason)
        }
    }

    override fun onGeometry(width: Int, height: Int, mode: String, rotation: Int) {
        // O player da TV lê width/height do heartbeat para ajustar o aspectRatio.
        server?.setGeometry(width, height, rotation)
        postToUi {
            Bridge.update {
                it.copy(width = width, height = height, mode = mode, rotation = rotation)
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun postToUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun shutdown(reason: String?) {
        mainHandler.removeCallbacks(statsTick)
        capturer?.release()
        capturer = null
        server?.stop()
        server = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        reason?.let { Bridge.log(it) }
        Bridge.update { it.copy(streaming = false, starting = false, clients = 0) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AmareloWatch::stream").apply {
            setReferenceCounted(false)
            runCatching { acquire(MAX_WAKELOCK) }
        }
    }

    private fun loadPlayerHtml(): ByteArray =
        runCatching { assets.open("player.html").use { it.readBytes() } }
            .getOrElse { FALLBACK_HTML.toByteArray(Charsets.UTF_8) }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolha_play)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(Bridge.current().url ?: getString(R.string.app_tagline))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "amarelo_stream"
        private const val NOTIFICATION_ID = 4711
        private const val MAX_WAKELOCK = 8L * 60 * 60 * 1000

        const val ACTION_STOP = "br.com.amarelowatch.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        private val FALLBACK_HTML =
            "<!doctype html><meta charset=utf-8><title>Amarelo Watch</title>" +
                "<body style='background:#FFD400;color:#171717;font:20px sans-serif'>" +
                "<h1>Amarelo Watch</h1><p>Player não encontrado no pacote.</p>"
    }
}
