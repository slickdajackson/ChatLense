package app.chatlens.asr

import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusException

/** Wandelt die Bytes einer Sprachnachricht in 16-kHz-Mono-Tondaten. */
interface AudioDecoder {
    val name: String
    fun decode(bytes: ByteArray): DecodedAudio
}

/**
 * Opus-Dekodierung in reinem Java (Concentus, BSD-3-Clause) ueber [OggOpusReader]. Dekodiert direkt mit 16 kHz Ausgaberate
 * (Opus liefert 8, 12, 16, 24 oder 48 kHz), entfernt den Vorlauf und kuerzt am Ende laut Granule-Position.
 * Laeuft auf der JVM und auf Android gleich und ist deshalb auf dem Rechner testbar.
 */
object ConcentusOpusDecoder : AudioDecoder {
    override val name: String = "Concentus (Java)"
    private const val RATE = Pcm.TARGET_RATE
    private const val MAX_FRAME = RATE * 120 / 1000 // 120 ms je Paket als Obergrenze

    override fun decode(bytes: ByteArray): DecodedAudio {
        val st = OggOpusReader.parse(bytes)
        val dec = try { OpusDecoder(RATE, st.channels) } catch (e: OpusException) { throw AudioDecodeException("Opus-Decoder nicht erstellt: ${e.message}", e) }
        val frame = ShortArray(MAX_FRAME * st.channels)
        var out = FloatArray(RATE * 10)
        var n = 0
        for (pkt in st.packets) {
            val got = try { dec.decode(pkt, 0, pkt.size, frame, 0, MAX_FRAME, false) } catch (e: OpusException) {
                throw AudioDecodeException("Opus-Paket fehlerhaft: ${e.message}", e)
            }
            if (got <= 0) continue
            if (n + got > out.size) out = out.copyOf(maxOf(out.size * 2, n + got))
            val mono = Pcm.downmix(frame, got, st.channels)
            System.arraycopy(mono, 0, out, n, got)
            n += got
        }
        val skip = (st.preSkip48 / (48000 / RATE)).coerceAtMost(n)
        var end = n
        st.durationSec?.let { d -> end = minOf(n, skip + (d * RATE).toInt()) }
        if (end <= skip) throw AudioDecodeException("Keine Audiodaten nach dem Vorlauf")
        return DecodedAudio(out.copyOfRange(skip, end), RATE)
    }
}
