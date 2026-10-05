package br.com.amarelowatch

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StreamServer(
    private val playerHtml: ByteArray,
    private val log: (String) -> Unit,
) {

    private val boundary = "amarelo" + System.nanoTime().toString().takeLast(6)

    private val viewers = CopyOnWriteArrayList<Viewer>()
    private val viewerCount = AtomicInteger(0)

    private val bytesSent = AtomicLong(0)
    private val framesSent = AtomicLong(0)
    private val startedAt = AtomicLong(0L)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var latestFrame: ByteArray? = null

    /**
     * Geometria do quadro atual. O player da TV consulta isso pelo heartbeat
     * para reajustar o aspectRatio quando o celular gira, já que o
     * multipart/x-mixed-replace não carrega metadados por quadro.
     */
    @Volatile
    private var streamWidth = 0

    @Volatile
    private var streamHeight = 0

    @Volatile
    private var streamRotation = 0

    @Volatile
    private var running = false

    val clientCount: Int get() = viewerCount.get()

    val latestJpeg: ByteArray? get() = latestFrame

    fun start(preferredPort: Int, attempts: Int = 12): Int {
        if (running) return port
        var socket: ServerSocket? = null
        var bound = -1
        for (offset in 0 until attempts) {
            val candidate = preferredPort + offset
            if (candidate !in 1..65535) continue
            try {
                socket = ServerSocket(candidate, 8)
                bound = candidate
                break
            } catch (e: IOException) {
                log("Porta $candidate ocupada, tentando a próxima…")
            }
        }
        if (socket == null) throw IOException("Nenhuma porta disponível a partir de $preferredPort")

        serverSocket = socket
        port = bound
        running = true
        startedAt.set(System.nanoTime())

        acceptThread = Thread({ acceptLoop(socket) }, "amarelo-accept").apply {
            isDaemon = true
            start()
        }
        log("Servidor ouvindo na porta $port")
        return port
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        viewers.forEach { it.closeQuietly() }
        viewers.clear()
        viewerCount.set(0)
        latestFrame = null
        streamWidth = 0
        streamHeight = 0
        streamRotation = 0
        bytesSent.set(0)
        framesSent.set(0)
        acceptThread = null
        log("Servidor encerrado")
    }

    fun publish(frame: ByteArray) {
        latestFrame = frame
        if (viewers.isEmpty()) return
        for (viewer in viewers) {
            viewer.offer(frame)
        }
    }

    fun clearStream() {
        latestFrame = null
    }

    fun setGeometry(width: Int, height: Int, rotation: Int) {
        if (streamWidth == width && streamHeight == height && streamRotation == rotation) return
        streamWidth = width
        streamHeight = height
        streamRotation = rotation
        log("Quadro agora em ${width}×${height}")
    }

    fun statsJson(): String {
        val now = System.nanoTime()
        val started = startedAt.get()
        val seconds = if (started == 0L) 0.0 else (now - started) / 1_000_000_000.0
        val totalBytes = bytesSent.get()
        val kbps = if (seconds > 0.5) ((totalBytes * 8.0) / seconds / 1000.0).toInt() else 0
        val fps = if (seconds > 0.5) (framesSent.get() / seconds).toInt() else 0
        return """{"clients":${viewerCount.get()},"streaming":${latestFrame != null},""" +
            """"kbps":$kbps,"fps":$fps,"port":$port,""" +
            """"width":$streamWidth,"height":$streamHeight,"rotation":$streamRotation}"""
    }

    fun resetCounters() {
        bytesSent.set(0)
        framesSent.set(0)
        startedAt.set(System.nanoTime())
    }

    // ---------------------------------------------------------------- accept

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) log("Falha ao aceitar conexão: ${e.message}")
                break
            }
            val worker = Thread({ handle(client) }, "amarelo-client")
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun handle(socket: Socket) {
        var viewer: Viewer? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            socket.receiveBufferSize = 16 * 1024

            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            val request = readRequest(input) ?: return
            log("GET ${request.target} de ${socket.inetAddress.hostAddress}")

            val path = request.target.substringBefore('?').trimEnd('/').lowercase()
                .ifEmpty { "/" }

            when (path) {
                "/", "/index.html", "/player", "/player.html" ->
                    sendPlayer(output)

                "/screen", "/stream", "/mjpeg" -> {
                    val v = Viewer(socket, output)
                    viewer = v
                    viewers.add(v)
                    viewerCount.set(viewers.size)
                    log("TV conectada: ${socket.inetAddress.hostAddress} (${viewers.size} na rede)")
                    streamTo(v)
                }

                "/snapshot.jpg", "/snapshot", "/frame.jpg" -> sendSnapshot(output)

                "/heartbeat", "/status" -> sendText(output, "application/json", statsJson())

                "/favicon.ico" -> sendEmpty(output)

                else -> sendText(
                    output,
                    "text/html; charset=utf-8",
                    "<!doctype html><meta charset=utf-8><title>Amarelo Watch</title>" +
                        "<body style='background:#FFD400;color:#171717;font:16px sans-serif;padding:24px'>" +
                        "<h1>Amarelo Watch</h1><p>Rota não encontrada: ${request.target}</p>",
                )
            }
        } catch (e: SocketTimeoutException) {
            // cliente abandonou a requisição
        } catch (e: Throwable) {
            log("Conexão encerrada: ${e.javaClass.simpleName}")
        } finally {
            viewer?.let {
                viewers.remove(it)
                viewerCount.set(viewers.size)
                log("TV desconectada (${viewers.size} na rede)")
                it.closeQuietly()
            }
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- routes

    private fun sendPlayer(output: OutputStream) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${playerHtml.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append(EXTRA_HEADERS)
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(playerHtml)
        output.flush()
    }

    private fun sendSnapshot(output: OutputStream) {
        var frame = latestFrame
        if (frame == null) {
            val deadline = System.currentTimeMillis() + 4000
            while (frame == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                frame = latestFrame
            }
        }
        if (frame == null) {
            sendText(output, "text/plain; charset=utf-8", "sem imagem ainda")
            return
        }
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${frame.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append(EXTRA_HEADERS)
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(frame)
        output.flush()
    }

    private fun sendText(output: OutputStream, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append(EXTRA_HEADERS)
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun sendEmpty(output: OutputStream) {
        val head = "HTTP/1.1 204 No Content\r\n" + EXTRA_HEADERS + "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun streamTo(viewer: Viewer) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate, max-age=0\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            append("Connection: close\r\n")
            append(EXTRA_HEADERS)
            append("\r\n")
        }
        viewer.writeHead(head.toByteArray(Charsets.US_ASCII))

        val deadline = System.currentTimeMillis() + 8000
        while (viewer.isAlive && latestFrame == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(40)
        }
        viewer.awaitFrame()

        while (viewer.isAlive && running) {
            val frame = viewer.nextFrame() ?: break
            viewer.writePart(frame)
            framesSent.incrementAndGet()
            bytesSent.addAndGet(frame.size.toLong())
        }
    }

    // ----------------------------------------------------------------- utils

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
        }
        return Request(method = parts[0].uppercase(), target = parts[1])
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder(128)
        var read = 0
        while (true) {
            val current = input.read()
            if (current == -1) {
                return if (read == 0) null else builder.toString()
            }
            read++
            if (current == '\n'.code) return builder.toString()
            if (current == '\r'.code) continue
            if (builder.length > 8192) throw IOException("Requisição muito grande")
            builder.append(current.toChar())
        }
    }

    private class Request(val method: String, val target: String)

    private inner class Viewer(
        private val socket: Socket,
        private val output: OutputStream,
    ) {
        private val lock = Object()
        private var pending: ByteArray? = null
        private var closed = false

        val isAlive: Boolean
            get() = !closed && !socket.isClosed && !socket.isOutputShutdown

        fun offer(frame: ByteArray) {
            synchronized(lock) {
                if (closed) return
                pending = frame
                lock.notifyAll()
            }
        }

        fun nextFrame(): ByteArray? {
            synchronized(lock) {
                var spins = 0
                while (pending == null && !closed) {
                    try {
                        lock.wait(1000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                    if (++spins > 600) return null
                }
                val frame = pending
                pending = null
                return frame
            }
        }

        fun awaitFrame() {
            synchronized(lock) {
                var spins = 0
                while (pending == null && !closed && spins < 100) {
                    try {
                        lock.wait(50)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    spins++
                }
            }
        }

        fun writeHead(bytes: ByteArray) = writeRaw(bytes)

        fun writePart(frame: ByteArray) {
            val header = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Type: image/jpeg\r\n")
                append("Content-Length: ").append(frame.size).append("\r\n\r\n")
            }
            try {
                writeRaw(header.toByteArray(Charsets.US_ASCII))
                writeRaw(frame)
                writeRaw(CRLF)
            } catch (e: IOException) {
                markClosed()
                throw e
            }
        }

        private fun writeRaw(bytes: ByteArray) {
            if (closed) throw SocketException("conexão encerrada")
            output.write(bytes)
            output.flush()
        }

        private fun markClosed() {
            closed = true
            synchronized(lock) { lock.notifyAll() }
        }

        fun closeQuietly() {
            markClosed()
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val EXTRA_HEADERS =
            "Access-Control-Allow-Origin: *\r\n" +
                "X-Content-Type-Options: nosniff\r\n"

        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
    }
}
