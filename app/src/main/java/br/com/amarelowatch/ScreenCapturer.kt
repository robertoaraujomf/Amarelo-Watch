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
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
        fun onGeometry(width: Int, height: Int, mode: String, letterboxed: Boolean)
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

    private var initialRotation: Int = 0

    private var scratchPixels: IntArray? = null
    private var scratchBitmap: Bitmap? = null
    private val scratchStream = ByteArrayOutputStream(512 * 1024)

    fun start() {
        if (!running.compareAndSet(false, true)) return
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

    // ------------------------------------------------------------------ loop

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)

        try {
            ensureProjection()
        } catch (t: Throwable) {
            fail("Não foi possível iniciar a captura: ${t.message}")
            return
        }

        var lastRotation = -1
        var lastDesiredWidth = 0
        var lastDesiredHeight = 0
        var openFailures = 0
        var lastDelta = 0

        while (running.get() && !released.get()) {
            val rotation = currentDisplay()?.rotation ?: 0
            val (displayWidth, displayHeight) = displaySize()
            val size = streamSize(displayWidth, displayHeight)

            if (rotation != lastRotation || size.first != lastDesiredWidth ||
                size.second != lastDesiredHeight
            ) {
                lastRotation = rotation
                lastDesiredWidth = size.first
                lastDesiredHeight = size.second
                val display = virtualDisplay
                if (display == null) {
                    if (!openCapture(size.first, size.second)) {
                        openFailures++
                        if (openFailures >= 3) {
                            fail("O sistema não liberou a captura de tela")
                            return
                        }
                        Thread.sleep(400)
                        continue
                    }
                    openFailures = 0
                    initialRotation = rotation
                    lastDelta = 0
                    listener.onGeometry(size.first, size.second, "JPEG ${config.quality}%", false)
                    listener.onLog("Captura em ${size.first}×${size.second} a ${config.fps} fps")
                } else {
                    val delta = (rotation - initialRotation + 4) % 4
                    val active = readerSize()
                    if (delta != lastDelta) {
                        lastDelta = delta
                        if (delta == 0) {
                            listener.onGeometry(
                                active.first, active.second, "JPEG ${config.quality}%", false,
                            )
                        } else {
                            listener.onLog(
                                "O celular foi girado. A transmissão continua em " +
                                    "${active.first}×${active.second} com tarjas; para usar " +
                                    "toda a tela, volte à orientação anterior ou pare e " +
                                    "TRANSMITIR de novo."
                            )
                            listener.onGeometry(
                                active.first, active.second, "tarjas — celular girado", true,
                            )
                        }
                    }
                }
            }

            pump(lastRotation)
        }
    }

    private fun readerSize(): Pair<Int, Int> {
        val r = reader ?: return 0 to 0
        return r.width to r.height
    }

    private fun pump(expectedRotation: Int) {
        var gotFrame = false
        var lastSentAt = 0L
        var lastCheckAt = 0L
        val intervalMs = (config.fpsIntervalNs / 1_000_000L).coerceAtLeast(1)

        while (running.get() && !released.get()) {
            val now = System.nanoTime()
            if (now - lastCheckAt > ORIENTATION_CHECK_NS) {
                lastCheckAt = now
                if ((currentDisplay()?.rotation ?: 0) != expectedRotation) return
            }

            val activeReader = reader ?: return

            if (gotFrame) {
                val waitMs = (config.fpsIntervalNs - (System.nanoTime() - lastSentAt)) / 1_000_000L
                if (waitMs > 0) {
                    try {
                        Thread.sleep(waitMs.coerceAtMost(intervalMs))
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
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
                try {
                    Thread.sleep(4)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
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

            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

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
            scratchBitmap = null
            scratchPixels = null
            true
        } catch (t: Throwable) {
            listener.onLog("Não foi possível abrir a tela virtual: ${t.message}")
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
            .coerceIn(targetShort, 3840)
        return if (displayWidth >= displayHeight) even(targetLong) to even(targetShort)
        else even(targetShort) to even(targetLong)
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value + 1

    companion object {
        private const val TAG = "AmareloWatch"
        private const val ORIENTATION_CHECK_NS = 500_000_000L
    }

    private fun fail(reason: String) {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        listener.onLog(reason)
        listener.onStopped(reason)
    }
}
