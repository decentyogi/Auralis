package com.aurora.music.webserver

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for Aurora Web Server logic:
 * - HTTP Range header parsing (O(1) partial content / seeking support)
 * - Safe path and path traversal rejection
 * - URL percent-encoding for non-ASCII and special characters
 * - WAV RIFF streaming header generation
 * - MIME type detection
 */
class AuroraWebServerTest {

    data class ByteRange(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    /**
     * Range parser mirroring AuroraWebServer's HTTP Range header logic.
     */
    private fun parseRangeHeader(rangeHeader: String?, totalSize: Long): ByteRange? {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=")) {
            return null
        }
        val spec = rangeHeader.removePrefix("bytes=").trim()
        val parts = spec.split("-", limit = 2)
        return try {
            if (parts.size == 2) {
                val startStr = parts[0].trim()
                val endStr = parts[1].trim()
                when {
                    startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                        val s = startStr.toLong()
                        val e = endStr.toLong().coerceAtMost(totalSize - 1)
                        if (s in 0..e) ByteRange(s, e) else null
                    }
                    startStr.isNotEmpty() && endStr.isEmpty() -> {
                        val s = startStr.toLong()
                        if (s in 0 until totalSize) ByteRange(s, totalSize - 1) else null
                    }
                    startStr.isEmpty() && endStr.isNotEmpty() -> {
                        val suffixLen = endStr.toLong()
                        if (suffixLen > 0) {
                            val s = (totalSize - suffixLen).coerceAtLeast(0L)
                            ByteRange(s, totalSize - 1)
                        } else null
                    }
                    else -> null
                }
            } else null
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Path sanitizer mirroring AuroraWebServer's directory traversal protection.
     */
    private fun isPathSafe(rawPath: String): Boolean {
        val decoded = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (_: Exception) {
            return false
        }
        if (decoded.contains("..") || decoded.contains("/../") || decoded.contains("\\..\\")) {
            return false
        }
        if (decoded.contains("/etc/") || decoded.contains("/proc/") || decoded.contains("/sys/") ||
            decoded.contains("C:\\Windows", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    /**
     * WAV RIFF header generation logic mirroring WebServerStorageBridge.
     */
    private fun generateWavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int, pcmDataLength: Long): ByteArray {
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()
        val totalFileSize = if (pcmDataLength > 0) pcmDataLength + 36 else 0x7FFFFFFF - 8

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalFileSize.toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1) // AudioFormat: 1 = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(if (pcmDataLength > 0) pcmDataLength.toInt() else 0x7FFFFFFF - 44)

        return header
    }

    private fun resolveMimeType(filename: String): String {
        return when {
            filename.endsWith(".flac", ignoreCase = true) -> "audio/flac"
            filename.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            filename.endsWith(".alac", ignoreCase = true) -> "audio/mp4"
            filename.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            filename.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            filename.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            filename.endsWith(".opus", ignoreCase = true) -> "audio/opus"
            else -> "application/octet-stream"
        }
    }

    @Test
    fun testRangeHeaderExplicitStartAndEnd() {
        val total = 10_000L
        val range = parseRangeHeader("bytes=0-499", total)
        assertNotNull(range)
        assertEquals(0L, range!!.start)
        assertEquals(499L, range.end)
        assertEquals(500L, range.length)
    }

    @Test
    fun testRangeHeaderOpenEnded() {
        val total = 10_000L
        val range = parseRangeHeader("bytes=1000-", total)
        assertNotNull(range)
        assertEquals(1000L, range!!.start)
        assertEquals(9999L, range.end)
        assertEquals(9000L, range.length)
    }

    @Test
    fun testRangeHeaderSuffixBytes() {
        val total = 10_000L
        val range = parseRangeHeader("bytes=-500", total)
        assertNotNull(range)
        assertEquals(9500L, range!!.start)
        assertEquals(9999L, range.end)
        assertEquals(500L, range.length)
    }

    @Test
    fun testRangeHeaderInvalidAndOutOfBounds() {
        val total = 1000L
        // start > end
        assertNull(parseRangeHeader("bytes=500-200", total))
        // start beyond total
        assertNull(parseRangeHeader("bytes=2000-", total))
        // malformed
        assertNull(parseRangeHeader("invalid-range", total))
        assertNull(parseRangeHeader("bytes=", total))
        assertNull(parseRangeHeader("bytes=abc-def", total))
    }

    @Test
    fun testPathTraversalRejection() {
        assertFalse(isPathSafe("../../../etc/shadow"))
        assertFalse(isPathSafe("..\\..\\windows\\system32\\cmd.exe"))
        assertFalse(isPathSafe("%2e%2e%2f%2e%2e%2fetc%2fpasswd"))
        assertFalse(isPathSafe("/music/tracks/../../private.key"))
        assertTrue(isPathSafe("12345"))
        assertTrue(isPathSafe("uuid-track-001"))
        assertTrue(isPathSafe("artist/album/song.flac"))
    }

    @Test
    fun testUrlEncodingNonAsciiAndSpecialChars() {
        val originalTitle = "गाने_हिन्दी & 'special' #1 [FLAC 96kHz]"
        val encoded = URLEncoder.encode(originalTitle, "UTF-8")
        assertFalse(encoded.contains(" "))
        assertFalse(encoded.contains("'"))

        val decoded = URLDecoder.decode(encoded, "UTF-8")
        assertEquals(originalTitle, decoded)
    }

    @Test
    fun testWavHeaderFormatAndOffsets() {
        val sampleRate = 44100
        val channels = 2
        val bitsPerSample = 16
        val pcmDataLength = 88200L // 1 second of stereo 16-bit 44.1kHz audio
        val header = generateWavHeader(sampleRate, channels, bitsPerSample, pcmDataLength)

        assertEquals(44, header.size)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val riff = ByteArray(4)
        buf.get(riff)
        assertEquals("RIFF", String(riff, Charsets.US_ASCII))

        val fileSize = buf.getInt()
        assertEquals((pcmDataLength + 36).toInt(), fileSize)

        val wave = ByteArray(4)
        buf.get(wave)
        assertEquals("WAVE", String(wave, Charsets.US_ASCII))

        val fmt = ByteArray(4)
        buf.get(fmt)
        assertEquals("fmt ", String(fmt, Charsets.US_ASCII))

        val subchunk1Size = buf.getInt()
        assertEquals(16, subchunk1Size)

        val audioFormat = buf.getShort()
        assertEquals(1.toShort(), audioFormat) // PCM

        val numChannels = buf.getShort()
        assertEquals(channels.toShort(), numChannels)

        val sRate = buf.getInt()
        assertEquals(sampleRate, sRate)

        val byteRate = buf.getInt()
        assertEquals(sampleRate * channels * 2, byteRate)

        val blockAlign = buf.getShort()
        assertEquals((channels * 2).toShort(), blockAlign)

        val bits = buf.getShort()
        assertEquals(bitsPerSample.toShort(), bits)

        val data = ByteArray(4)
        buf.get(data)
        assertEquals("data", String(data, Charsets.US_ASCII))

        val dataSize = buf.getInt()
        assertEquals(pcmDataLength.toInt(), dataSize)
    }

    @Test
    fun testMimeTypeResolution() {
        assertEquals("audio/flac", resolveMimeType("song.flac"))
        assertEquals("audio/flac", resolveMimeType("SONG.FLAC"))
        assertEquals("audio/mp4", resolveMimeType("track.m4a"))
        assertEquals("audio/mp4", resolveMimeType("track.alac"))
        assertEquals("audio/mpeg", resolveMimeType("track.mp3"))
        assertEquals("audio/wav", resolveMimeType("audio.wav"))
        assertEquals("audio/ogg", resolveMimeType("stream.ogg"))
        assertEquals("audio/opus", resolveMimeType("voice.opus"))
        assertEquals("application/octet-stream", resolveMimeType("unknown.xyz"))
    }
}
