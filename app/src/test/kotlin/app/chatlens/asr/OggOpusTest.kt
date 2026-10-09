package app.chatlens.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class OggOpusTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/asr/$n")!!.readBytes()

    /** Estimates the frequency of a tone from its zero crossings. */
    private fun freq(x: FloatArray, rate: Int): Double {
        var z = 0
        for (i in 1 until x.size) if (x[i - 1] < 0 && x[i] >= 0) z++
        return z / (x.size / rate.toDouble())
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun parsesHeaderPacketsAndDuration() {
        val d = res("tone-mono-6s.opus")
        val st = OggOpusReader.parse(d)
        assertEquals(1, st.channels)
        assertTrue("Vorlauf ${st.preSkip48}", st.preSkip48 in 1..2000)
        assertTrue(st.packets.size > 100)
        assertEquals(6.0, st.durationSec!!, 0.05)
        // several Ogg pages
        val pages = (0 until d.size - 3).count { d[it] == 'O'.code.toByte() && d[it + 1] == 'g'.code.toByte() && d[it + 2] == 'g'.code.toByte() && d[it + 3] == 'S'.code.toByte() }
        assertTrue("Seiten $pages", pages >= 3)
        assertEquals(st.durationSec!!, OggOpusReader.durationSec(d)!!, 1e-9)
    }

    @Test
    fun decodesMonoToneAt16kHzWithCorrectLengthAndFrequency() {
        val a = ConcentusOpusDecoder.decode(res("tone-mono-6s.opus"))
        assertEquals(16000, a.sampleRate)
        assertEquals(6.0, a.durationSec, 0.02)
        assertEquals(440.0, freq(a.samples, 16000), 6.0)
        assertTrue("Pegel ${rms(a.samples)}", rms(a.samples, 8000, 90000) > 0.07)
    }

    @Test
    fun decodesStereoToMono() {
        val st = OggOpusReader.parse(res("tone-stereo-1s.opus"))
        assertEquals(2, st.channels)
        val a = ConcentusOpusDecoder.decode(res("tone-stereo-1s.opus"))
        assertEquals(1.0, a.durationSec, 0.02)
        assertEquals(660.0, freq(a.samples, 16000), 10.0)
    }

    @Test
    fun garbageAndTruncationAreReportedOrHandled() {
        try { ConcentusOpusDecoder.decode(ByteArray(500) { it.toByte() }); fail("Muell darf nicht dekodieren") } catch (e: AudioDecodeException) { assertTrue(e.message!!.isNotBlank()) }
        try { ConcentusOpusDecoder.decode(ByteArray(0)); fail() } catch (e: AudioDecodeException) { }
        // truncated file: complete pages before the cut are read, no crash
        val d = res("tone-mono-6s.opus")
        val a = ConcentusOpusDecoder.decode(d.copyOf(d.size / 2))
        assertTrue(a.durationSec in 1.0..5.0)
        assertEquals(null, OggOpusReader.durationSec(ByteArray(10)))
    }

    private fun page(headerType: Int, granule: Long, serial: Int, seq: Int, segs: IntArray, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("OggS".toByteArray())
        o.write(0); o.write(headerType)
        for (i in 0 until 8) o.write(((granule shr (8 * i)) and 0xff).toInt())
        for (i in 0 until 4) o.write((serial shr (8 * i)) and 0xff)
        for (i in 0 until 4) o.write((seq shr (8 * i)) and 0xff)
        o.write(ByteArray(4))
        o.write(segs.size)
        segs.forEach { o.write(it) }
        o.write(body)
        return o.toByteArray()
    }

    private fun opusHead(ch: Int, preSkip: Int, family: Int = 0): ByteArray {
        val b = ByteArrayOutputStream()
        b.write("OpusHead".toByteArray()); b.write(1); b.write(ch); b.write(preSkip and 0xff); b.write(preSkip shr 8)
        b.write(byteArrayOf(0x80.toByte(), 0x3e, 0, 0)); b.write(0); b.write(0); b.write(family)
        return b.toByteArray()
    }

    @Test
    fun reassemblesPacketAcrossPageBoundaryAndSkipsTags() {
        val head = opusHead(1, 312)
        val tags = "OpusTags".toByteArray() + ByteArray(8)
        val big = ByteArray(300) { (it % 251).toByte() }
        val small = byteArrayOf(1, 2, 3)
        val f = ByteArrayOutputStream()
        f.write(page(2, 0, 7, 0, intArrayOf(head.size), head))
        f.write(page(0, 0, 7, 1, intArrayOf(tags.size), tags))
        // packet of 300 bytes: 255 on page 1 (continuation open), 45 on page 2
        f.write(page(0, -1, 7, 2, intArrayOf(255), big.copyOfRange(0, 255)))
        f.write(page(1, 960, 7, 3, intArrayOf(45, 3), big.copyOfRange(255, 300) + small))
        val st = OggOpusReader.parse(f.toByteArray())
        assertEquals(2, st.packets.size)
        assertTrue(st.packets[0].contentEquals(big))
        assertTrue(st.packets[1].contentEquals(small))
        assertEquals(312, st.preSkip48)
        assertEquals((960 - 312) / 48000.0, st.durationSec!!, 1e-9)
    }

    @Test
    fun rejectsUnsupportedChannelMapping() {
        val head = opusHead(6, 312, family = 1)
        try { OggOpusReader.parse(page(2, 0, 1, 0, intArrayOf(head.size), head)); fail() } catch (e: AudioDecodeException) { assertTrue(e.message!!.contains("Kanalzuordnung")) }
    }

    @Test
    fun resamplerKeepsInBandToneAndRemovesOutOfBand() {
        fun tone(f: Double, rate: Int, sec: Int) = FloatArray(rate * sec) { (0.5 * sin(2 * PI * f * it / rate)).toFloat() }
        val low = Pcm.resampleTo16k(tone(1000.0, 48000, 2), 48000)
        assertEquals(32000, low.size)
        assertEquals(1000.0, freq(low, 16000), 15.0)
        assertTrue(rms(low, 1000, 31000) > 0.3)
        val high = Pcm.resampleTo16k(tone(12000.0, 48000, 2), 48000)
        assertTrue("12 kHz muss stark gedaempft sein, Pegel ${rms(high, 1000, 31000)}", rms(high, 1000, 31000) < 0.05)
        val same = FloatArray(10) { it / 10f }
        assertTrue(Pcm.resampleTo16k(same, 16000) === same)
        val up = Pcm.resampleTo16k(tone(500.0, 8000, 1), 8000)
        assertEquals(16000, up.size)
        assertEquals(500.0, freq(up, 16000), 10.0)
        assertTrue(abs(rms(up, 500, 15000) - 0.3535) < 0.05)
    }
}
