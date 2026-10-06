package br.com.amarelowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamServerTest {

    private fun buildServer(): StreamServer =
        StreamServer("<html>player</html>".toByteArray(), log = {})

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val current = input.read()
            if (current == -1) return if (builder.isEmpty()) null else builder.toString()
            if (current == '\n'.code) return builder.toString()
            if (current != '\r'.code) builder.append(current.toChar())
        }
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val lines = ArrayList<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            lines.add(line)
        }
        return lines.associate {
            val idx = it.indexOf(':')
            it.substring(0, idx).trim().lowercase() to it.substring(idx + 1).trim()
        }
    }

    private fun get(path: String, port: Int): Pair<Map<String, String>, String> {
        var lastError: IOException? = null
        repeat(3) {
            try {
                return getOnce(path, port)
            } catch (error: IOException) {
                lastError = error
                Thread.sleep(150)
            }
        }
        throw lastError!!
    }

    private fun getOnce(path: String, port: Int): Pair<Map<String, String>, String> {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
            val output = socket.getOutputStream()
            output.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            output.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val status = readLine(input)
            val headers = readHeaders(input)
            val body = input.readBytes().toString(Charsets.UTF_8)
            return headers to "$status\n$body"
        }
    }

    @Test
    fun servePlayerHtml() {
        val server = buildServer()
        val port = server.start(19_500)
        try {
            val (headers, body) = get("/", port)
            assertTrue(body.startsWith("HTTP/1.1 200 OK"))
            assertEquals("text/html; charset=utf-8", headers["content-type"])
            assertEquals("19", headers["content-length"])
            assertTrue(body.endsWith("<html>player</html>"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun serveHeartbeatJson() {
        val server = buildServer()
        val port = server.start(19_501)
        try {
            val (headers, body) = get("/heartbeat", port)
            assertEquals("application/json", headers["content-type"])
            assertTrue(body.contains("\"clients\":0"))
            assertTrue(body.contains("\"port\":$port"))
            assertTrue(body.contains("\"width\":0"))
            assertTrue(body.contains("\"rotation\":0"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun heartbeatReportsGeometryForTvPlayer() {
        val server = buildServer()
        val port = server.start(19_521)
        try {
            server.setGeometry(1600, 720, 1)
            val (_, body) = get("/heartbeat", port)
            assertTrue(body.contains("\"width\":1600"))
            assertTrue(body.contains("\"height\":720"))
            assertTrue(body.contains("\"rotation\":1"))

            // Girar de novo precisa aparecer no heartbeat: e o player da TV
            // que usa isso para reajustar o aspectRatio.
            server.setGeometry(720, 1600, 0)
            val (_, after) = get("/heartbeat", port)
            assertTrue(after.contains("\"width\":720"))
            assertTrue(after.contains("\"height\":1600"))
            assertTrue(after.contains("\"rotation\":0"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun streamMultipartJpeg() {
        val server = buildServer()
        val port = server.start(19_502)
        val payload = ByteArray(64) { 0x41 }
        val received = CountDownLatch(2)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())

        try {
            val reader = Thread {
                try {
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 8000
                        socket.getOutputStream().apply {
                            write("GET /screen HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                            flush()
                        }
                        val input = BufferedInputStream(socket.getInputStream())
                        assertTrue(readLine(input).orEmpty().contains("200 OK"))
                        val contentType = readHeaders(input)["content-type"].orEmpty()
                        assertTrue(
                            "content-type inesperado: $contentType",
                            contentType.startsWith("multipart/x-mixed-replace; boundary="),
                        )
                        val boundary = contentType.substringAfter("boundary=")

                        repeat(2) {
                            assertEquals("--" + boundary, readLine(input).orEmpty().trim())
                            val headers = readHeaders(input)
                            assertEquals("image/jpeg", headers["content-type"])
                            val length = headers["content-length"]!!.toInt()
                            assertEquals(payload.size, length)
                            val buffer = ByteArray(length)
                            var read = 0
                            while (read < length) {
                                val n = input.read(buffer, read, length - read)
                                if (n < 0) throw IllegalStateException("stream encerrado")
                                read += n
                            }
                            assertEquals('A', buffer[0].toInt().toChar())
                            assertEquals("", readLine(input).orEmpty())
                            received.countDown()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
            reader.isDaemon = true
            reader.start()

            val publisher = Thread {
                repeat(12) {
                    Thread.sleep(120)
                    server.publish(payload)
                }
            }
            publisher.isDaemon = true
            publisher.start()

            assertTrue("não recebeu os quadros: $errors", received.await(10, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }

    @Test
    fun snapshotReturnsLastFrame() {
        val server = buildServer()
        val port = server.start(19_503)
        val payload = ByteArray(32) { 0x42 }
        try {
            server.publish(payload)
            val (headers, body) = get("/snapshot.jpg", port)
            assertEquals("image/jpeg", headers["content-type"])
            assertEquals("32", headers["content-length"])
            assertTrue(body.endsWith(String(CharArray(32) { 'B' })))
        } finally {
            server.stop()
        }
    }

    @Test
    fun fallsBackToNextPortWhenBusy() {
        val first = buildServer()
        val busy = first.start(19_504)
        val second = buildServer()
        try {
            val chosen = second.start(19_504)
            assertEquals(busy + 1, chosen)
        } finally {
            second.stop()
            first.stop()
        }
    }

    @Test
    fun stopsCleanly() {
        val server = buildServer()
        server.start(19_505)
        server.publish(ByteArray(8))
        server.stop()
        assertEquals(0, server.clientCount)
        assertEquals(null, server.latestJpeg)
    }

    @Test
    fun bufferDoesNotLeakJunk() {
        val out = ByteArrayOutputStream()
        assertEquals(0, out.size())
    }

    @Test
    fun serveAudioStreamChunked() {
        val server = buildServer()
        val port = server.start(19_506)
        val payload = ByteArray(48) { 0x7F }
        val received = CountDownLatch(2)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())

        try {
            val reader = Thread {
                try {
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 8000
                        socket.getOutputStream().apply {
                            write("GET /audio.aac HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                            flush()
                        }
                        val input = BufferedInputStream(socket.getInputStream())
                        assertTrue(readLine(input).orEmpty().contains("200 OK"))
                        val headers = readHeaders(input)
                        assertEquals("audio/aac", headers["content-type"])
                        // Sem Content-Length: o fluxo nunca termina, e o
                        // navegador precisa disso para tocar em vez de esperar
                        // o arquivo inteiro.
                        assertEquals(null, headers["content-length"])
                        assertEquals("chunked", headers["transfer-encoding"])

                        repeat(2) {
                            val size = readLine(input).orEmpty().trim().toInt(16)
                            val data = ByteArray(size)
                            var lido = 0
                            while (lido < size) {
                                val n = input.read(data, lido, size - lido)
                                if (n < 0) throw IllegalStateException("stream encerrado")
                                lido += n
                            }
                            assertEquals(payload.size, size)
                            assertEquals(0x7F, data[0].toInt())
                            assertEquals("", readLine(input).orEmpty())
                            received.countDown()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
            reader.isDaemon = true
            reader.start()

            val publisher = Thread {
                repeat(12) {
                    Thread.sleep(120)
                    server.publishAudio(payload)
                }
            }
            publisher.isDaemon = true
            publisher.start()

            assertTrue("não recebeu os quadros de áudio: $errors", received.await(10, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }

    @Test
    fun heartbeatReportsAudioState() {
        val server = buildServer()
        val port = server.start(19_507)
        try {
            val (_, before) = get("/heartbeat", port)
            assertTrue(before.contains("\"audioReady\":false"))
            assertTrue(before.contains("\"audio\":false"))

            server.publishAudio(ByteArray(16))
            val (_, depois) = get("/heartbeat", port)
            assertTrue(depois.contains("\"audioReady\":true"))
            assertTrue(depois.contains("\"audio\":true"))

            // Sem áudio o player precisa saber para parar de pedir.
            server.setAudioAvailable(false)
            val (_, semAudio) = get("/heartbeat", port)
            assertTrue(semAudio.contains("\"audioReady\":false"))
            assertTrue(semAudio.contains("\"audio\":false"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun audioAloneDoesNotFakeVideo() {
        val server = buildServer()
        val port = server.start(19_508)
        try {
            server.publishAudio(ByteArray(16))
            val (_, body) = get("/heartbeat", port)
            // O vídeo continua desligado: o player não pode achar que há tela.
            assertTrue(body.contains("\"streaming\":false"))
        } finally {
            server.stop()
        }
    }
}
