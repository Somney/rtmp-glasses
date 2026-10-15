// LivePublisher - sends the glasses camera to an RTMP / SRT / RTSP server
//
// Two modes:
//
//  ORIGINAL QUALITY (passthrough, RTMP only)
//    glasses HEVC --> FramePacer --> Enhanced-RTMP H.265 (RootEncoder RtmpClient) --> MediaMTX
//    The glasses' own video is forwarded untouched: no second compression, lowest delay.
//
//  COMPATIBLE
//    glasses HEVC --> FramePacer --> HevcDecoder --> GL --> H.264 encoder --> RTMP/SRT/RTSP
//    Re-encoded to H.264 for servers/players that can't take H.265.
//
// In both, audio (glasses mic, phone mic or none) is AAC. The FramePacer evens out the bursty
// delivery from the glasses; audio timestamps are shifted by the same delay so A/V stay in sync.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.util.Log
import android.view.Surface
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.TimeUtils
import com.pedro.common.VideoCodec
import com.pedro.encoder.Frame
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.audio.GetAudioData
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.BufferAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.QueueAwareBitrateAdapter
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Connection state of the outgoing live stream, shown in the UI. */
sealed interface LiveState {
  data object Idle : LiveState

  data object Connecting : LiveState

  data class Live(val bitrateKbps: Long = 0) : LiveState

  data class Reconnecting(val reason: String) : LiveState

  data class Failed(val reason: String) : LiveState
}

class LivePublisher(context: Context) {

  companion object {
    private const val TAG = "GlassesLive:Publisher"
    private const val RECONNECT_DELAY_MS = 3000L
  }

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _state = MutableStateFlow<LiveState>(LiveState.Idle)
  val state: StateFlow<LiveState> = _state.asStateFlow()

  /** Non-fatal messages for the user (e.g. falling back to no audio). */
  var onNotice: ((String) -> Unit)? = null

  /** Glasses mic over Bluetooth HFP (same handler the recorder uses, separate instance). */
  private val glassesAudio = AudioInputHandler(appContext)
  private var usingGlassesAudio = false

  @Volatile private var engine: Engine? = null
  private val pacer =
      FramePacer("live") { data, pts, isConfig -> engine?.onFrame(data, pts, isConfig) }
  private var autoReconnect = true
  private var wifiLock: WifiManager.WifiLock? = null

  val isActive: Boolean
    get() = engine != null

  private val connectChecker =
      object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}

        override fun onConnectionSuccess() {
          Log.i(TAG, "Connected")
          engine?.onConnected()
          _state.value = LiveState.Live()
        }

        override fun onConnectionFailed(reason: String) {
          Log.w(TAG, "Connection failed: $reason")
          val current = engine ?: return
          if (autoReconnect && current.retry(RECONNECT_DELAY_MS, reason)) {
            _state.value = LiveState.Reconnecting(reason)
          } else {
            scope.launch {
              stopInternal()
              _state.value = LiveState.Failed(reason)
            }
          }
        }

        override fun onDisconnect() {
          Log.i(TAG, "Disconnected")
        }

        override fun onAuthError() {
          scope.launch {
            stopInternal()
            _state.value = LiveState.Failed("Server rejected the username/password")
          }
        }

        override fun onAuthSuccess() {}

        override fun onNewBitrate(bitrate: Long) {
          _state.update { if (it is LiveState.Live) LiveState.Live(bitrate / 1000) else it }
        }

        override fun onStreamingStats(report: StreamingStatsReport) {
          engine?.onStats(report)
        }
      }

  /**
   * Starts publishing. [codecConfig] is the glasses' VPS+SPS+PPS. Returns an error message, or
   * null on success (the connection then continues asynchronously).
   */
  suspend fun start(
      settings: LiveSettings,
      codecConfig: ByteArray,
      frameWidth: Int,
      frameHeight: Int,
  ): String? {
    if (isActive) return null
    val url = settings.url.trim()
    if (!LiveSettings.isSupportedUrl(url)) {
      return "Enter a stream URL starting with rtmp://, srt:// or rtsp://"
    }
    if (settings.mode == LiveMode.PASSTHROUGH && !LiveSettings.isRtmpUrl(url)) {
      return "Original quality mode needs an rtmp:// URL. Use Compatible mode for SRT/RTSP."
    }
    autoReconnect = settings.autoReconnect
    val delayUs = settings.smoothingMs * 1000L

    // Audio first: routing the glasses mic can take a moment and may fail (no HFP link).
    var bufferSource: BufferAudioSource? = null
    val rawAudio: AudioSource =
        when (settings.audioSource) {
          LiveAudioSource.GLASSES -> {
            if (glassesAudio.prepare()) {
              usingGlassesAudio = true
              BufferAudioSource().also { bufferSource = it }
            } else {
              onNotice?.invoke(
                  "Glasses mic not available (is its Bluetooth audio connected?). " +
                      "Going live without audio.")
              NoAudioSource()
            }
          }
          LiveAudioSource.PHONE -> MicrophoneSource(MediaRecorder.AudioSource.CAMCORDER)
          LiveAudioSource.NONE -> NoAudioSource()
        }
    val hasAudio = rawAudio !is NoAudioSource
    // Video is held back by the smoothing buffer; hold audio back by the same amount.
    val audioSource =
        if (hasAudio && delayUs > 0) DelayedAudioSource(rawAudio, delayUs) else rawAudio

    val newEngine: Engine =
        when (settings.mode) {
          LiveMode.PASSTHROUGH -> PassthroughEngine(connectChecker, audioSource, hasAudio)
          LiveMode.COMPATIBLE -> CompatibleEngine(appContext, connectChecker, audioSource, hasAudio)
        }
    val error =
        try {
          newEngine.prepare(settings, codecConfig, frameWidth, frameHeight)
        } catch (e: Exception) {
          Log.e(TAG, "prepare failed", e)
          e.message ?: "Could not prepare the stream"
        }
    if (error != null) {
      runCatching { newEngine.stop() }
      releaseGlassesAudio()
      return error
    }

    val buffer = bufferSource
    if (buffer != null) {
      glassesAudio.pcmDataCallback = { data, offset, size -> buffer.setBuffer(data, offset, size) }
    }

    _state.value = LiveState.Connecting
    acquireWifiLock()
    pacer.delayUs = delayUs
    engine = newEngine
    pacer.start()
    return try {
      newEngine.start(url)
      if (usingGlassesAudio && !glassesAudio.startRecording()) {
        Log.w(TAG, "Glasses mic capture did not start; stream continues with silence")
      }
      null
    } catch (e: Exception) {
      Log.e(TAG, "start failed", e)
      stopInternal()
      _state.value = LiveState.Idle
      e.message ?: "Could not start the stream"
    }
  }

  /** Feed every compressed HEVC frame from the glasses here. No-op when not live. */
  fun onHevcFrame(data: ByteArray, presentationTimeUs: Long, isCodecConfig: Boolean) {
    if (engine != null) pacer.offer(data, presentationTimeUs, isCodecConfig)
  }

  fun stop() {
    stopInternal()
    _state.value = LiveState.Idle
  }

  private fun stopInternal() {
    val current = engine
    engine = null
    pacer.stop()
    if (current != null) {
      runCatching { current.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
    }
    releaseGlassesAudio()
    releaseWifiLock()
  }

  private fun releaseGlassesAudio() {
    if (!usingGlassesAudio) return
    glassesAudio.pcmDataCallback = null
    glassesAudio.stopRecording()
    usingGlassesAudio = false
  }

  @Suppress("DEPRECATION")
  private fun acquireWifiLock() {
    if (wifiLock != null) return
    runCatching {
          val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
          wifiLock =
              wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GlassesLive").apply {
                setReferenceCounted(false)
                acquire()
              }
        }
        .onFailure { Log.w(TAG, "Wi-Fi lock unavailable", it) }
  }

  private fun releaseWifiLock() {
    runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    wifiLock = null
  }

  fun close() {
    stop()
    glassesAudio.cleanup()
  }
}

// MARK: - Engines

private interface Engine {
  /** Returns an error message or null. */
  fun prepare(
      settings: LiveSettings,
      codecConfig: ByteArray,
      frameWidth: Int,
      frameHeight: Int,
  ): String?

  fun start(url: String)

  /** Called on the pacer thread with frames in order, evenly spaced. */
  fun onFrame(data: ByteArray, presentationTimeUs: Long, isCodecConfig: Boolean)

  fun onConnected() {}

  fun onStats(report: StreamingStatsReport) {}

  fun retry(delayMs: Long, reason: String): Boolean

  fun stop()
}

/** Forwards the glasses' H.265 untouched over Enhanced RTMP; audio is encoded to AAC. */
private class PassthroughEngine(
    connectChecker: ConnectChecker,
    private val audioSource: AudioSource,
    private val hasAudio: Boolean,
) : Engine {

  companion object {
    private const val TAG = "GlassesLive:Passthrough"
    private const val MAX_PTS_GAP_US = 3_000_000L
  }

  private val rtmp = RtmpClient(connectChecker)
  private val lock = Any()

  // Parameter sets (each with a 4-byte start code).
  private var vps: ByteArray? = null
  private var sps: ByteArray? = null
  private var pps: ByteArray? = null
  private var sentParams: List<ByteArray>? = null

  @Volatile private var waitingForKeyframe = true
  @Volatile private var startTsUs = 0L
  private var basePtsUs = Long.MIN_VALUE
  private var baseTsUs = 0L
  private var lastPtsUs = Long.MIN_VALUE
  private var lastTsUs = -1L

  private val audioEncoder =
      AudioEncoder(
          object : GetAudioData {
            override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
              rtmp.sendAudio(audioBuffer, info)
            }

            override fun onAudioFormat(mediaFormat: MediaFormat) {}
          })

  private val micCallback =
      object : GetMicrophoneData {
        override fun inputPCMData(frame: Frame) {
          audioEncoder.inputPCMData(frame)
        }
      }

  override fun prepare(
      settings: LiveSettings,
      codecConfig: ByteArray,
      frameWidth: Int,
      frameHeight: Int,
  ): String? {
    collectParams(codecConfig)
    val v = vps
    val s = sps
    val p = pps
    if (v == null || s == null || p == null) return "Waiting for video from the glasses, try again"

    rtmp.setVideoCodec(VideoCodec.H265)
    rtmp.setAudioCodec(AudioCodec.AAC)
    rtmp.setVideoInfo(ByteBuffer.wrap(s), ByteBuffer.wrap(p), ByteBuffer.wrap(v))
    sentParams = listOf(v, s, p)
    rtmp.setVideoResolution(frameWidth, frameHeight)
    rtmp.setFps(settings.fps)
    rtmp.setReTries(if (settings.autoReconnect) 50 else 0)

    if (hasAudio) {
      if (!audioSource.init(LiveSettings.AUDIO_SAMPLE_RATE, false, false, false)) {
        return "Could not open the microphone"
      }
      val encoderOk =
          audioEncoder.prepareAudioEncoder(
              LiveSettings.AUDIO_BITRATE, LiveSettings.AUDIO_SAMPLE_RATE, false)
      if (!encoderOk) return "Could not start the audio encoder"
      rtmp.setAudioInfo(LiveSettings.AUDIO_SAMPLE_RATE, false)
      // Audio is sent right away but stamped later (smoothing delay): let the sender re-order.
      if (settings.smoothingMs > 0) rtmp.setDelay(settings.smoothingMs + 300L)
    } else {
      rtmp.setOnlyVideo(true)
    }
    return null
  }

  override fun start(url: String) {
    startTsUs = TimeUtils.getCurrentTimeMicro()
    if (hasAudio) {
      audioEncoder.start(startTsUs)
      audioSource.start(micCallback)
    }
    rtmp.connect(url)
  }

  override fun onConnected() {
    // A player can only start decoding at a keyframe.
    waitingForKeyframe = true
  }

  override fun onFrame(data: ByteArray, presentationTimeUs: Long, isCodecConfig: Boolean) {
    synchronized(lock) {
      val nals = HevcNals.split(data)
      if (nals.isEmpty()) return
      var paramsSeen = false
      val slices = ArrayList<HevcNals.Nal>(nals.size)
      for (nal in nals) {
        when (nal.type) {
          HevcNals.VPS -> {
            vps = nal.withStartCode(data)
            paramsSeen = true
          }
          HevcNals.SPS -> {
            sps = nal.withStartCode(data)
            paramsSeen = true
          }
          HevcNals.PPS -> {
            pps = nal.withStartCode(data)
            paramsSeen = true
          }
          else -> slices.add(nal)
        }
      }
      if (paramsSeen) maybeResendParams()
      if (slices.isEmpty() || isCodecConfig) return

      val isKeyframe = slices.any { it.type in HevcNals.IRAP_RANGE }
      if (waitingForKeyframe) {
        if (!isKeyframe) return
        waitingForKeyframe = false
      }

      // Timestamps follow the glasses' capture clock so playback speed is steady, anchored to
      // the phone clock at the first frame (audio uses the same phone clock).
      val now = TimeUtils.getCurrentTimeMicro()
      val jumped =
          lastPtsUs != Long.MIN_VALUE &&
              (presentationTimeUs < lastPtsUs || presentationTimeUs - lastPtsUs > MAX_PTS_GAP_US)
      if (basePtsUs == Long.MIN_VALUE || jumped) {
        basePtsUs = presentationTimeUs
        baseTsUs = maxOf(now - startTsUs, lastTsUs + 1000)
      }
      lastPtsUs = presentationTimeUs
      var ts = baseTsUs + (presentationTimeUs - basePtsUs)
      if (ts <= lastTsUs) ts = lastTsUs + 1000
      lastTsUs = ts

      val payload = HevcNals.join(data, slices)
      val info = MediaCodec.BufferInfo()
      info.set(0, payload.size, ts, if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
      rtmp.sendVideo(ByteBuffer.wrap(payload), info)
    }
  }

  /** The glasses changed resolution/settings: send a new sequence header before the next frame. */
  private fun maybeResendParams() {
    val v = vps ?: return
    val s = sps ?: return
    val p = pps ?: return
    val previous = sentParams
    if (previous != null &&
        previous[0].contentEquals(v) &&
        previous[1].contentEquals(s) &&
        previous[2].contentEquals(p)) {
      return
    }
    Log.i(TAG, "Glasses video parameters changed, resending sequence header")
    rtmp.setVideoInfo(ByteBuffer.wrap(s), ByteBuffer.wrap(p), ByteBuffer.wrap(v))
    sentParams = listOf(v, s, p)
    waitingForKeyframe = true
  }

  private fun collectParams(config: ByteArray) {
    for (nal in HevcNals.split(config)) {
      when (nal.type) {
        HevcNals.VPS -> vps = nal.withStartCode(config)
        HevcNals.SPS -> sps = nal.withStartCode(config)
        HevcNals.PPS -> pps = nal.withStartCode(config)
      }
    }
  }

  override fun retry(delayMs: Long, reason: String): Boolean {
    if (!rtmp.shouldRetry(reason)) return false
    rtmp.reConnect(delayMs)
    return true
  }

  override fun stop() {
    runCatching { rtmp.disconnect() }
    if (hasAudio) {
      runCatching { audioSource.stop() }
      runCatching { audioEncoder.stop() }
      runCatching { audioSource.release() }
    }
  }
}

/** Decodes the glasses video and re-encodes it to H.264 with RootEncoder's GenericStream. */
private class CompatibleEngine(
    context: Context,
    connectChecker: ConnectChecker,
    audioSource: AudioSource,
    private val hasAudio: Boolean,
) : Engine {

  companion object {
    private const val TAG = "GlassesLive:Compatible"
    private const val KEYFRAME_INTERVAL_S = 2
  }

  private val videoSource = GlassesVideoSource()
  private val stream = GenericStream(context, connectChecker, videoSource, audioSource)
  private var bitrateAdapter: QueueAwareBitrateAdapter? = null

  override fun prepare(
      settings: LiveSettings,
      codecConfig: ByteArray,
      frameWidth: Int,
      frameHeight: Int,
  ): String? {
    // Encode at the glasses' own size (no upscaling); keep dimensions encoder-friendly.
    val width = (frameWidth and 0xFFF8).coerceAtLeast(160)
    val height = (frameHeight and 0xFFF8).coerceAtLeast(160)
    videoSource.codecConfig = codecConfig
    videoSource.frameWidth = frameWidth
    videoSource.frameHeight = frameHeight

    val videoBitrate = settings.videoBitrateKbps * 1000
    val videoOk =
        try {
          stream.prepareVideo(
              width = width,
              height = height,
              bitrate = videoBitrate,
              fps = settings.fps,
              iFrameInterval = KEYFRAME_INTERVAL_S,
          )
        } catch (e: Exception) {
          Log.e(TAG, "prepareVideo failed", e)
          false
        }
    if (!videoOk) return "This phone's video encoder rejected the settings"
    val audioOk =
        try {
          stream.prepareAudio(
              sampleRate = LiveSettings.AUDIO_SAMPLE_RATE,
              isStereo = false,
              bitrate = LiveSettings.AUDIO_BITRATE,
              echoCanceler = false,
              noiseSuppressor = false,
          )
        } catch (e: Exception) {
          Log.e(TAG, "prepareAudio failed", e)
          false
        }
    if (!audioOk) return "Could not open the microphone"

    val client = stream.getStreamClient()
    client.setReTries(if (settings.autoReconnect) 50 else 0)
    if (!hasAudio) client.setOnlyVideo(true)
    if (hasAudio && settings.smoothingMs > 0) client.setDelay(settings.smoothingMs + 300L)

    if (settings.adaptiveBitrate) {
      val audioBitrate = if (hasAudio) LiveSettings.AUDIO_BITRATE else 0
      bitrateAdapter =
          QueueAwareBitrateAdapter(videoBitrate + audioBitrate) { total ->
            stream.setVideoBitrateOnFly((total - audioBitrate).coerceAtLeast(300_000))
          }
    }
    return null
  }

  override fun start(url: String) {
    stream.startStream(url)
  }

  override fun onFrame(data: ByteArray, presentationTimeUs: Long, isCodecConfig: Boolean) {
    videoSource.feed(data, presentationTimeUs)
  }

  override fun onConnected() {
    bitrateAdapter?.reset()
  }

  override fun onStats(report: StreamingStatsReport) {
    bitrateAdapter?.onStreamingStats(report)
  }

  override fun retry(delayMs: Long, reason: String): Boolean =
      stream.getStreamClient().reTry(delayMs, reason, null)

  override fun stop() {
    runCatching { if (stream.isStreaming) stream.stopStream() }
    runCatching { stream.release() }
  }
}

// MARK: - Sources

/** Wraps an audio source and stamps its samples [delayUs] later (matches the video buffer). */
private class DelayedAudioSource(
    private val inner: AudioSource,
    private val delayUs: Long,
) : AudioSource() {

  override fun create(
      sampleRate: Int,
      isStereo: Boolean,
      echoCanceler: Boolean,
      noiseSuppressor: Boolean,
  ): Boolean = inner.init(sampleRate, isStereo, echoCanceler, noiseSuppressor)

  override fun start(getMicrophoneData: GetMicrophoneData) {
    this.getMicrophoneData = getMicrophoneData
    inner.start(
        object : GetMicrophoneData {
          override fun inputPCMData(frame: Frame) {
            frame.timeStamp += delayUs
            getMicrophoneData.inputPCMData(frame)
          }
        })
  }

  override fun stop() {
    inner.stop()
  }

  override fun isRunning(): Boolean = inner.isRunning()

  override fun release() {
    inner.release()
  }
}

/**
 * RootEncoder video source fed by the glasses. RootEncoder hands us a SurfaceTexture; we decode
 * the glasses' HEVC frames straight into it with the sample's [HevcDecoder].
 */
private class GlassesVideoSource : VideoSource() {

  private val lock = Any()
  private var decoder: HevcDecoder? = null
  private var surface: Surface? = null

  /** Latest VPS+SPS+PPS from the glasses, used to prime a decoder created mid-stream. */
  @Volatile var codecConfig: ByteArray? = null
  @Volatile var frameWidth: Int = 504
  @Volatile var frameHeight: Int = 896

  override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

  override fun start(surfaceTexture: SurfaceTexture) {
    synchronized(lock) {
      this.surfaceTexture = surfaceTexture
      if (decoder != null) return
      val out = Surface(surfaceTexture)
      surface = out
      decoder =
          HevcDecoder().also { d ->
            d.start(frameWidth, frameHeight, out)
            codecConfig?.let { d.decodeFrame(it, 0) }
          }
    }
  }

  fun feed(data: ByteArray, presentationTimeUs: Long) {
    synchronized(lock) { decoder?.decodeFrame(data, presentationTimeUs) }
  }

  override fun stop() {
    synchronized(lock) {
      decoder?.stop()
      decoder = null
      surface?.release()
      surface = null
    }
  }

  override fun release() {}

  override fun isRunning(): Boolean = synchronized(lock) { decoder != null }

  // Draw the decoded frame as-is: no camera-sensor rotation (cameraOrientation 0), and tell the
  // renderer the frame's real shape. Forcing LANDSCAPE here squeezed the portrait glasses image
  // into a band of the encoder frame, so OBS showed it squished.
  override fun getOrientationConfig(): OrientationConfig =
      OrientationConfig(
          cameraOrientation = 0,
          isPortrait = frameHeight > frameWidth,
          forced = OrientationForced.NONE,
      )
}

// MARK: - H.265 Annex-B helpers

internal object HevcNals {
  const val VPS = 32
  const val SPS = 33
  const val PPS = 34
  /** IRAP pictures (BLA/IDR/CRA): a decoder can start here. */
  val IRAP_RANGE = 16..21

  private val START_CODE = byteArrayOf(0, 0, 0, 1)

  /** A NAL unit's payload position inside its frame (start code excluded). */
  class Nal(val type: Int, val start: Int, val end: Int) {
    val size: Int
      get() = end - start

    fun withStartCode(data: ByteArray): ByteArray {
      val out = ByteArray(4 + size)
      START_CODE.copyInto(out, 0)
      data.copyInto(out, 4, start, end)
      return out
    }
  }

  fun split(data: ByteArray): List<Nal> {
    val out = ArrayList<Nal>()
    var payloadStart = -1
    var i = 0
    while (i + 2 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
        if (payloadStart >= 0) {
          var end = i
          // A 4-byte start code's leading zero belongs to the start code.
          if (end > payloadStart && data[end - 1].toInt() == 0) end--
          add(out, data, payloadStart, end)
        }
        payloadStart = i + 3
        i += 3
      } else {
        i++
      }
    }
    if (payloadStart >= 0 && payloadStart < data.size) add(out, data, payloadStart, data.size)
    return out
  }

  private fun add(out: MutableList<Nal>, data: ByteArray, start: Int, end: Int) {
    if (end - start < 2) return
    val type = (data[start].toInt() shr 1) and 0x3F
    out.add(Nal(type, start, end))
  }

  /** Re-assembles [nals] from [data] into one Annex-B buffer. */
  fun join(data: ByteArray, nals: List<Nal>): ByteArray {
    val out = ByteArray(nals.sumOf { it.size + 4 })
    var offset = 0
    for (nal in nals) {
      START_CODE.copyInto(out, offset)
      data.copyInto(out, offset + 4, nal.start, nal.end)
      offset += 4 + nal.size
    }
    return out
  }
}
