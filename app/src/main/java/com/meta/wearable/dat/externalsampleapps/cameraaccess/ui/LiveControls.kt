// LiveControls - "Go Live" button and stream server settings dialog

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CameraUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveAudioSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveQuality
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveSettings
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveState

/** Row with the big Go Live / End Live pill and a settings gear. */
@Composable
fun LiveRow(
    ui: CameraUiState,
    onToggleLive: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val live = ui.isLiveActive
  val enabled = !ui.isStartingLive && (live || (ui.isStreaming && !ui.isRecording))
  val background =
      when {
        live -> AppColor.Red
        enabled -> AppColor.Red.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.08f)
      }
  val label =
      when (val s = ui.liveState) {
        LiveState.Connecting -> "Connecting…"
        is LiveState.Reconnecting -> "Reconnecting… tap to stop"
        is LiveState.Live -> if (s.bitrateKbps > 0) "LIVE · ${s.bitrateKbps} kbps" else "LIVE"
        else -> if (ui.liveSettings.url.isBlank()) "Set up stream" else "Go Live"
      }

  Row(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
        modifier =
            Modifier.weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(background)
                .clickable(enabled = enabled, onClick = onToggleLive)
                .testTag("go_live_button"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (ui.isStartingLive || ui.liveState is LiveState.Connecting) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
      } else {
        Icon(
            imageVector = if (live) Icons.Filled.Sensors else Icons.Filled.SensorsOff,
            contentDescription = null,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
          text = label,
          color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
      )
    }

    Box(
        modifier =
            Modifier.size(50.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (live) 0.08f else 0.18f))
                .clickable(enabled = !live, onClick = onOpenSettings)
                .testTag("live_settings_button"),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          imageVector = Icons.Filled.Settings,
          contentDescription = "Stream settings",
          tint = if (live) Color.White.copy(alpha = 0.45f) else Color.White,
          modifier = Modifier.size(22.dp),
      )
    }
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LiveSettingsDialog(
    initial: LiveSettings,
    onSave: (LiveSettings) -> Unit,
    onDismiss: () -> Unit,
) {
  var url by remember { mutableStateOf(initial.url) }
  var mode by remember { mutableStateOf(initial.mode) }
  var smoothing by remember { mutableStateOf(initial.smoothingMs) }
  var adaptive by remember { mutableStateOf(initial.adaptiveBitrate) }
  var quality by remember { mutableStateOf(initial.quality) }
  var fps by remember { mutableStateOf(initial.fps) }
  var bitrate by remember { mutableStateOf(initial.videoBitrateKbps) }
  var audio by remember { mutableStateOf(initial.audioSource) }
  var reconnect by remember { mutableStateOf(initial.autoReconnect) }
  val urlValid = url.isBlank() || LiveSettings.isSupportedUrl(url)
  val modeMismatch =
      mode == LiveMode.PASSTHROUGH && url.isNotBlank() && urlValid && !LiveSettings.isRtmpUrl(url)

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Stream settings") },
      text = {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          OutlinedTextField(
              value = url,
              onValueChange = { url = it },
              label = { Text("Server URL") },
              placeholder = { Text("rtmp://192.168.1.50:1935/live/glasses") },
              singleLine = true,
              isError = !urlValid,
              supportingText = {
                Text(
                    if (urlValid) "MediaMTX: rtmp://<PC IP>:1935/<any path>"
                    else "Must start with rtmp://, rtmps://, srt:// or rtsp://",
                )
              },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
              modifier = Modifier.fillMaxWidth().testTag("live_url_field"),
          )

          Section("Stream mode") {
            LiveMode.entries.forEach { m ->
              FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
            }
          }
          Text(
              text =
                  if (modeMismatch) "Original quality needs an rtmp:// URL. Pick Compatible for SRT/RTSP."
                  else if (mode == LiveMode.PASSTHROUGH)
                      "Sends the glasses' own video untouched: sharpest and lowest delay. " +
                          "In OBS, play it via rtsp://<PC IP>:8554/<path>."
                  else "Re-encodes to H.264 on the phone. Use if your player can't show H.265.",
              fontSize = 12.sp,
              color = if (modeMismatch) AppColor.Red else Color.Unspecified,
          )

          Section("Smoothing buffer (evens out stutter / fast-forward)") {
            LiveSettings.SMOOTHING_OPTIONS_MS.forEach { ms ->
              FilterChip(
                  selected = smoothing == ms,
                  onClick = { smoothing = ms },
                  label = {
                    Text(if (ms == 0) "Off" else "${ms / 1000.0}".removeSuffix(".0") + " s")
                  },
              )
            }
          }

          Section("Glasses camera quality (applies on next Preview)") {
            LiveQuality.entries.forEach { q ->
              FilterChip(
                  selected = quality == q,
                  onClick = { quality = q },
                  label = { Text(q.label) },
              )
            }
          }

          Section("Frame rate") {
            LiveSettings.FPS_OPTIONS.forEach { f ->
              FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("$f fps") })
            }
          }

          Section("Video bitrate (Compatible mode)") {
            LiveSettings.BITRATE_OPTIONS_KBPS.forEach { b ->
              FilterChip(
                  selected = bitrate == b,
                  onClick = { bitrate = b },
                  label = { Text(String.format(java.util.Locale.ROOT, "%.1f Mbps", b / 1000f)) },
              )
            }
          }

          Section("Audio") {
            LiveAudioSource.entries.forEach { a ->
              FilterChip(selected = audio == a, onClick = { audio = a }, label = { Text(a.label) })
            }
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Lower bitrate automatically on weak networks (Compatible mode; leave off on home Wi-Fi)",
                modifier = Modifier.weight(1f),
            )
            Switch(checked = adaptive, onCheckedChange = { adaptive = it })
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-reconnect if the connection drops", modifier = Modifier.weight(1f))
            Switch(checked = reconnect, onCheckedChange = { reconnect = it })
          }
        }
      },
      confirmButton = {
        TextButton(
            enabled = urlValid && !modeMismatch,
            onClick = {
              onSave(
                  LiveSettings(
                      url = url.trim(),
                      mode = mode,
                      quality = quality,
                      fps = fps,
                      videoBitrateKbps = bitrate,
                      audioSource = audio,
                      smoothingMs = smoothing,
                      adaptiveBitrate = adaptive,
                      autoReconnect = reconnect,
                  ),
              )
            },
        ) {
          Text("Save")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
  }
}
