package com.aurora.music.webserver

/**
 * Represents an audio track discovered in the local device library or selected storage locations,
 * exposed over the local HTTP streaming API.
 */
data class WebTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val format: String, // "flac", "m4a", "alac", "mp3", "wav", "ogg", "aac", etc.
    val uriString: String,
    val fileName: String,
    val locationId: String = "sys_music",
    val dateModified: Long = 0L,
    val isAlac: Boolean = false
) {
    val durationFormatted: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val sizeFormatted: String
        get() {
            if (sizeBytes <= 0) return "0 B"
            val mb = sizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format(sizeBytes / 1024)
        }
}
