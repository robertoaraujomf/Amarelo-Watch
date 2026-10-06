package br.com.amarelowatch

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pega o som que o celular esta tocando e entrega em quadros AAC/ADTS.
 *
 * O caminho oficial e o AudioPlaybackCaptureConfiguration (Android 10+): o app
 * nao ganha acesso privilegiado ao audio do sistema, apenas o direito de
 * gravar o playback de outros apps, e apenas com o consentimento da mesma
 * tela do MediaProjection. O AudioRecord precisa usar a fonte REMOTE_SUBMIX
 * porque e assim que o framework separa este caminho do CaptureAudioOutput,
 * que e assinatura e nao existe para app comum.
 *
 * O que fica de fora e esperado, nao e falha: conteudo protegido (DRM,
 * FLAG_SECURE) e apps que desligaram a captura por politica. Nesse caso o
 * AudioRecord entrega silencio e a TV fica sem som.
 *
 * Vale saber antes de ligar: como a captura e o playback sao caminhos
 * independentes do video, o som chega com um atraso proprio e nao existe
 * ancora comum entre os dois. E o celular vai tocar o audio no alto-falante
 * ao mesmo tempo que a TV toca, ou seja, dois sons.
 */
class AudioCapturer(
    private val projection: MediaProjection,
    private val listener: Listener,
) {

    interface Listener {
        /** Quadro ADTS pronto para o fluxo de audio. */
        fun onAudioChunk(aac: ByteArray)

        fun onAvailable()

        /** Audio nao pode ser capturado; [reason] explica o motivo para o usuario. */
        fun onUnavailable(reason: String)

        fun onLog(message: String)
    }

    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    private var worker: Thread? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var codec: MediaCodec? = null

    /** Ultimo quadro produzido, para o teste e para diagnostico. */
    @Volatile
    var framesEncoded: Long = 0L
        private set

    val isAvailable: Boolean get() = available.get()

    private val available = AtomicBoolean(false)

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            listener.onUnavailable("captura de áudio exige Android 10 ou mais novo")
            return
        }
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ pump() }, "amarelo-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        // read() so volta com falha se o AudioRecord for parado; por isso
        // paramos por fora em vez de so interromper a thread.
        runCatching { record?.stop() }
        worker?.interrupt()
        worker = null
    }

    // -------------------------------------------------------------- pipeline

    private fun pump() {
        var recordLocal: AudioRecord? = null
        var codecLocal: MediaCodec? = null
        try {
            val playbackCapture = AudioPlaybackCaptureConfiguration.Builder(projection).build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                fail("o aparelho recusou o formato de áudio desejado")
                return
            }

            val audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setAudioPlaybackCaptureConfig(playbackCapture)
                .build()

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { audioRecord.release() }
                fail("o aparelho recusou a captura de áudio do sistema")
                return
            }

            record = audioRecord
            recordLocal = audioRecord

            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("a gravação de áudio não iniciou")
                return
            }

            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            }

            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder
            codecLocal = encoder

            available.set(true)
            listener.onAvailable()
            listener.onLog("capturando áudio em ${SAMPLE_RATE / 1000} kHz, ${BITRATE / 1000} kbps")

            val adts = AdtsWriter(AdtsWriter.AAC_LC, SAMPLE_RATE, CHANNEL_COUNT)
            val pcm = ByteArray(MAX_INPUT_SIZE)
            val drain = Drainer(encoder, adts, ::emit)

            while (running.get()) {
                val read = audioRecord.read(pcm, 0, pcm.size, READ_TIMEOUT_MS)
                if (read < 0) {
                    if (running.get()) {
                        fail("leitura de áudio interrompida ($read)")
                    }
                    return
                }
                if (read == 0) continue

                var offset = 0
                var ptsUs = 0L
                var travas = 0
                while (offset < read && running.get()) {
                    val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index < 0) {
                        // Sem buffer de entrada o encoder esta cheio: esvazia a
                        // saida para liberar. Se nao liberar em varias
                        // tentativas, algo travou e nao adianta insistir.
                        drain.drain()
                        if (++travas > MAX_STALLS) {
                            listener.onLog("encoder de áudio travado, reiniciando")
                            fail("o encoder de áudio não respondeu")
                            return
                        }
                        continue
                    }
                    travas = 0
                    val buffer = encoder.getInputBuffer(index)
                    if (buffer == null) {
                        encoder.queueInputBuffer(index, 0, 0, ptsUs, 0)
                        continue
                    }
                    val chunk = minOf(buffer.remaining(), read - offset)
                    buffer.put(pcm, offset, chunk)
                    encoder.queueInputBuffer(index, 0, chunk, ptsUs, 0)
                    offset += chunk
                    // bytes -> amostras de audio -> microssegundos
                    ptsUs += (chunk / BYTES_PER_FRAME).toLong() * 1_000_000L / SAMPLE_RATE
                }
                drain.drain()
            }
        } catch (t: Throwable) {
            if (running.get()) fail("falha no áudio: ${t.message}")
        } finally {
            available.set(false)
            releaseQuietly(codecLocal)
            releaseQuietly(recordLocal)
            codec = null
            record = null
        }
    }

    private fun emit(frame: ByteArray) {
        framesEncoded++
        listener.onAudioChunk(frame)
    }

    private fun fail(reason: String) {
        available.set(false)
        listener.onUnavailable(reason)
    }

    private fun releaseQuietly(target: MediaCodec?) {
        if (target == null) return
        runCatching { target.stop() }
        runCatching { target.release() }
    }

    private fun releaseQuietly(target: AudioRecord?) {
        if (target == null) return
        runCatching { target.stop() }
        runCatching { target.release() }
    }

    /**
     * Esvazia o encoder. O primeiro evento chega antes de qualquer quadro e e
     * quando descobrimos como o encoder escolheu profile, taxa e canais: e dali
     * que sai o cabecalho ADTS de cada quadro.
     */
    private class Drainer(
        private val encoder: MediaCodec,
        fallback: AdtsWriter,
        private val emit: (ByteArray) -> Unit,
    ) {
        private var header: AdtsWriter = fallback

        fun drain() {
            while (true) {
                val index = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    index >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val payload = ByteArray(bufferInfo.size)
                            encoder.getOutputBuffer(index)?.apply {
                                position(bufferInfo.offset)
                                limit(bufferInfo.offset + bufferInfo.size)
                            }?.get(payload)
                            if (payload.isNotEmpty()) emit(header.frame(payload))
                        }
                        encoder.releaseOutputBuffer(index, false)
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        header = AdtsWriter(
                            audioObjectType = encoder.outputFormat.objectType(),
                            sampleRate = encoder.outputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE),
                            channels = encoder.outputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, CHANNEL_COUNT),
                        )
                    }

                    else -> return
                }
            }
        }

        private companion object {
            val bufferInfo = MediaCodec.BufferInfo()
        }
    }

    companion object {
        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 2
        const val BITRATE = 128_000
        private const val MAX_INPUT_SIZE = 16 * 1024
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val READ_TIMEOUT_MS = 500
        private const val MAX_STALLS = 64

        /** 2 canais de 16 bits = 4 bytes por amostra de audio. */
        private const val BYTES_PER_FRAME = CHANNEL_COUNT * 2
    }
}

private fun MediaFormat.intOr(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

/** O profile vem no csd-0: 5 bits no primeiro byte. */
private fun MediaFormat.objectType(): Int {
    val csd = runCatching { getByteBuffer("csd-0") }.getOrNull() ?: return AdtsWriter.AAC_LC
    if (csd.remaining() <= 0) return AdtsWriter.AAC_LC
    val first = csd.get(0).toInt() and 0xFF
    val type = (first shr 3) and 0x1F
    return if (type in 1..4) type else AdtsWriter.AAC_LC
}
