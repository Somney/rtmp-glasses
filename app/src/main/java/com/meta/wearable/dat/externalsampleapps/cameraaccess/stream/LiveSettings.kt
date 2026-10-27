// LiveSettings - persisted settings for publishing the glasses camera to a stream server
//
// Stored in SharedPreferences so the server URL and quality choices survive app restarts.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import com.meta.wearable.dat.camera.types.VideoQuality

/** How the video leaves the phone. */
enum class LiveMode(val label: String) {
  /** Decode and re-encode to H.264 at a constant frame rate. Works with any server/player. */
  COMPATIBLE("Compatible (H.264)"),
  /** Forward the glasses' own H.265 untouched (RTMP only). Experimental. */
  PASSTHROUGH("Original quality (H.265)"),
}

/** Where the live stream's audio comes from. */
enum class LiveAudioSource(val label: String) {
  GLASSES("Glasses mic"),
  PHONE("Phone mic"),
  NONE("No audio"),
}

/** Glasses camera quality. Resolution is what the glasses send to the phone. */
enum class LiveQuality(val label: String, val videoQuality: VideoQuality) {
  LOW("Low · 360×640", VideoQuality.LOW),
  MEDIUM("Medium · 504×896", VideoQuality.MEDIUM),
  HIGH("High · 720×1280", VideoQuality.HIGH),
}

data class LiveSettings(
    /** rtmp://, rtmps://, srt:// or rtsp:// URL, e.g. rtmp://192.168.1.50:1935/live/glasses */
    val url: String = "",
    val mode: LiveMode = LiveMode.COMPATIBLE,
    val quality: LiveQuality = LiveQuality.MEDIUM,
    /** Glasses frame rate. The SDK accepts 2, 7, 15, 24 or 30. */
    val fps: Int = 24,
    /** Compatible mode only: H.264 bitrate. */
    val videoBitrateKbps: Int = 4000,
    val audioSource: LiveAudioSource = LiveAudioSource.GLASSES,
    /** Smoothing buffer that evens out bursty frames from the glasses. 0 = off. */
    val smoothingMs: Int = 1000,
    /** Compatible mode only: lower the bitrate automatically on a congested network. */
    val adaptiveBitrate: Boolean = false,
    /** Reconnect automatically if the server connection drops. */
    val autoReconnect: Boolean = true,
) {
  companion object {
    val FPS_OPTIONS = listOf(15, 24, 30)
    val BITRATE_OPTIONS_KBPS = listOf(2500, 4000, 6000, 8000)
    val SMOOTHING_OPTIONS_MS = listOf(0, 500, 1000, 2000)

    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_BITRATE = 128 * 1000

    fun isSupportedUrl(url: String): Boolean {
      val u = url.trim().lowercase()
      return listOf("rtmp://", "rtmps://", "srt://", "rtsp://", "rtsps://").any { u.startsWith(it) } &&
          u.length > 10
    }

    fun isRtmpUrl(url: String): Boolean {
      val u = url.trim().lowercase()
      return u.startsWith("rtmp://") || u.startsWith("rtmps://")
    }
  }
}

class LiveSettingsStore(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("live_settings", Context.MODE_PRIVATE)

  fun load(): LiveSettings {
    val d = LiveSettings()
    return LiveSettings(
        url = prefs.getString("url", d.url) ?: d.url,
        mode =
            runCatching { LiveMode.valueOf(prefs.getString("mode", d.mode.name)!!) }
                .getOrDefault(d.mode),
        quality =
            runCatching { LiveQuality.valueOf(prefs.getString("quality", d.quality.name)!!) }
                .getOrDefault(d.quality),
        fps = prefs.getInt("fps", d.fps).takeIf { it in LiveSettings.FPS_OPTIONS } ?: d.fps,
        videoBitrateKbps = prefs.getInt("bitrate", d.videoBitrateKbps),
        audioSource =
            runCatching { LiveAudioSource.valueOf(prefs.getString("audio", d.audioSource.name)!!) }
                .getOrDefault(d.audioSource),
        smoothingMs =
            prefs.getInt("smoothing", d.smoothingMs).takeIf {
              it in LiveSettings.SMOOTHING_OPTIONS_MS
            } ?: d.smoothingMs,
        adaptiveBitrate = prefs.getBoolean("adaptive", d.adaptiveBitrate),
        autoReconnect = prefs.getBoolean("reconnect", d.autoReconnect),
    )
  }

  fun save(settings: LiveSettings) {
    prefs
        .edit()
        .putString("url", settings.url.trim())
        .putString("mode", settings.mode.name)
        .putString("quality", settings.quality.name)
        .putInt("fps", settings.fps)
        .putInt("bitrate", settings.videoBitrateKbps)
        .putString("audio", settings.audioSource.name)
        .putInt("smoothing", settings.smoothingMs)
        .putBoolean("adaptive", settings.adaptiveBitrate)
        .putBoolean("reconnect", settings.autoReconnect)
        .apply()
  }
}
