package br.com.amarelowatch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.DisplayMetrics
import android.view.OrientationEventListener
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captura a tela via MediaProjection e publica JPEG em um fluxo unidirecional.
 *
 * A rotação merece um cuidado especial. O Android proibe criar uma segunda
 * VirtualDisplay na mesma instância de MediaProjection, e um ImageReader não
 * aceita resize. Se mantivéssemos o reader do retrato, o compositor escalaria o
 * conteúdo já girado dentro do buffer antigo e a TV receberia a imagem
 * espremida, com barras pretas gigantes.
 *
 * A solução aqui é criar o reader sempre com o tamanho exato da VirtualDisplay
 * e, no giro, redimensionar a VirtualDisplay e recolocar a superfície dela com
 * setSurface(). Como reader e tela passam a ter as mesmas dimensões, o compositor
 * escreve o conteúdo preenchendo o buffer inteiro: sem escala, sem recorte e sem
 * barras. O resize() e o setSurface() existem a partir da API 26.
 */
class ScreenCapturer(
    private val context: Context,
    private val resultCode: Int,
    private val tokenData: Intent,
    private val config: StreamConfig,
    private val listener: Listener,
) {

    interface Listener {
        fun onFrame(jpeg: ByteArray)
        fun onLog(message: String)
        fun onStopped(reason: String)
        fun onGeometry(width: Int, height: Int, mode: String, rotation: Int)

        /**
         * A mesma instância que autoriza o vídeo também autoriza o áudio
         * (AudioPlaybackCaptureConfiguration). Entregamos a referência para
         * que o AudioCapturer suba no mesmo contexto e liberação.
         */
        fun onProjection(projection: MediaProjection)
    }

    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var captureThread: Thread? = null
    private var projectionCallback: MediaProjection.Callback? = null

    /**
     * O reader nasce com o tamanho da tela virtual, então mudamos os dois
     * juntos. Sinalizamos a rotação por aqui e tratamos no próximo quadro.
     * Marcado pelo OrientationEventListener para responder rápido e conferido
     * contra display.rotation, que é a fonte confiável.
     */
    private val rotationDirty = AtomicBoolean(true)

    private var orientationListener: OrientationEventListener? = null

    private var lastRotation = -1

    /** Dimensões lógicas atuais da VirtualDisplay. */
    private var contentWidth = 0
    private var contentHeight = 0

    private var scratchPixels: IntArray? = null

    private var scratchBitmap: Bitmap? = null
    private val scratchStream = ByteArrayOutputStream(512 * 1024)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startOrientationListener()
        captureThread = Thread({ loop() }, "amarelo-capture").apply {
            isDaemon = true
            start()
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        runCatching { captureThread?.interrupt() }
        captureThread = null
        stopOrientationListener()
        releaseDisplay()
        scratchStream.reset()
        val proj = projection
        val callback = projectionCallback
        if (proj != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            callback != null
        ) {
            runCatching { proj.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { proj?.stop() }
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    // --------------------------------------------------------- orientation

    private fun startOrientationListener() {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) rotationDirty.set(true)
            }
        }
        orientationListener = listener
        // Registrar pode falhar em aparelhos sem sensores; a checagem por
        // display.rotation no laço continua cobrindo esse caso.
        runCatching { listener.enable() }
    }

    private fun stopOrientationListener() {
        runCatching { orientationListener?.disable() }
        orientationListener = null
    }

    // ------------------------------------------------------------------ loop

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)

        try {
            ensureProjection()
        } catch (t: Throwable) {
            fail("Não foi possível iniciar a captura: ${t.message}")
            return
        }

        while (running.get() && !released.get()) {
            val rotation = currentDisplay()?.rotation ?: 0
            val (displayWidth, displayHeight) = displaySize()
            val target = streamSize(displayWidth, displayHeight)

            rotationDirty.set(false)

            if (virtualDisplay == null) {
                if (!openCapture(target.first, target.second)) {
                    if (!sleep(400)) return
                    continue
                }
                lastRotation = rotation
                listener.onGeometry(
                    target.first, target.second, mode(), rotation,
                )
                listener.onLog("Captura em ${target.first}×${target.second} a ${config.fps} fps")
            } else if (rotation != lastRotation ||
                target.first != contentWidth || target.second != contentHeight
            ) {
                applyOrientation(rotation, target)
            }

            pump(rotation)
        }
    }

    /**
     * Aplica a nova orientação. Invertemos largura e altura da VirtualDisplay e
     * reaproveitamos a mesma tela virtual trocando apenas a superfície, de modo
     * que o reader volte a ter exatamente o tamanho do conteúdo.
     */
    private fun applyOrientation(rotation: Int, target: Pair<Int, Int>) {
        val previousRotation = lastRotation
        lastRotation = rotation

        val resized = resizeDisplay(target.first, target.second) &&
            swapReader(target.first, target.second)
        if (resized) {
            contentWidth = target.first
            contentHeight = target.second
        }

        val turned = previousRotation >= 0 && rotation != previousRotation
        if (turned) {
            val label = orientationLabel(rotation)
            if (resized) {
                listener.onLog("Tela em $label: ${contentWidth}×${contentHeight}")
            } else {
                listener.onLog(
                    "Tela em $label. Este Android (API ${Build.VERSION.SDK_INT}) não " +
                        "redimensiona a captura, então o quadro continua em " +
                        "${contentWidth}×${contentHeight} até você reiniciar a transmissão."
                )
            }
        }
        listener.onGeometry(contentWidth, contentHeight, mode(), rotation)
    }

    private fun pump(expectedRotation: Int) {
        var gotFrame = false
        var lastSentAt = 0L
        var lastCheckAt = 0L
        val intervalMs = (config.fpsIntervalNs / 1_000_000L).coerceAtLeast(1)

        while (running.get() && !released.get()) {
            val now = System.nanoTime()

            if (rotationDirty.compareAndSet(true, false)) {
                lastCheckAt = now
                if ((currentDisplay()?.rotation ?: 0) != expectedRotation) return
            } else if (now - lastCheckAt > ORIENTATION_CHECK_NS) {
                lastCheckAt = now
                if ((currentDisplay()?.rotation ?: 0) != expectedRotation) return
            }

            val activeReader = reader ?: return

            if (gotFrame) {
                val waitMs = (config.fpsIntervalNs - (System.nanoTime() - lastSentAt)) / 1_000_000L
                if (waitMs > 0) {
                    if (!sleep(waitMs.coerceAtMost(intervalMs))) return
                    continue
                }
            }

            val image = try {
                activeReader.acquireLatestImage()
            } catch (t: Throwable) {
                if (!running.get() || released.get()) return
                null
            }
            if (image == null) {
                if (!sleep(4)) return
                continue
            }

            val jpeg = try {
                encode(image)
            } catch (t: Throwable) {
                null
            }

            runCatching { image.close() }

            if (jpeg == null) continue

            gotFrame = true
            lastSentAt = System.nanoTime()
            listener.onFrame(jpeg)
        }
    }

    /** Dorme devolvendo false quando a captura foi encerrada no meio do caminho. */
    private fun sleep(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // -------------------------------------------------------------- encoding

    private fun encode(image: Image): ByteArray? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        var pixels = scratchPixels
        if (pixels == null || pixels.size != width * height) {
            pixels = IntArray(width * height)
            scratchPixels = pixels
        }

        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val base = buffer.position()

        if (pixelStride == 4 && rowStride >= width * 4) {
            for (y in 0 until height) {
                buffer.position(base + y * rowStride)
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = rgbaToArgb(buffer.getInt())
                }
            }
        } else {
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(base + y * rowStride)
                val count = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, count)
                val offset = y * width
                var x = 0
                var i = 0
                while (x < width) {
                    pixels[offset + x] = if (i + 3 < count) {
                        ((row[i + 3].toInt() and 0xFF) shl 24) or
                            ((row[i].toInt() and 0xFF) shl 16) or
                            ((row[i + 1].toInt() and 0xFF) shl 8) or
                            (row[i + 2].toInt() and 0xFF)
                    } else {
                        0xFF000000.toInt()
                    }
                    x++
                    i += pixelStride
                }
            }
        }

        var bitmap = scratchBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            scratchBitmap = bitmap
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        scratchStream.reset()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, config.quality, scratchStream)) return null
        return scratchStream.toByteArray()
    }

    private fun rgbaToArgb(v: Int): Int =
        ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v ushr 16) and 0xFF) or (v and 0xFF000000.toInt())

    // ------------------------------------------------------------- projection

    private fun ensureProjection() {
        if (projection != null) return
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val proj = manager.getMediaProjection(resultCode, tokenData)
        projection = proj
        listener.onProjection(proj)

        val thread = HandlerThread("amarelo-media", Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        handlerThread = thread
        val h = Handler(thread.looper)
        handler = h

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (running.get()) fail("A captura foi encerrada pelo sistema")
            }
        }
        projectionCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            proj.registerCallback(callback, h)
        }
    }

    private fun densityDpi(): Int {
        val display = currentDisplay()
        val metrics = DisplayMetrics()
        if (display != null) {
            @Suppress("DEPRECATION")
            run { display.getRealMetrics(metrics) }
        }
        return if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT
    }

    private fun openCapture(width: Int, height: Int): Boolean {
        return try {
            val proj = projection ?: return false
            val density = densityDpi()

            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            val newDisplay = proj.createVirtualDisplay(
                "Amarelo Watch",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                handler,
            )

            virtualDisplay = newDisplay
            reader = newReader
            contentWidth = width
            contentHeight = height
            scratchBitmap = null
            scratchPixels = null
            true
        } catch (t: Throwable) {
            listener.onLog("Não foi possível abrir a tela virtual: ${t.message}")
            false
        }
    }

    /**
     * Troca o reader por um do tamanho novo, reaproveitando a mesma
     * VirtualDisplay. O Android proibe criar uma segunda VirtualDisplay na mesma
     * MediaProjection, mas permite recolocar a superfície: como o reader nasce
     * com o tamanho exato da VirtualDisplay, o compositor escreve o conteúdo
     * preenchendo o buffer inteiro, sem escala e sem barras pretas.
     */
    private fun swapReader(width: Int, height: Int): Boolean {
        val display = virtualDisplay ?: return false
        val old = reader ?: return false
        val fresh = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (t: Throwable) {
            listener.onLog("Não foi possível criar o leitor novo: ${t.message}")
            return false
        }
        return try {
            display.surface = fresh.surface
            reader = fresh
            old.close()
            scratchBitmap = null
            scratchPixels = null
            true
        } catch (t: Throwable) {
            fresh.close()
            listener.onLog("A troca de tela não foi aceita: ${t.message}")
            false
        }
    }

    /**
     * Inverte largura e altura da VirtualDisplay. Disponível a partir da API 26;
     * em versões anteriores devolvemos false e o quadro segue no tamanho original.
     */
    private fun resizeDisplay(width: Int, height: Int): Boolean {
        val display = virtualDisplay ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            display.resize(width, height, densityDpi())
            true
        } catch (t: Throwable) {
            listener.onLog("Não foi possível redimensionar a captura: ${t.message}")
            false
        }
    }

    private fun releaseDisplay() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        scratchBitmap = null
        scratchPixels = null
    }

    // ----------------------------------------------------------------- sizes

    private fun currentDisplay(): android.view.Display? = try {
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    } catch (t: Throwable) {
        null
    }

    private fun displaySize(): Pair<Int, Int> {
        val display = currentDisplay()
        if (display != null) {
            val mode = display.mode
            if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                return orient(mode.physicalWidth, mode.physicalHeight, display.rotation)
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            run { display.getRealMetrics(metrics) }
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return orient(metrics.widthPixels, metrics.heightPixels, display.rotation)
            }
        }
        return 1080 to 1920
    }

    private fun orient(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) height to width
        else width to height

    private fun streamSize(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        val shortSide = minOf(displayWidth, displayHeight).coerceAtLeast(1)
        val longSide = maxOf(displayWidth, displayHeight).coerceAtLeast(1)
        val targetShort = config.shortEdge
        val targetLong = ((targetShort.toLong() * longSide) / shortSide)
            .toInt()
            .coerceIn(targetShort, MAX_LONG_EDGE)
        return if (displayWidth >= displayHeight) even(targetLong) to even(targetShort)
        else even(targetShort) to even(targetLong)
    }

    private fun mode(): String = "JPEG ${config.quality}%"

    private fun orientationLabel(rotation: Int): String = when (rotation) {
        Surface.ROTATION_90 -> "paisagem"
        Surface.ROTATION_180 -> "retrato invertido"
        Surface.ROTATION_270 -> "paisagem invertida"
        else -> "retrato"
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value + 1

    companion object {
        private const val TAG = "AmareloWatch"
        private const val ORIENTATION_CHECK_NS = 500_000_000L
        private const val MAX_LONG_EDGE = 3840
    }

    private fun fail(reason: String) {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        listener.onLog(reason)
        listener.onStopped(reason)
    }
}

