package app.chatlens.asr

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Fallback decoder via Android's built-in tools (MediaExtractor and MediaCodec, "audio/opus"). Not tried on the target device.
 * Returns 16-bit PCM, converted to mono and 16 kHz. Not testable on the JVM.
 */
class MediaCodecAudioDecoder : AudioDecoder {
    override val name: String = "MediaCodec"

    override fun decode(bytes: ByteArray): DecodedAudio {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = minOf(size, bytes.size - position.toInt())
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun getSize(): Long = bytes.size.toLong()
                override fun close() {}
            })
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw AudioDecodeException("MediaExtractor findet keine Audiospur")
            ex.selectTrack(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val info = MediaCodec.BufferInfo()
            var pcm = ShortArray(rate * 10)
            var n = 0
            var inputDone = false
            var outputDone = false
            var idleLoops = 0
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        idleLoops = 0
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val cnt = sb.remaining()
                        if (n + cnt > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, n + cnt))
                        sb.get(pcm, n, cnt)
                        n += cnt
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    else -> if (inputDone && ++idleLoops > 300) throw AudioDecodeException("MediaCodec liefert kein Ende")
                }
            }
            if (n == 0) throw AudioDecodeException("MediaCodec lieferte keine Daten")
            val mono = Pcm.downmix(pcm, n / ch, ch)
            return DecodedAudio(Pcm.resampleTo16k(mono, rate), Pcm.TARGET_RATE)
        } catch (e: AudioDecodeException) {
            throw e
        } catch (e: Exception) {
            throw AudioDecodeException("MediaCodec: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
