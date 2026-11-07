package com.aurora.music.webserver

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WebLocation(
    val id: String,
    val name: String,
    val uriString: String,
    val trackCount: Int = 0,
    val isSystemLibrary: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis()
)

class WebServerStorageBridge(private val context: Context) {

    companion object {
        private const val TAG = "WebServerStorageBridge"

        // Global static cache shared across WebServerService and HTTP Server
        val sharedTracksMap = ConcurrentHashMap<String, WebTrack>()
        private var isDiskCacheLoaded = false
        private val cacheLock = Any()

        // Ultra-fast in-memory thumbnail cache (holds up to 400 low-res thumbnails in RAM)
        private val thumbnailLruCache = LruCache<String, ByteArray>(400)
        private val noArtCache = java.util.Collections.synchronizedSet(HashSet<String>())
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aurora_web_locations_prefs", Context.MODE_PRIVATE)

    private val metaCachePrefs: SharedPreferences =
        context.getSharedPreferences("aurora_web_meta_cache", Context.MODE_PRIVATE)

    private val supportedExtensions = setOf(
        "flac", "m4a", "alac", "mp3", "wav", "ogg", "aac", "opus", "aiff"
    )

    private val diskCacheFile: File
        get() = File(context.filesDir, "aurora_web_tracks_cache.json")

    init {
        ensureTracksLoaded()
    }

    private fun ensureTracksLoaded() {
        synchronized(cacheLock) {
            if (!isDiskCacheLoaded) {
                loadTracksFromDiskCache()
                isDiskCacheLoaded = true
            }
        }
    }

    /**
     * Retrieves all cached tracks. If cache is empty, loads from disk or scans MediaStore immediately.
     */
    fun getCachedTracks(): List<WebTrack> {
        ensureTracksLoaded()
        if (sharedTracksMap.isNotEmpty()) {
            return sharedTracksMap.values.toList().sortedBy { it.title.lowercase() }
        }

        // Attempt scan of device MediaStore directly if cache is empty
        val mediaStoreTracks = scanSystemMediaStore("sys_music")
        if (mediaStoreTracks.isNotEmpty()) {
            for (t in mediaStoreTracks) {
                sharedTracksMap[t.id] = t
            }
            saveTracksToDiskCache(mediaStoreTracks)
            // Ensure system library location is registered in preferences
            val locations = getSavedLocations().toMutableList()
            if (locations.none { it.isSystemLibrary }) {
                locations.add(0, WebLocation(
                    id = "sys_music",
                    name = "Device Music Library",
                    uriString = "content://media/external/audio/media",
                    trackCount = mediaStoreTracks.size,
                    isSystemLibrary = true
                ))
                saveLocations(locations)
            }
            return mediaStoreTracks.sortedBy { it.title.lowercase() }
        }

        // Also check if any saved folder locations can be scanned
        val locations = getSavedLocations()
        val folderTracks = mutableListOf<WebTrack>()
        for (loc in locations) {
            if (!loc.isSystemLibrary) {
                folderTracks.addAll(scanDocumentTree(loc))
            }
        }
        if (folderTracks.isNotEmpty()) {
            for (t in folderTracks) {
                sharedTracksMap[t.id] = t
            }
            saveTracksToDiskCache(folderTracks)
            return folderTracks.sortedBy { it.title.lowercase() }
        }

        return getDemoTracks()
    }

    private fun loadTracksFromDiskCache() {
        val file = diskCacheFile
        if (!file.exists() || file.length() == 0L) return
        try {
            val jsonString = file.readText(Charsets.UTF_8)
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val track = WebTrack(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    album = obj.getString("album"),
                    durationMs = obj.getLong("durationMs"),
                    sizeBytes = obj.getLong("sizeBytes"),
                    format = obj.getString("format"),
                    uriString = obj.getString("uriString"),
                    fileName = obj.getString("fileName"),
                    locationId = obj.optString("locationId", "sys_music"),
                    dateModified = obj.optLong("dateModified", 0L),
                    isAlac = obj.optBoolean("isAlac", false),
                    filePath = if (obj.has("filePath") && !obj.isNull("filePath")) obj.getString("filePath") else null
                )
                sharedTracksMap[track.id] = track
            }
            Log.i(TAG, "Loaded ${sharedTracksMap.size} tracks from persistent disk cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk cache", e)
        }
    }

    private fun saveTracksToDiskCache(tracks: List<WebTrack>) {
        try {
            val array = JSONArray()
            for (t in tracks) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("durationMs", t.durationMs)
                    put("sizeBytes", t.sizeBytes)
                    put("format", t.format)
                    put("uriString", t.uriString)
                    put("fileName", t.fileName)
                    put("locationId", t.locationId)
                    put("dateModified", t.dateModified)
                    put("isAlac", t.isAlac)
                    if (t.filePath != null) {
                        put("filePath", t.filePath)
                    }
                }
                array.put(obj)
            }
            diskCacheFile.writeText(array.toString(), Charsets.UTF_8)
            Log.i(TAG, "Saved ${tracks.size} tracks to persistent disk cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk cache", e)
        }
    }

    fun getSavedLocations(): List<WebLocation> {
        val jsonString = prefs.getString("locations_list", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<WebLocation>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    WebLocation(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        uriString = obj.getString("uriString"),
                        trackCount = obj.optInt("trackCount", 0),
                        isSystemLibrary = obj.optBoolean("isSystemLibrary", false),
                        dateAdded = obj.optLong("dateAdded", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved locations", e)
            emptyList()
        }
    }

    private fun saveLocations(locations: List<WebLocation>) {
        val array = JSONArray()
        for (loc in locations) {
            val obj = JSONObject().apply {
                put("id", loc.id)
                put("name", loc.name)
                put("uriString", loc.uriString)
                put("trackCount", loc.trackCount)
                put("isSystemLibrary", loc.isSystemLibrary)
                put("dateAdded", loc.dateAdded)
            }
            array.put(obj)
        }
        prefs.edit().putString("locations_list", array.toString()).apply()
    }

    fun addFolderLocation(uri: Uri, folderName: String): WebLocation {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            Log.w(TAG, "Could not take persistable permission: ${e.message}")
        }

        val id = UUID.randomUUID().toString().take(8)
        val location = WebLocation(
            id = id,
            name = folderName.ifEmpty { "Folder $id" },
            uriString = uri.toString(),
            trackCount = 0,
            isSystemLibrary = false
        )

        val current = getSavedLocations().toMutableList()
        current.add(location)
        saveLocations(current)
        return location
    }

    fun addSystemMusicLibrary(): WebLocation {
        val current = getSavedLocations().toMutableList()
        val existing = current.find { it.isSystemLibrary }
        if (existing != null) return existing

        val loc = WebLocation(
            id = "sys_music",
            name = "Device Music Library",
            uriString = "content://media/external/audio/media",
            trackCount = 0,
            isSystemLibrary = true
        )
        current.add(0, loc)
        saveLocations(current)
        return loc
    }

    fun removeLocation(locationId: String) {
        val current = getSavedLocations().toMutableList()
        current.removeAll { it.id == locationId }
        saveLocations(current)
        sharedTracksMap.values.removeAll { it.locationId == locationId }
        saveTracksToDiskCache(sharedTracksMap.values.toList())
    }

    suspend fun scanAllLocations(): List<WebTrack> = withContext(Dispatchers.IO) {
        val locations = getSavedLocations()
        val allTracks = mutableListOf<WebTrack>()

        for (loc in locations) {
            val tracks = if (loc.isSystemLibrary) {
                scanSystemMediaStore(loc.id)
            } else {
                scanDocumentTree(loc)
            }
            updateLocationTrackCount(loc.id, tracks.size)
            allTracks.addAll(tracks)
        }

        if (allTracks.isNotEmpty()) {
            sharedTracksMap.clear()
            for (t in allTracks) {
                sharedTracksMap[t.id] = t
            }
            saveTracksToDiskCache(allTracks)
        } else {
            allTracks.addAll(getDemoTracks())
        }

        allTracks
    }

    private fun updateLocationTrackCount(locationId: String, count: Int) {
        val current = getSavedLocations().toMutableList()
        val index = current.indexOfFirst { it.id == locationId }
        if (index != -1) {
            current[index] = current[index].copy(trackCount = count)
            saveLocations(current)
        }
    }

    private fun scanDocumentTree(location: WebLocation): List<WebTrack> {
        val result = mutableListOf<WebTrack>()
        val uri = Uri.parse(location.uriString)
        val rootDoc = try {
            DocumentFile.fromTreeUri(context, uri)
        } catch (_: Exception) { null } ?: return emptyList()

        fun traverse(doc: DocumentFile) {
            if (doc.isDirectory) {
                doc.listFiles().forEach { child -> traverse(child) }
            } else if (doc.isFile) {
                val name = doc.name ?: return
                val ext = name.substringAfterLast('.', "").lowercase()
                if (supportedExtensions.contains(ext)) {
                    val track = parseTrackFromDocFast(doc, ext, location.id)
                    if (track != null) result.add(track)
                }
            }
        }

        traverse(rootDoc)
        return result
    }

    private fun scanSystemMediaStore(locationId: String): List<WebTrack> {
        val result = mutableListOf<WebTrack>()
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.RELATIVE_PATH
            )
        } else {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DATA
            )
        }

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                while (c.moveToNext()) {
                    val mediaId = if (idCol >= 0) c.getLong(idCol) else continue
                    val title = if (titleCol >= 0) c.getString(titleCol) ?: "Unknown Track" else "Unknown Track"
                    val artist = if (artistCol >= 0) c.getString(artistCol) ?: "Unknown Artist" else "Unknown Artist"
                    val album = if (albumCol >= 0) c.getString(albumCol) ?: "Unknown Album" else "Unknown Album"
                    val duration = if (durationCol >= 0) c.getLong(durationCol) else 0L
                    val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                    val fileName = if (nameCol >= 0) c.getString(nameCol) ?: "track.mp3" else "track.mp3"
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    val dateModified = if (dateCol >= 0) c.getLong(dateCol) * 1000L else 0L

                    var rawPath = if (dataCol >= 0) c.getString(dataCol) else null
                    if (rawPath.isNullOrEmpty() && relCol >= 0) {
                        val rel = c.getString(relCol)
                        if (!rel.isNullOrEmpty()) {
                            rawPath = "/storage/emulated/0/${rel.trimStart('/')}$fileName"
                        }
                    }

                    val trackUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId.toString())
                    val isAlac = ext == "m4a" || ext == "alac"

                    result.add(
                        WebTrack(
                            id = "media_$mediaId",
                            title = if (title.contains("<unknown>")) fileName.substringBeforeLast('.') else title,
                            artist = if (artist.contains("<unknown>")) "Unknown Artist" else artist,
                            album = if (album.contains("<unknown>")) "Unknown Album" else album,
                            durationMs = duration,
                            sizeBytes = size,
                            format = ext.ifEmpty { "mp3" },
                            uriString = trackUri.toString(),
                            fileName = fileName,
                            locationId = locationId,
                            dateModified = dateModified,
                            isAlac = isAlac,
                            filePath = rawPath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore", e)
        }

        return result
    }

    private fun parseTrackFromDocFast(doc: DocumentFile, ext: String, locationId: String): WebTrack? {
        val uri = doc.uri
        val fileName = doc.name ?: "Unknown"
        val lastMod = doc.lastModified()
        val fileLength = doc.length()
        val cacheKey = "${uri}_${lastMod}_$fileLength"

        val cachedJson = metaCachePrefs.getString(cacheKey, null)
        var resolvedPath: String? = null
        if (uri.scheme == "file") {
            resolvedPath = uri.path
        } else {
            try {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    resolvedPath = "/storage/emulated/0/" + docId.removePrefix("primary:")
                }
            } catch (_: Exception) {}
        }

        if (cachedJson != null) {
            try {
                val obj = JSONObject(cachedJson)
                return WebTrack(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    album = obj.getString("album"),
                    durationMs = obj.getLong("durationMs"),
                    sizeBytes = fileLength,
                    format = ext,
                    uriString = uri.toString(),
                    fileName = fileName,
                    locationId = locationId,
                    dateModified = lastMod,
                    isAlac = obj.getBoolean("isAlac"),
                    filePath = resolvedPath
                )
            } catch (_: Exception) {}
        }

        var title = fileName.substringBeforeLast('.')
        var artist = "Unknown Artist"
        var album = "Unknown Album"
        var duration = 0L
        val isAlac = ext == "m4a" || ext == "alac"

        try {
            val mmr = MediaMetadataRetriever()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                mmr.setDataSource(pfd.fileDescriptor)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
                    if (it.isNotBlank()) title = it
                }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let {
                    if (it.isNotBlank()) artist = it
                }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
                    if (it.isNotBlank()) album = it
                }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    duration = it
                }
            }
            mmr.release()
        } catch (_: Exception) {}

        val trackId = "doc_${uri.toString().hashCode().toString(16)}"
        val track = WebTrack(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            sizeBytes = fileLength,
            format = ext,
            uriString = uri.toString(),
            fileName = fileName,
            locationId = locationId,
            dateModified = lastMod,
            isAlac = isAlac,
            filePath = resolvedPath
        )

        try {
            val obj = JSONObject().apply {
                put("id", trackId)
                put("title", title)
                put("artist", artist)
                put("album", album)
                put("durationMs", duration)
                put("isAlac", isAlac)
            }
            metaCachePrefs.edit().putString(cacheKey, obj.toString()).apply()
        } catch (_: Exception) {}

        return track
    }

    fun getTrackById(id: String): WebTrack? {
        ensureTracksLoaded()
        return sharedTracksMap[id] ?: getDemoTracks().find { it.id == id }
    }

    suspend fun deleteTrack(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val track = getTrackById(trackId) ?: return@withContext false

        var physicallyDeleted = false
        val uri = Uri.parse(track.uriString)

        try {
            // 1. Resolve physical filesystem path
            var filePath: String? = track.filePath
            if (filePath.isNullOrEmpty()) {
                if (track.uriString.startsWith("file://")) {
                    filePath = uri.path
                } else if (track.uriString.startsWith("content://media/")) {
                    try {
                        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            arrayOf(
                                MediaStore.Audio.Media.DATA,
                                MediaStore.Audio.Media.RELATIVE_PATH,
                                MediaStore.Audio.Media.DISPLAY_NAME
                            )
                        } else {
                            arrayOf(MediaStore.Audio.Media.DATA)
                        }
                        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                                if (dataIdx >= 0) {
                                    val d = c.getString(dataIdx)
                                    if (!d.isNullOrEmpty()) filePath = d
                                }
                                if (filePath.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val relIdx = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                                    val nameIdx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                                    val rel = if (relIdx >= 0) c.getString(relIdx) else null
                                    val name = if (nameIdx >= 0) c.getString(nameIdx) else track.fileName
                                    if (!rel.isNullOrEmpty() && !name.isNullOrEmpty()) {
                                        filePath = "/storage/emulated/0/${rel.trimStart('/')}$name"
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not query MediaStore path: ${e.message}")
                    }
                } else if (track.uriString.startsWith("content://com.android.externalstorage") ||
                    track.uriString.contains("/document/") ||
                    track.uriString.contains("/tree/")
                ) {
                    try {
                        val docId = android.provider.DocumentsContract.getDocumentId(uri)
                        if (docId.startsWith("primary:")) {
                            filePath = "/storage/emulated/0/" + docId.removePrefix("primary:")
                        } else if (docId.contains(":")) {
                            val parts = docId.split(":", limit = 2)
                            val candidate = File("/storage/${parts[0]}/${parts[1]}")
                            if (candidate.exists()) filePath = candidate.absolutePath
                        }
                    } catch (_: Exception) {}
                }
            }

            // Fallback: If filePath is still null or does not exist, search standard audio locations
            if (filePath.isNullOrEmpty() || !File(filePath!!).exists()) {
                val candidateDirs = listOfNotNull(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    File("/storage/emulated/0/Music"),
                    File("/storage/emulated/0/Download")
                )
                for (dir in candidateDirs) {
                    val candidate = File(dir, track.fileName)
                    if (candidate.exists()) {
                        filePath = candidate.absolutePath
                        break
                    }
                }
            }

            // 2. Physical file deletion on host filesystem
            if (!filePath.isNullOrEmpty()) {
                try {
                    val file = File(filePath!!)
                    if (file.exists()) {
                        if (file.delete() || !file.exists()) {
                            physicallyDeleted = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "File.delete error: ${e.message}")
                }
            }

            // 3. SAF DocumentContract & DocumentFile deletion
            if (!physicallyDeleted && (
                track.uriString.startsWith("content://com.android.externalstorage") ||
                track.uriString.startsWith("content://com.android.providers") ||
                track.uriString.contains("/tree/") ||
                track.uriString.contains("/document/")
            )) {
                try {
                    if (android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                        physicallyDeleted = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DocumentsContract delete: ${e.message}")
                }
                if (!physicallyDeleted) {
                    try {
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        if (doc != null && doc.delete()) {
                            physicallyDeleted = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "DocumentFile delete: ${e.message}")
                    }
                }
            }

            // 4. Check if any mounted SAF folder has write access to this file
            if (!physicallyDeleted) {
                val safLocations = getSavedLocations().filter { !it.isSystemLibrary }
                for (loc in safLocations) {
                    try {
                        val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(loc.uriString)) ?: continue
                        val targetDoc = findFileInDocumentTree(rootDoc, track.fileName)
                        if (targetDoc != null && (targetDoc.delete() ||
                            android.provider.DocumentsContract.deleteDocument(context.contentResolver, targetDoc.uri))
                        ) {
                            physicallyDeleted = true
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            // 5. ContentResolver deletion for MediaStore items
            if (track.uriString.startsWith("content://media/")) {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        physicallyDeleted = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ContentResolver delete: ${e.message}")
                }
            }

            // 6. Purge from MediaStore database and trigger rescan if physically deleted
            if (physicallyDeleted) {
                try {
                    context.contentResolver.delete(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                        arrayOf(track.fileName)
                    )
                } catch (_: Exception) {}
                if (!filePath.isNullOrEmpty()) {
                    try {
                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(filePath),
                            null,
                            null
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during physical file delete: ${e.message}", e)
        }

        if (physicallyDeleted || track.uriString.startsWith("demo://")) {
            sharedTracksMap.remove(trackId)
            thumbnailLruCache.remove("${trackId}_80")
            thumbnailLruCache.remove("${trackId}_96")
            saveTracksToDiskCache(sharedTracksMap.values.toList())

            val locs = getSavedLocations().toMutableList()
            val locIdx = locs.indexOfFirst { it.id == track.locationId }
            if (locIdx >= 0) {
                val curCount = locs[locIdx].trackCount
                locs[locIdx] = locs[locIdx].copy(trackCount = maxOf(0, curCount - 1))
                saveLocations(locs)
            }
            return@withContext true
        }

        return@withContext false
    }

    private fun findFileInDocumentTree(dir: DocumentFile, targetFileName: String): DocumentFile? {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                val found = findFileInDocumentTree(child, targetFileName)
                if (found != null) return found
            } else if (child.name.equals(targetFileName, ignoreCase = true)) {
                return child
            }
        }
        return null
    }

    fun openTrackInputStream(track: WebTrack): InputStream? {
        if (track.uriString.startsWith("demo://")) {
            return getDemoAudioStream(track.format)
        }
        return try {
            val uri = Uri.parse(track.uriString)
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open stream for ${track.uriString}", e)
            null
        }
    }

    fun openTrackFileDescriptor(track: WebTrack): ParcelFileDescriptor? {
        if (track.uriString.startsWith("demo://")) return null
        return try {
            val uri = Uri.parse(track.uriString)
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open file descriptor for ${track.uriString}", e)
            null
        }
    }

    fun openDecodedWavStream(track: WebTrack, startMs: Long = 0L): InputStream? {
        if (track.uriString.startsWith("demo://")) {
            return getDemoAudioStream("wav")
        }

        try {
            val uri = Uri.parse(track.uriString)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null

            val extractor = MediaExtractor()
            extractor.setDataSource(pfd.fileDescriptor)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                extractor.release()
                pfd.close()
                return openTrackInputStream(track)
            }

            extractor.selectTrack(audioTrackIndex)
            if (startMs > 0) {
                extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

            Thread {
                try {
                    writeStreamingWavHeader(pipedOut, sampleRate, channelCount)

                    val bufferInfo = MediaCodec.BufferInfo()
                    var isEOS = false

                    while (!isEOS) {
                        val inIndex = decoder.dequeueInputBuffer(10000)
                        if (inIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inIndex) ?: continue
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }

                        var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                        while (outIndex >= 0) {
                            val outputBuffer = decoder.getOutputBuffer(outIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(chunk)
                                pipedOut.write(chunk)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                        }
                    }
                    pipedOut.flush()
                } catch (_: Exception) {
                } finally {
                    try { pipedOut.close() } catch (_: Exception) {}
                    try { decoder.stop(); decoder.release() } catch (_: Exception) {}
                    try { extractor.release() } catch (_: Exception) {}
                    try { pfd.close() } catch (_: Exception) {}
                }
            }.start()

            return pipedIn
        } catch (e: Exception) {
            Log.e(TAG, "Decoded WAV stream error, fallback to direct stream", e)
            return openTrackInputStream(track)
        }
    }

    private fun writeStreamingWavHeader(out: java.io.OutputStream, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = 0xFF.toByte(); header[5] = 0xFF.toByte(); header[6] = 0xFF.toByte(); header[7] = 0x7F.toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = 0xFF.toByte(); header[41] = 0xFF.toByte(); header[42] = 0xFF.toByte(); header[43] = 0x7F.toByte()

        out.write(header)
    }

    fun getAlbumArtThumbnail(track: WebTrack, targetSize: Int = 80): ByteArray {
        val cacheKey = "${track.id}_$targetSize"
        thumbnailLruCache.get(cacheKey)?.let { return it }

        val thumbDir = File(context.cacheDir, "thumbs").apply { if (!exists()) mkdirs() }
        val safeFileName = cacheKey.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".jpg"
        val diskThumbFile = File(thumbDir, safeFileName)
        if (diskThumbFile.exists() && diskThumbFile.length() > 0L) {
            try {
                val bytes = diskThumbFile.readBytes()
                if (bytes.isNotEmpty()) {
                    thumbnailLruCache.put(cacheKey, bytes)
                    return bytes
                }
            } catch (_: Exception) {}
        }

        fun decodeAndScaleToJpeg(rawBytes: ByteArray): ByteArray? {
            if (rawBytes.isEmpty()) return null
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            var sampleSize = 1
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            while (maxDim / (sampleSize * 2) >= targetSize) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
            val scaled = if (bitmap.width > targetSize || bitmap.height > targetSize) {
                val scale = targetSize.toFloat() / maxOf(bitmap.width, bitmap.height)
                val dstW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val dstH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, dstW, dstH, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 68, bos)
            scaled.recycle()
            return bos.toByteArray()
        }

        if (!track.uriString.startsWith("demo://") && !noArtCache.contains(track.id)) {
            val mmr = MediaMetadataRetriever()
            try {
                val uri = Uri.parse(track.uriString)
                val rawArt = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    mmr.setDataSource(pfd.fileDescriptor)
                    mmr.embeddedPicture
                }
                if (rawArt != null && rawArt.isNotEmpty()) {
                    val thumbBytes = decodeAndScaleToJpeg(rawArt)
                    if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                        thumbnailLruCache.put(cacheKey, thumbBytes)
                        try { diskThumbFile.writeBytes(thumbBytes) } catch (_: Exception) {}
                        return thumbBytes
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }

            try {
                val trackIdNum = track.id.removePrefix("media_").toLongOrNull()
                val cursor = if (trackIdNum != null) {
                    context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                        "${MediaStore.Audio.Media._ID} = ?",
                        arrayOf(trackIdNum.toString()),
                        null
                    )
                } else {
                    context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                        "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                        arrayOf(track.fileName),
                        null
                    )
                }
                val albumId = cursor?.use { if (it.moveToFirst()) it.getLong(0) else null }
                if (albumId != null && albumId > 0) {
                    val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                    context.contentResolver.openInputStream(artUri)?.use { input ->
                        val rawBytes = input.readBytes()
                        val thumbBytes = decodeAndScaleToJpeg(rawBytes)
                        if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                            thumbnailLruCache.put(cacheKey, thumbBytes)
                            try { diskThumbFile.writeBytes(thumbBytes) } catch (_: Exception) {}
                            return thumbBytes
                        }
                    }
                }
            } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val uri = Uri.parse(track.uriString)
                    val bm = context.contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
                    val bos = ByteArrayOutputStream()
                    bm.compress(Bitmap.CompressFormat.JPEG, 68, bos)
                    bm.recycle()
                    val bytes = bos.toByteArray()
                    if (bytes.isNotEmpty()) {
                        thumbnailLruCache.put(cacheKey, bytes)
                        try { diskThumbFile.writeBytes(bytes) } catch (_: Exception) {}
                        return bytes
                    }
                } catch (_: Exception) {}
            }

            noArtCache.add(track.id)
        }

        val defaultThumb = generateDefaultThumbnail(track, targetSize)
        thumbnailLruCache.put(cacheKey, defaultThumb)
        return defaultThumb
    }

    private fun generateDefaultThumbnail(track: WebTrack, size: Int): ByteArray {
        val bm = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val canvas = Canvas(bm)

        val hash = (track.title + track.artist).hashCode()
        val hue = (Math.abs(hash) % 360).toFloat()
        val c1 = Color.HSVToColor(floatArrayOf(hue, 0.70f, 0.45f))
        val c2 = Color.HSVToColor(floatArrayOf((hue + 45f) % 360f, 0.85f, 0.20f))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        paint.shader = shader
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        paint.shader = null
        paint.color = Color.WHITE
        paint.textSize = size * 0.42f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD

        val letter = (track.title.firstOrNull { it.isLetterOrDigit() } ?: '♪').uppercaseChar().toString()
        val yPos = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(letter, size / 2f, yPos, paint)

        val bos = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        bm.recycle()
        return bos.toByteArray()
    }

    private fun getDemoTracks(): List<WebTrack> {
        return listOf(
            WebTrack(
                id = "demo_alac_1",
                title = "Aurora Borealis (Apple Lossless)",
                artist = "Solar Symphony",
                album = "Cosmic Waves (2024)",
                durationMs = 214000,
                sizeBytes = 28420000,
                format = "m4a",
                uriString = "demo://sample_alac.m4a",
                fileName = "Aurora Borealis.m4a",
                locationId = "demo_loc",
                isAlac = true
            ),
            WebTrack(
                id = "demo_flac_2",
                title = "Midnight Starlight (FLAC Studio)",
                artist = "Celestial Ensemble",
                album = "Starlight Odyssey",
                durationMs = 186000,
                sizeBytes = 34500000,
                format = "flac",
                uriString = "demo://sample_flac.flac",
                fileName = "Midnight Starlight.flac",
                locationId = "demo_loc",
                isAlac = false
            ),
            WebTrack(
                id = "demo_mp3_3",
                title = "Neon Highway Groove",
                artist = "Cyber Pulse",
                album = "Night City Radio",
                durationMs = 175000,
                sizeBytes = 7200000,
                format = "mp3",
                uriString = "demo://sample_mp3.mp3",
                fileName = "Neon Highway Groove.mp3",
                locationId = "demo_loc",
                isAlac = false
            )
        )
    }

    private fun getDemoAudioStream(format: String): InputStream {
        val sampleRate = 44100
        val durationSec = 15
        val numSamples = sampleRate * durationSec
        val bytes = ByteArrayOutputStream()

        val dataSize = numSamples * 2 * 2
        val totalSize = 36 + dataSize

        bytes.write("RIFF".toByteArray())
        bytes.write(intToBytes(totalSize))
        bytes.write("WAVE".toByteArray())
        bytes.write("fmt ".toByteArray())
        bytes.write(intToBytes(16))
        bytes.write(shortToBytes(1))
        bytes.write(shortToBytes(2))
        bytes.write(intToBytes(sampleRate))
        bytes.write(intToBytes(sampleRate * 2 * 2))
        bytes.write(shortToBytes(4))
        bytes.write(shortToBytes(16))
        bytes.write("data".toByteArray())
        bytes.write(intToBytes(dataSize))

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val freqL = 440.0 * Math.sin(t * 0.5) + 330.0
            val freqR = 554.37 * Math.cos(t * 0.4) + 440.0
            val env = Math.min(1.0, Math.sin(Math.PI * (i % (sampleRate * 2)) / (sampleRate * 2)))
            val sampleValL = (Math.sin(2.0 * Math.PI * freqL * t) * 8000.0 * env).toInt().toShort()
            val sampleValR = (Math.sin(2.0 * Math.PI * freqR * t) * 8000.0 * env).toInt().toShort()
            bytes.write(sampleValL.toInt() and 0xff)
            bytes.write((sampleValL.toInt() shr 8) and 0xff)
            bytes.write(sampleValR.toInt() and 0xff)
            bytes.write((sampleValR.toInt() shr 8) and 0xff)
        }

        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    )

    private fun shortToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte()
    )
}
