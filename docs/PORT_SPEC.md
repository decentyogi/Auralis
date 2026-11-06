# PORT SPECIFICATION: Aurora_Final Streaming Server -> Aurora Music Player

## 1. Overview & Stack Discovery
This specification documents every file, route, behavior, buffer parameter, transcode pipeline, and bug workaround discovered in `Aurora_Final` (commit HEAD) to be ported into `Aurora` (`Aurora_With_Server`).

### Source Application Stack (`Aurora_Final`):
- **Language**: Kotlin 2.0+
- **Architecture**: Single Activity (`MainActivity`), Jetpack Compose UI, MVVM (`AuroraViewModel`), Android Foreground Service (`AuroraServerService`), custom embedded multithreaded HTTP/1.1 socket server (`AuroraHttpServer`).
- **Build & Dependencies**: Gradle with Kotlin DSL, `compileSdk 36`, `minSdk 24`, `targetSdk 36`, `versionCode 1`, `versionName "1.0"`. Uses OkHttp (client only for other tests), Moshi, Coroutines Android, DocumentFile, MediaMetadataRetriever, MediaExtractor, MediaCodec.
- **Native Binaries / Bundled Libraries**: None bundled. Transcoding and playback decode relies on Android platform hardware/software codecs via Android Media Framework (`MediaExtractor` + `MediaCodec`). No external FFmpeg binary or shared object was used in `Aurora_Final`.
- **Permissions**:
  - `android.permission.INTERNET`
  - `android.permission.ACCESS_NETWORK_STATE`
  - `android.permission.ACCESS_WIFI_STATE`
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_DATA_SYNC` (Android 14+ typed foreground service)
  - `android.permission.POST_NOTIFICATIONS` (Android 13+ runtime permission)
  - `android.permission.READ_MEDIA_AUDIO` (Android 13+)
  - `android.permission.READ_EXTERNAL_STORAGE` (maxSdkVersion 32)
  - `android.permission.WRITE_EXTERNAL_STORAGE` (maxSdkVersion 29)
  - `android.permission.MANAGE_EXTERNAL_STORAGE` (tools:ignore="ScopedStorage" for physical file operations)
- **Manifest Config**:
  - `android:usesCleartextTraffic="true"` (essential for local LAN HTTP streaming without requiring self-signed SSL certs)
  - `android:requestLegacyExternalStorage="true"`
  - Service: `com.example.server.AuroraServerService` with `android:foregroundServiceType="dataSync"` and `android:exported="false"`.

---

## 2. HTTP Server Architecture (`AuroraHttpServer`)

### Server Engine Details:
- **Core Technology**: Pure Java/Kotlin `ServerSocket` with unbounded cached thread pool (`Executors.newCachedThreadPool()`).
- **Port Selection**: Default port `8080`. Bound via `ServerSocket(port)`. `activePort` dynamically resolves `serverSocket?.localPort ?: port` to handle system port fallback.
- **TCP Socket Optimization**:
  - `socket.tcpNoDelay = true` (Disables Nagle's algorithm for immediate packet dispatch; critical for audio streaming latency).
  - `socket.sendBufferSize = 64 * 1024` (64 KB OS send buffer prevents TCP pipeline stalls).
  - `socket.soTimeout = 20000` (20-second read timeout for client connections).
- **Buffered I/O**:
  - `BufferedOutputStream(socket.getOutputStream(), 64 * 1024)`: 64 KB output stream buffering for kernel block alignment.
  - `BufferedReader(InputStreamReader(socket.getInputStream()))` for request header parsing.

### Routes, Methods & Headers:
| Route | Method | MIME Type | Description |
|---|---|---|---|
| `/` or `/index.html` | GET | `text/html; charset=UTF-8` | Serves web player HTML/JS/CSS assets bundled in app assets (`web/index.html`). |
| `/api/status` | GET | `application/json; charset=UTF-8` | JSON object: `status` ("online"), `port`, `ip`, `uptimeSeconds`, `requestCount`, `locationCount`. |
| `/api/tracks` | GET | `application/json; charset=UTF-8` | Instant retrieval from in-memory cache: array of track objects (`id`, `title`, `artist`, `album`, `durationMs`, `durationFormatted`, `sizeBytes`, `sizeFormatted`, `format`, `fileName`, `isAlac`). |
| `/api/locations` | GET | `application/json; charset=UTF-8` | Returns configured music directories: `id`, `name`, `trackCount`, `isSystemLibrary`. |
| `/api/stream` | GET | Dynamic: `audio/flac`, `audio/mp4`, `audio/mpeg`, `audio/wav`, `audio/ogg`, `audio/aac` | Audio streaming engine with 2 modes: direct range stream vs. decoded hardware WAV. |
| `/api/cover` | GET | `image/jpeg` | Album art JPEG thumbnail (80x80) or full art. `Cache-Control: public, max-age=604800, immutable`. |
| `/api/track` | DELETE | `application/json; charset=UTF-8` | Physical file deletion from Android storage with fallback to SAF and MediaStore purge. |
| `*` | OPTIONS | N/A | CORS preflight handler returning status 204 No Content. |

### CORS & Response Headers:
- `Access-Control-Allow-Origin: *`
- `Access-Control-Allow-Methods: GET, DELETE, OPTIONS`
- `Access-Control-Allow-Headers: Range, Content-Type, Accept`
- `Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges`
- `Connection: close`
- Static assets / streams: `Cache-Control: public, max-age=3600`
- Album covers: `Cache-Control: public, max-age=604800, immutable`
- Status / API responses: `Cache-Control: public, max-age=60`

### HTTP Range & Partial Content Protocol:
- Supports `bytes=start-end`, open-ended `bytes=start-`, and suffix `bytes=-suffixLen` (e.g., last 500 bytes).
- Standard Range Response:
  - Status: `206 Partial Content`
  - `Content-Range: bytes <start>-<end>/<totalBytes>`
  - `Content-Length: <end - start + 1>`
  - `Accept-Ranges: bytes`
- Invalid Range Request (`start >= totalBytes`):
  - Status: `416 Range Not Satisfiable`
  - `Content-Range: bytes */<totalBytes>`
  - `Content-Length: 0`
- Zero-copy / Fast Kernel Seek:
  - `channel.position(start)` directly skips file pointer on `FileInputStream.channel` in O(1) time without reading intermediate bytes.
  - Stream chunk buffer: 64 KB (`ByteArray(64 * 1024)`) in loop, respecting `contentLength` to prevent over-reading.

---

## 3. The ALAC/FLAC Path & Decoded WAV Fallback

### Problem Solved:
Native web browsers (especially Chromium on Android/Windows/Linux) do not natively play ALAC (`audio/mp4` with `alac` fourcc) out-of-the-box. Attempting to play ALAC directly causes HTML5 `<audio>` element `MediaError` or silent stall.

### Dual-Path Engine:
1. **Direct Range Stream (`mode=direct` or default)**:
   - For FLAC, MP3, AAC, OGG, WAV, and ALAC on compatible clients (e.g. Safari / Apple WebKit).
   - Fast O(1) byte seeking via `FileChannel.position(start)` or `skipFully(inStream, start)`.
   - MIME mapping:
     - `flac` -> `audio/flac`
     - `m4a`, `alac` -> `audio/mp4`
     - `mp3` -> `audio/mpeg`
     - `wav` -> `audio/wav`
     - `ogg`, `opus` -> `audio/ogg`
     - `aac` -> `audio/aac`
2. **Decoded Lossless PCM WAV Stream (`mode=decoded&seek=<sec>`)**:
   - Explicitly requested via query parameter or automatically triggered when browser triggers `nativeAudio.onerror`.
   - Uses Android's `MediaExtractor` to demux the audio track and `MediaCodec.createDecoderByType(mime)` to decode native ALAC/FLAC into 16-bit uncompressed PCM.
   - Piped Streaming: A `PipedOutputStream` connected to a `PipedInputStream` with a 64 KB buffer transmits decoded chunks asynchronously.
   - Streaming WAV Header: Synthesizes a 44-byte RIFF/WAVE PCM header on the fly with 0x7FFFFFFF data size to allow continuous live streaming.
   - Seek Handling in Decoded Mode: Ingests `seek=<seconds>` parameter, performs `extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)`, and resets stream offset. The browser UI tracks `decodedSeekOffset + nativeAudio.currentTime` seamlessly.
   - Cleanup: Proper lifecycle release in `finally` blocks (`pipedOut.close()`, `decoder.stop()`, `decoder.release()`, `extractor.release()`, `pfd.close()`).

---

## 4. Android Lifecycle & Background Execution
- **Foreground Service**: `AuroraServerService` runs as a Foreground Service with notification channel `aurora_server_channel` (Importance: Low).
- **Service Type**: `foregroundServiceType="dataSync"` for Android 14+ compatibility.
- **Notification**:
  - Title: `"AURORA Music Server Active"`
  - Text: `"Access player at http://<ip>:<port>"`
  - Ongoing, with intent to return to MainActivity, and "Stop Server" action button (`PendingIntent` calling `ACTION_STOP`).
- **Power & Network Locks**:
  - WakeLock: `PowerManager.PARTIAL_WAKE_LOCK` to prevent CPU sleep while streaming.
  - Wi-Fi Lock: `WifiManager.WIFI_MODE_FULL_HIGH_PERF` to maintain high-performance Wi-Fi connection while server is active.
- **Network Interface IP Resolution (`NetworkUtils`)**:
  - Primary: `WifiManager.getConnectionInfo().getIpAddress()` converted to dotted quad format.
  - Secondary fallback: Enumerate `NetworkInterface.getNetworkInterfaces()` filtering for non-loopback, active `wlan*` or `ap*` (hotspot) IPv4 addresses.
  - Final fallback: Any non-loopback IPv4 address; default `127.0.0.1`.

---

## 5. Browser-Side Web UI (`app/src/main/assets/web/index.html`)
- Standalone HTML5 single-page application with responsive glassmorphic dark theme (`#06090F`, cyan neon `#00F2FE`, emerald neon `#38EF7D`, purple neon `#9D4EDD`).
- **Audio Pipeline**:
  - Web Audio API pipeline with 3-band BiquadFilter EQ (`lowshelf` 110Hz, `peaking` 1200Hz Q=1.0, `highshelf` 8500Hz) and real-time FFT visualizer (`createAnalyser()`, fftSize 128).
  - Presets: Flat, Bass, Vocal, Air, Warm.
- **Playback & Scrubber Engine**:
  - Glitch-free scrubbing: Previews seek position on mouse/touch drag, commits Range seek only on release (`mouseup` / `touchend`).
  - Seek lock: 1200ms anti-snap lock (`pendingSeekSec`, `seekLockUntil`) prevents scrubber jumping backward before browser completes network Range request.
  - Buffer progress bar calculates `buffered.start(i)` and `buffered.end(i)` matching playback position.
- **Auto-Fallback Engine**:
  - `nativeAudio.onerror` automatically swaps `src` to `/api/stream?id=...&mode=decoded` without user intervention if direct ALAC stream cannot be demuxed by the browser.
- **Album Art Thumbnail Queue**:
  - Throttled sequential loader (`MAX_CONCURRENT_THUMBS = 3`) prevents HTTP socket pool exhaustion when displaying hundreds of songs.

---

## 6. HARD-WON FIXES & Anti-Regression Invariants

| # | Fix / Magic Value | Location | Bug Prevented |
|---|---|---|---|
| 1 | `socket.tcpNoDelay = true` | `AuroraHttpServer.kt` | Nagle's algorithm delay causing 200-500ms initial audio packet latency. |
| 2 | `socket.sendBufferSize = 64 * 1024` | `AuroraHttpServer.kt` | Kernel socket buffer overflow causing stutter/stalls on high bitrate FLAC/ALAC. |
| 3 | `BufferedOutputStream(..., 64 * 1024)` | `AuroraHttpServer.kt` | Unaligned small chunk writes triggering excessive TCP packet fragmentation. |
| 4 | `channel.position(start)` | `AuroraHttpServer.kt` | O(1) kernel seek avoids reading megabytes of audio when user seeks mid-track. |
| 5 | `skipFully(inStream, start)` fallback | `AuroraHttpServer.kt` | Broken seek when streaming from ContentResolver streams where FileChannel is unavailable. |
| 6 | 44-byte WAV header with 0x7FFFFFFF size | `StorageManager.kt` | Web browsers stopping playback prematurely because decoded stream length is unknown upfront. |
| 7 | `PipedInputStream(pipedOut, 64 * 1024)` | `StorageManager.kt` | Thread deadlock between MediaCodec decoder thread and HTTP server socket writer. |
| 8 | Anti-Snap Seek Lock (`seekLockUntil = Date.now() + 1200`) | `index.html` | Scrubber bouncing back to old position during HTTP Range seek before browser buffers new segment. |
| 9 | `MAX_CONCURRENT_THUMBS = 3` thumbnail queue | `index.html` | Browser socket starvation causing audio fetch requests to stall while downloading 50+ album covers. |
| 10 | `nativeAudio.onerror` automatic fallback to `mode=decoded` | `index.html` | Complete playback failure on Chromium browsers when playing ALAC (.m4a). |
| 11 | In-memory `thumbnailLruCache(400)` + disk cache (`cacheDir/thumbs`) | `StorageManager.kt` | UI hitching and high CPU/battery load from repeatedly extracting embedded album art via MMR. |
| 12 | Path traversal rejection (`..`, encoded `%2e%2e`) | `AuroraHttpServer.kt` | Security vulnerability: malicious clients traversing outside the designated audio library. |
| 13 | UTF-8 query param decoding with safe percent decoding | `AuroraHttpServer.kt` | Broken track playback or 404 when file titles contain spaces, `#`, `&`, `'`, or non-ASCII characters. |
| 14 | MediaScanner notification on physical delete | `StorageManager.kt` | Ghost tracks remaining in system library after deletion over HTTP. |

---

## 7. Feature Acceptance Checklist
- [ ] Server OFF by default on app launch.
- [ ] "Web Server" settings entry with toggle switch, running status, copyable IP:Port URL.
- [ ] Foreground service with notification and "Stop Server" action button.
- [ ] Partial WakeLock and Wi-Fi High Perf lock acquired while server running and released on stop.
- [ ] Serves web UI at `/` and `/index.html`.
- [ ] `/api/status` returns online status, port, IP, and library stats.
- [ ] `/api/tracks` returns track list.
- [ ] Direct FLAC streaming with HTTP Range 206 support and 416 on out-of-bounds range.
- [ ] ALAC direct stream and automatic/manual `mode=decoded` WAV transcoding stream.
- [ ] Seeking on FLAC and ALAC without stalls (> 1s delay).
- [ ] Album cover art retrieval at `/api/cover`.
- [ ] Path traversal protection (rejection of `..` or unauthorized paths).
- [ ] No regression on existing Aurora playback, audio engine, or Cast features.
