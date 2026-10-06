package br.com.amarelowatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O cabeçalho ADTS é o contrato com o navegador da TV: se um bit estiver
 * errado, o player simplesmente não toca nada e não há pista do motivo.
 */
class AdtsWriterTest {

    private fun bits(payload: ByteArray, objectType: Int = 2, rate: Int = 48000, channels: Int = 2): ByteArray {
        val out = ByteArray(AdtsWriter.HEADER_SIZE + payload.size)
        AdtsWriter.writeHeader(out, 0, payload.size, objectType, rate, channels)
        payload.copyInto(out, AdtsWriter.HEADER_SIZE)
        return out
    }

    @Test
    fun syncWordAndNoCrc() {
        val header = bits(ByteArray(4))
        assertEquals(0xFF.toByte(), header[0])
        // 1111 (sync) | 0 (MPEG-4) | 00 (layer) | 1 (sem CRC)
        assertEquals(0xF1.toByte(), header[1])
    }

    @Test
    fun profileAndSampleRate() {
        // AAC-LC => profile 1; 48000 Hz => índice 3.
        val header = bits(ByteArray(4))
        assertEquals((1 shl 6) or (3 shl 2) or 0, header[2].toInt() and 0xFF)
    }

    @Test
    fun channelConfigInBothBytes() {
        // O canal é um número de 3 bits que atravessa a fronteira dos bytes:
        // estéreo = 010 e mono = 001, então mudam os bits 2 e 7.
        val stereo = bits(ByteArray(4), channels = 2)
        assertEquals(0, stereo[2].toInt() and 0x01)
        assertEquals(0, stereo[3].toInt() and 0x80)

        val mono = bits(ByteArray(4), channels = 1)
        assertEquals(0, mono[2].toInt() and 0x01)
        assertEquals(0x80, mono[3].toInt() and 0x80)

        // Cinco canais (5.1) exercita o bit alto do byte 3.
        val surround = bits(ByteArray(4), channels = 6)
        assertEquals(1, surround[2].toInt() and 0x01)
        assertEquals(0, surround[3].toInt() and 0x80)
    }

    @Test
    fun frameLengthSpansThirteenBits() {
        // 7 bytes de cabeçalho + 4000 de carga = 4007.
        val frame = bits(ByteArray(4000))
        val length = ((frame[3].toInt() and 0x03) shl 11) or
            ((frame[4].toInt() and 0xFF) shl 3) or
            ((frame[5].toInt() and 0xFF) shr 5)
        assertEquals(4007, length)
        assertEquals(4007, frame.size)
    }

    @Test
    fun variableBufferFullnessMarksVbr() {
        val header = bits(ByteArray(64))
        assertEquals(0x1F, header[5].toInt() and 0x1F)
        assertEquals(0xFC.toByte(), header[6])
    }

    @Test
    fun payloadIsCopiedAfterHeader() {
        val payload = ByteArray(3) { (it + 1).toByte() }
        val frame = bits(payload)
        assertEquals(1.toByte(), frame[AdtsWriter.HEADER_SIZE])
        assertEquals(3.toByte(), frame[AdtsWriter.HEADER_SIZE + 2])
    }

    @Test
    fun sampleRateTable() {
        assertEquals(3, AdtsWriter.sampleRateIndex(48000))
        assertEquals(4, AdtsWriter.sampleRateIndex(44100))
        assertEquals(11, AdtsWriter.sampleRateIndex(8000))
        assertEquals(-1, AdtsWriter.sampleRateIndex(12345))
    }

    @Test
    fun rejectsWhatAdtsCannotExpress() {
        assertTrue(runCatching { AdtsWriter(2, 12345, 2) }.isFailure)
        assertTrue(runCatching { AdtsWriter(2, 48000, 0) }.isFailure)
        // 13 bits de tamanho, com 7 bytes de cabeçalho.
        assertTrue(runCatching { bits(ByteArray(0x2000)) }.isFailure)
    }
}
