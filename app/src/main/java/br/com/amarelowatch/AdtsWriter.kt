package br.com.amarelowatch

/**
 * Monta o cabecalho ADTS que o navegador precisa para tocar um fluxo AAC cru.
 *
 * Um MP4 seria o container ideal, mas ele escreve o indice (moov) no fim do
 * arquivo: como a transmissao nunca termina, o arquivo nunca ficaria pronto.
 * O ADTS carrega o necessario em cada quadro, entao o `<audio>` da TV ja
 * comeca a tocar enquanto os dados ainda estao chegando.
 *
 * Fica isolado do Android de proposito: e aritmetica pura, e assim da para
 * testar sem aparelho.
 */
internal class AdtsWriter(
    private val audioObjectType: Int,
    private val sampleRate: Int,
    private val channels: Int,
) {

    init {
        require(sampleRateIndex(sampleRate) >= 0) { "taxa de amostragem fora do ADTS: $sampleRate" }
        require(channels in 1..7) { "canais fora do ADTS: $channels" }
        require(audioObjectType in 1..4) { "perfil AAC fora do ADTS: $audioObjectType" }
    }

    /**
     * Devolve o quadro inteiro: cabecalho + payload do MediaCodec.
     */
    fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        writeHeader(out, 0, payload.size, audioObjectType, sampleRate, channels)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    companion object {
        const val HEADER_SIZE = 7

        /** AAC-LC, que e o perfil que o MediaCodec entrega para audio/aac. */
        const val AAC_LC = 2

        /** the sampling_frequency_index da tabela do ISO/IEC 14496-3. */
        fun sampleRateIndex(sampleRate: Int): Int = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> -1
        }

        fun writeHeader(
            out: ByteArray,
            offset: Int,
            payloadLength: Int,
            audioObjectType: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            val frameLength = HEADER_SIZE + payloadLength
            require(frameLength < 0x2000) { "quadro ADTS grande demais: $frameLength" }

            val freq = sampleRateIndex(sampleRate)
            val config = channels
            val profile = (audioObjectType - 1) and 0x03

            // Layout dos 7 bytes (sem CRC):
            //   0: syncword[11:4]
            //   1: syncword[3:0] | ID | layer[1:0] | protection_absent
            //   2: profile[1:0] | sampling_frequency_index[3:0] | private | config[2]
            //   3: config[0] | original | home | copyright_id_bit | copyright_id_start | length[12:11]
            //   4: length[10:3]
            //   5: length[2:0] | buffer_fullness[10:6]
            //   6: buffer_fullness[5:0] | raw_blocks[1:0]
            out[offset] = 0xFF.toByte()
            // ID=0 (MPEG-4), layer=00, protection_absent=1 (por isso header de 7 bytes)
            out[offset + 1] = 0xF1.toByte()
            out[offset + 2] = ((profile shl 6) or (freq shl 2) or ((config shr 2) and 0x01)).toByte()
            // original/home/copyright zerados => so o bit de canal e o topo do tamanho
            out[offset + 3] = (((config and 0x01) shl 7) or ((frameLength shr 11) and 0x03)).toByte()
            out[offset + 4] = ((frameLength shr 3) and 0xFF).toByte()
            // buffer_fullness = 0x7FF marca VBR, que e o que queremos
            out[offset + 5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            out[offset + 6] = 0xFC.toByte()
        }
    }
}
