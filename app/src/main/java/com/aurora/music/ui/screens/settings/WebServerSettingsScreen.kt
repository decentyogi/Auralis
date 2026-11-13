package com.aurora.music.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.WebServerPrefs
import com.aurora.music.webserver.AuroraWebServerService
import com.aurora.music.webserver.NetworkUtils
import com.aurora.music.webserver.QrCodeView
import com.aurora.music.webserver.WebServerStorageBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WebServerSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as? AuroraApplication
    val settingsStore = app?.container?.settingsStore
    val scope = rememberCoroutineScope()

    val webPrefs by settingsStore?.webServerPrefs?.collectAsStateWithLifecycle(WebServerPrefs())
        ?: remember { mutableStateOf(WebServerPrefs()) }

    val isRunning by AuroraWebServerService.isRunning.collectAsStateWithLifecycle()
    val serverUrl by AuroraWebServerService.serverUrl.collectAsStateWithLifecycle()
    val activePort by AuroraWebServerService.activePort.collectAsStateWithLifecycle()

    val bridge = remember { WebServerStorageBridge(context) }
    var currentToken by remember { mutableStateOf(bridge.getAccessToken()) }
    var allowDelete by remember { mutableStateOf(bridge.isDeleteAllowed()) }

    val localIp = remember(isRunning) { NetworkUtils.getLocalIpAddress(context) }
    val effectiveUrl = serverUrl ?: "http://$localIp:$activePort/?token=$currentToken"

    var copiedRecently by remember { mutableStateOf(false) }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(2000L)
            copiedRecently = false
        }
    }

    val tracks = remember { bridge.getCachedTracks() }
    val totalTracks = tracks.size
    val losslessTracks = remember(tracks) {
        tracks.count { it.format.equals("flac", ignoreCase = true) || it.isAlac || it.format.equals("m4a", ignoreCase = true) }
    }
    val totalBytes = remember(tracks) { tracks.sumOf { it.sizeBytes } }
    val totalSizeFormatted = remember(totalBytes) {
        val gb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val mb = totalBytes.toDouble() / (1024.0 * 1024.0)
        if (gb >= 1.0) "%.1f GB".format(gb) else "%d MB".format(mb.toLong())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Web Streaming Server", onBack = onBack)

        SettingsSectionTitle("Server Control")
        SettingsGroup {
            SettingsSwitchRow(
                icon = Icons.Default.Wifi,
                title = "Local Music Server",
                subtitle = if (isRunning) "Server is active on local Wi-Fi" else "Serve music library to browsers on local Wi-Fi",
                checked = isRunning,
                onCheckedChange = { enable ->
                    scope.launch { settingsStore?.setWebServerEnabled(enable) }
                    val intent = Intent(context, AuroraWebServerService::class.java).apply {
                        action = if (enable) AuroraWebServerService.ACTION_START else AuroraWebServerService.ACTION_STOP
                    }
                    if (enable) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    } else {
                        context.startService(intent)
                    }
                }
            )
        }

        if (isRunning) {
            SettingsSectionTitle("Network Access URL")
            SettingsGroup {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "STREAMING URL (WITH TOKEN)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = effectiveUrl,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Aurora Server URL", effectiveUrl))
                                copiedRecently = true
                                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = if (copiedRecently) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = "Copy URL",
                                tint = if (copiedRecently) Color(0xFF38EF7D) else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effectiveUrl))
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open in Browser")
                        }

                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Aurora Music Stream")
                                    putExtra(Intent.EXTRA_TEXT, "Listen to my lossless music library here: $effectiveUrl")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Aurora Server URL"))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share URL")
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        text = "SCAN QR CODE TO CONNECT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        contentAlignment = Alignment.Center
                    ) {
                        QrCodeView(
                            content = effectiveUrl,
                            size = 180.dp,
                            darkColor = Color(0xFF0A101A),
                            lightColor = Color.White
                        )
                    }
                }
            }
        }

        SettingsSectionTitle("Security & Authorization")
        SettingsGroup {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "ACCESS TOKEN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = currentToken,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            val newToken = bridge.regenerateAccessToken()
                            currentToken = newToken
                            scope.launch { settingsStore?.setWebServerToken(newToken) }
                            Toast.makeText(context, "Access token regenerated", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Regenerate")
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Restricted to local Wi-Fi / private LAN addresses. The random access token is required on every request to prevent unauthorized access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SettingsRowDivider()
            SettingsSwitchRow(
                icon = Icons.Default.Delete,
                title = "Allow Web File Deletion",
                subtitle = if (allowDelete) "Clients on Wi-Fi can delete tracks from storage" else "File deletion disabled by default for safety",
                checked = allowDelete,
                onCheckedChange = { allowed ->
                    allowDelete = allowed
                    bridge.setDeleteAllowed(allowed)
                    scope.launch { settingsStore?.setWebServerAllowDelete(allowed) }
                }
            )
        }

        SettingsSectionTitle("Library Status")
        SettingsGroup {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Total Available Tracks", style = MaterialTheme.typography.bodyMedium)
                    Text("$totalTracks tracks", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lossless (FLAC / ALAC)", style = MaterialTheme.typography.bodyMedium)
                    Text("$losslessTracks tracks", fontWeight = FontWeight.Bold, color = Color(0xFF38EF7D))
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Library Storage Size", style = MaterialTheme.typography.bodyMedium)
                    Text(totalSizeFormatted, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Active Port", style = MaterialTheme.typography.bodyMedium)
                    Text("$activePort", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()
        ) {
            SettingsSectionTitle("Storage Access")
            SettingsGroup {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "All Files Access Required for Deletion",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "To allow deleting audio tracks from the web browser interface, Android requires 'All Files Access' permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    val fallback = Intent(
                                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallback)
                                } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant All Files Access")
                    }
                }
            }
        }

        SettingsSectionTitle("About Web Studio")
        SettingsGroup {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Aurora Web Studio allows any browser on your Wi-Fi network to stream your music collection with zero file copying.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Features high-performance HTTP range seeking for FLAC, MP3, and AAC, plus seamless hardware/software decoding to uncompressed WAV for ALAC audio tracks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}
