# TARGET MAP: Aurora Music Player Architecture & Integration Blueprint

## 1. Module Layout
`Aurora` is a modern multi-module Kotlin project:
- **`:app`**: Android application module containing the Jetpack Compose UI, Android Application entry point (`AuroraApplication`), Activity (`MainActivity`), Android services (`PlaybackService`, `AuroraWidgetReceiver`, `TileService`), ViewModels, and Android-specific implementations.
- **`:core`**: Core multiplatform/Kotlin business logic: repository, database, models (`Song`, `Album`, `Artist`), tag parsing, audio processing models, and networking abstractions.
- **`:decent` (`:decent-usb-audio-wrapper-media3`)**: Experimental USB bit-perfect audio driver (vendored, MIT license).
- **`:desktop`**: Desktop Compose UI runner for Linux/macOS/Windows.
- **`:extension-sdk`**: Plugin and extension API.
- **`:native`**: CMake and native C++ AcoustID/Chromaprint audio fingerprinting code.

---

## 2. Architecture, DI, Navigation & Settings Patterns

### Dependency Injection & Service Locator:
- Aurora uses an explicit Application-scoped container: `AppContainer` accessed via `(context.applicationContext as AuroraApplication).container`.
- It holds repositories (`SongRepository`, `LocalLibrary`), settings stores (`SettingsStore`), and playback managers.

### Navigation:
- Navigation routes are centralized in `com.aurora.music.navigation.Routes`.
- Settings routes are defined as `Routes.SETTINGS_*`.
- Destination models are declared in `SettingsDestinations.kt` (`SettingsDestination(route, labelRes, descriptionRes)`).
- Settings screens live under `com.aurora.music.ui.screens.settings`.

### Compose UI Patterns:
- Design system: Dark / AMOLED theme with neon accents, liquid glassmorphic cards (`LiquidGlass`), rounded pill tags, and animated state visibility.
- Compose components follow Material 3 with Aurora's typography and color tokens (`AuroraTheme`, `TextPrimary`, `TextSecondary`).

---

## 3. Storage, MediaStore & Library Access
- Local music tracks in Aurora are scanned via `LocalLibrary.kt` and `AndroidStorage.kt` using `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`.
- Aurora models music tracks as `Song` (`core/src/main/kotlin/com/aurora/music/model/Song.kt`), each with `id`, `title`, `artist`, `album`, `durationSec`, `uri`, `path`, `mimeType`, and format metadata.
- Track resolution from library to file descriptor / stream utilizes `context.contentResolver.openFileDescriptor(uri, "r")`.

---

## 4. Analysis of Existing Embedded Network Servers & Decision

### Existing Implementations in Target:
1. **`EndpointServer` (Aurora Connect / mTLS)**:
   - Location: `core/src/main/kotlin/com/aurora/music/playback/network/endpoint/EndpointServer.kt`.
   - Protocol: Strict mutual-TLS HTTPS with pinned controller certificates (`trust = RendererTrustStore(context).controllers()`).
   - Purpose: Encrypted inter-device remote control RPC (`/command`, `/status`, pairing handshake).
   - Constraints: Requires TLS client certificate authentication and pairing approval. Web browsers navigating to this endpoint directly encounter an SSL protocol error (missing client certificate / untrusted self-signed root).
2. **`BoundedHttpServer`**:
   - Location: `core/src/main/kotlin/com/aurora/music/playback/network/BoundedHttpServer.kt`.
   - Constraints: Hardcoded `maxBodyBytes in 0..1_048_576` (1 MB max), `maxConcurrentRequests in 1..16`, and strict deadline timeouts (30s).
   - Not suitable for continuous audio streaming or multiple concurrent media connections.
3. **`ScopedMediaServer` (DLNA / Cast Media Grant Server)**:
   - Location: `core/src/main/kotlin/com/aurora/music/playback/network/ScopedMediaServer.kt`.
   - Protocol: Tokenized single-track HTTP server (`/media/<token>`) using `BoundedHttpServer`.
   - Purpose: Grants ephemeral (30-minute) access tokens to external DLNA/Cast renderers for individual playing tracks.
   - Constraints: Hardcoded limit of 100 concurrent track grants (`check(entries.size < 100)`), no library discovery API, no web UI assets, no on-the-fly transcoding for unplayable codecs (e.g. ALAC to PCM WAV).

### Architectural Decision:
- **Run Separately in `com.aurora.music.webserver`**:
  - The Web Streaming Server will be implemented as a dedicated, self-contained component matching `Aurora_Final`'s battle-tested design.
  - **Justification**:
    1. Zero risk of breaking Aurora's existing encrypted Connect protocol, Cast framework, or DLNA features.
    2. Zero overhead when the web server is toggled off.
    3. Handles standard HTTP/1.1 Range requests (`206 Partial Content`, `416 Range Not Satisfiable`), open-ended streams, and direct browser connections on local Wi-Fi without SSL certificate warnings.
    4. Provides full REST endpoints (`/api/status`, `/api/tracks`, `/api/locations`, `/api/stream`, `/api/cover`, `/api/track`) and serves the complete browser Studio web app.
    5. Port selection: default `8080`, with auto-fallback to open ports if busy.

---

## 5. Codec, Transcoding & Native Binary Analysis
- **Target's Bundled Decoders**:
  - `org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1`: Decoder-only for ExoPlayer playback (float32 output). It is NOT an encoder or multiplexer.
  - AcoustID Chromaprint (`native/`): C++ audio fingerprinter, compiled via CMake (`libaurora_precision_benchmark.so`).
- **Streaming Transcoding Solution**:
  - Android prohibits executing standalone ELF binaries from app data directories on modern Android (Android 10+ W^X security policy).
  - Like `Aurora_Final`, our streaming transcode path uses Android's native hardware/software decoders via `MediaExtractor` and `MediaCodec`.
  - When browser requests `mode=decoded`, `MediaCodec` decodes the ALAC or FLAC file to 16-bit PCM, wraps it in a streaming 44-byte WAV header, and streams it over a `PipedOutputStream` in 64 KB chunks.
  - No external FFmpeg binary or GPL/LGPL binary execution required, zero APK size bloat, and fully compatible with modern scoped storage and targetSdk 35.

---

## 6. Integration Surface & Minimal Touch Points
To adhere strictly to Non-Negotiable #1 (Additive only) and #4 (parities and surgical changes):
1. **New Package**: `com.aurora.music.webserver`
   - `AuroraWebServer.kt` (HTTP server engine with Range streaming, WAV decoding, and API routes)
   - `AuroraWebServerService.kt` (Foreground Service with typed `dataSync`, notification, WakeLock & WifiLock)
   - `WebServerController.kt` (StateFlow holder for server status, URL, IP, and library track bridge)
   - `WebServerSettingsScreen.kt` (Dedicated Compose settings screen matching Aurora's liquid glassmorphic UI)
2. **Assets**: Copy verbatim `Aurora_Final`'s browser UI into `app/src/main/assets/web/index.html`.
3. **Settings Entry**: Add `webServer` destination to `SettingsDestinations.kt`, wire navigation in `SettingsScreen.kt`, and route in `MainActivity.kt`.
4. **Manifest**: Register `AuroraWebServerService` with `foregroundServiceType="dataSync"`, ensure `android:usesCleartextTraffic="true"`.
