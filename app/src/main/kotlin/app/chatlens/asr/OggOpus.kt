package app.chatlens.asr

/** Fehler beim Lesen oder Dekodieren einer Audiodatei. Die Meldung ist fuer das Log gedacht (ohne Dateinamen). */
class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Inhalt einer Ogg-Opus-Datei: Kopfdaten und die Opus-Pakete in Reihenfolge. */
class OggOpusStream(
    val channels: Int,
    /** Vorlauf in Abtastwerten bei 48 kHz, der am Anfang zu verwerfen ist. */
    val preSkip48: Int,
    val inputRate: Int,
    val packets: List<ByteArray>,
    /** Granule-Position der letzten Seite (Abtastwerte bei 48 kHz einschliesslich Vorlauf), -1 wenn unbekannt. */
    val lastGranule: Long,
) {
    /** Gesamtdauer der Tonspur in Sekunden laut Granule-Position, null wenn unbekannt. */
    val durationSec: Double? get() = if (lastGranule >= 0) ((lastGranule - preSkip48).coerceAtLeast(0L)) / 48000.0 else null
}

/**
 * Kleiner Ogg-Opus-Leser (RFC 3533 und RFC 7845), nur fuer Kanalzuordnung 0 (Mono oder Stereo), wie sie WhatsApp-Sprachnachrichten nutzen.
 * Liest die erste logische Spur, setzt Pakete ueber Seitengrenzen zusammen, ueberspringt OpusTags. Die CRC wird nicht geprueft.
 */
object OggOpusReader {
    private const val HEADER_MIN = 27

    fun parse(data: ByteArray): OggOpusStream {
        var pos = 0
        var serial: Long? = null
        val packets = ArrayList<ByteArray>()
        var partial: java.io.ByteArrayOutputStream? = null
        var lastGranule = -1L
        var pages = 0
        while (pos + HEADER_MIN <= data.size) {
            if (data[pos] != 'O'.code.toByte() || data[pos + 1] != 'g'.code.toByte() || data[pos + 2] != 'g'.code.toByte() || data[pos + 3] != 'S'.code.toByte()) {
                // Synchronisation verloren: bis zur naechsten Seite suchen
                val next = indexOfCapture(data, pos + 1)
                if (next < 0) break
                pos = next
                continue
            }
            if ((data[pos + 4].toInt() and 0xff) != 0) throw AudioDecodeException("Ogg-Version nicht unterstuetzt")
            val granule = le64(data, pos + 6)
            val ser = le32(data, pos + 14)
            val nseg = data[pos + 26].toInt() and 0xff
            if (pos + HEADER_MIN + nseg > data.size) break
            var bodyLen = 0
            for (i in 0 until nseg) bodyLen += data[pos + HEADER_MIN + i].toInt() and 0xff
            val bodyStart = pos + HEADER_MIN + nseg
            if (bodyStart + bodyLen > data.size) break // abgeschnittene letzte Seite
            if (serial == null) serial = ser
            if (ser == serial) {
                pages++
                if (granule >= 0) lastGranule = granule
                var off = bodyStart
                for (i in 0 until nseg) {
                    val l = data[pos + HEADER_MIN + i].toInt() and 0xff
                    val buf = partial ?: java.io.ByteArrayOutputStream().also { partial = it }
                    buf.write(data, off, l)
                    off += l
                    if (l < 255) { packets.add(buf.toByteArray()); partial = null }
                }
            }
            pos = bodyStart + bodyLen
        }
        if (pages == 0 || packets.isEmpty()) throw AudioDecodeException("Keine Ogg-Daten gefunden")
        val head = packets[0]
        if (head.size < 19 || String(head, 0, 8, Charsets.ISO_8859_1) != "OpusHead") throw AudioDecodeException("Kein Opus-Kopf (OpusHead fehlt)")
        val channels = head[9].toInt() and 0xff
        val preSkip = (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8)
        val rate = le32(head, 12).toInt()
        val family = head[18].toInt() and 0xff
        if (family != 0) throw AudioDecodeException("Kanalzuordnung $family nicht unterstuetzt")
        if (channels !in 1..2) throw AudioDecodeException("Kanalzahl $channels nicht unterstuetzt")
        var firstAudio = 1
        if (packets.size > 1 && packets[1].size >= 8 && String(packets[1], 0, 8, Charsets.ISO_8859_1) == "OpusTags") firstAudio = 2
        return OggOpusStream(channels, preSkip, rate, packets.subList(firstAudio, packets.size).filter { it.isNotEmpty() }, lastGranule)
    }

    /** Nur die Dauer (Sekunden) aus Kopf und letzter Seite, ohne Dekodierung. Null, wenn nicht lesbar. */
    fun durationSec(data: ByteArray): Double? = runCatching { parse(data).durationSec }.getOrNull()

    private fun indexOfCapture(d: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 < d.size) {
            if (d[i] == 'O'.code.toByte() && d[i + 1] == 'g'.code.toByte() && d[i + 2] == 'g'.code.toByte() && d[i + 3] == 'S'.code.toByte()) return i
            i++
        }
        return -1
    }

    private fun le32(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xff) or ((d[o + 1].toLong() and 0xff) shl 8) or ((d[o + 2].toLong() and 0xff) shl 16) or ((d[o + 3].toLong() and 0xff) shl 24)

    private fun le64(d: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (d[o + i].toLong() and 0xff)
        return v
    }
}
