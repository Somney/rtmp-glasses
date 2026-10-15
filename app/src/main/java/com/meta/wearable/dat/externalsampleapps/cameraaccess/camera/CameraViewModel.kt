/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraViewModel - DAT camera lifecycle, capture, and recording
//
// Drives the camera screen by exercising the SDK's camera lifecycle as explicit steps: create and
// start a DeviceSession, add and start a Stream, capture a photo or record video, stop the stream,
// end the session. Owns the screen-scoped pieces — the DeviceSession, its Stream, the on-device
// HEVC preview decoder, and the passthrough video recorder. The UI binds to the SDK's own
// DeviceSessionState / StreamState so the sample shows the real state machine.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.AudioInputHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcDecoder
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcParameterSetCollector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveAudioSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LivePublisher
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveSettings
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveSettingsStore
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.LiveState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.RecordingResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.VideoRecorder
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:CameraViewModel"
    private const val KEYFRAME_WAIT_STEP_MS = 25L
    private const val KEYFRAME_WAIT_MAX_MS = 500L
  }

  private class RecorderFrame(
      val data: ByteArray,
      val presentationTimeUs: Long,
      val width: Int,
      val height: Int,
      val isCodecConfig: Boolean,
  )

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector

  // Live streaming (RTMP / SRT / RTSP) to a server such as MediaMTX.
  private val liveSettingsStore = LiveSettingsStore(application)
  private val livePublisher = LivePublisher(application)
  // Size of the frames the glasses are actually sending, captured from the stream.
  @Volatile private var lastFrameWidth = 504
  @Volatile private var lastFrameHeight = 896

  // Incoming-video stats (what the glasses are actually sending), updated once a second.
  private var statsWindowStartNs = 0L
  private var statsBytes = 0L
  private var statsFrames = 0

  private val _uiState = MutableStateFlow(CameraUiState(liveSettings = liveSettingsStore.load()))
  val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null

  // Recording pieces. The single compressed-HEVC stream feeds both the on-screen decoder and the
  // passthrough MP4 writer.
  private val audioInputHandler = AudioInputHandler(application)
  private val videoRecorder = VideoRecorder(application, viewModelScope)

  // Preview decode runs off the main thread; single-threaded so frames stay in order.
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

  // Recording muxes on a serialized dispatcher fed by an unbounded channel, so it never stalls the
  // live preview and keeps frames in order.
  private val recorderDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val recorderChannel = Channel<RecorderFrame>(Channel.UNLIMITED)

  // Guards decoder create/teardown so a frame can't bind a new decoder to a Surface that
  // setSurface(null) just released — the @Volatile refs alone can't fix that check-then-act.
  private val decoderLock = Any()

  // @Volatile: single refs shared by the frame-collector and main threads, nulled at teardown.
  @Volatile private var hevcDecoder: HevcDecoder? = null
  @Volatile private var decoderSurface: Surface? = null

  // Accumulates the stream's HEVC parameter sets (VPS/SPS/PPS) so a recording (or decoder) started
  // mid-stream can be primed with a complete format. The SDK emits the VPS once at stream start, so
  // a partial set yields an unfinalizable file ("Missing codec specific data"). Internally
  // synchronized.
  private val csdCollector = HevcParameterSetCollector()

  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var videoJob: Job? = null
  private var streamStateJob: Job? = null
  private var streamErrorJob: Job? = null

  init {
    videoRecorder.setAudioInputHandler(audioInputHandler)

    livePublisher.onNotice = { message -> wearablesViewModel.setRecentError(message) }

    // Mirror the live publisher's connection state into the UI.
    viewModelScope.launch {
      livePublisher.state.collect { live ->
        _uiState.update { it.copy(liveState = live) }
        if (live is LiveState.Failed) {
          wearablesViewModel.setRecentError("Live stream stopped: ${live.reason}")
          if (stream != null) StreamingService.start(getApplication())
        }
      }
    }

    // Drain recording frames off the preview thread.
    viewModelScope.launch(recorderDispatcher) {
      for (frame in recorderChannel) {
        videoRecorder.writeCompressedFrame(
            frame.data,
            frame.presentationTimeUs,
            frame.width,
            frame.height,
            frame.isCodecConfig,
        )
      }
    }

    // Mirror the recorder's intent/elapsed into UI state.
    viewModelScope.launch {
      videoRecorder.isRecording.collect { recording ->
        _uiState.update { it.copy(isRecording = recording) }
      }
    }
    viewModelScope.launch {
      videoRecorder.recordingElapsedSeconds.collect { seconds ->
        _uiState.update { it.copy(recordingElapsedSeconds = seconds) }
      }
    }
    viewModelScope.launch {
      videoRecorder.audioActive.collect { active ->
        if (!active && videoRecorder.isRecording.value && _uiState.value.includeAudioInStream) {
          _uiState.update { it.copy(includeAudioInStream = false) }
        }
      }
    }

    // Keyed on the same predicate the scaffold navigates on, so a cancelled unregistration — which
    // reverts to REGISTERED — leaves an in-flight session untouched.
    viewModelScope.launch {
      wearablesViewModel.uiState
          .map { it.isRegistered }
          .distinctUntilChanged()
          .collect { isRegistered -> if (!isRegistered) handleUnregistered() }
    }
  }

  // MARK: - Surface

  fun setSurface(surface: Surface?) {
    synchronized(decoderLock) {
      decoderSurface = surface
      if (surface == null) {
        hevcDecoder?.stop()
        hevcDecoder = null
      }
    }
  }

  // MARK: - Lifecycle step 1: session

  /** Creates and starts a [DeviceSession] (no stream yet). */
  fun startSession() {
    if (_uiState.value.hasSession) return
    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          // Subscribe before start() so no initial transitions are missed.
          observeSession(created)
          _uiState.update { it.copy(sessionState = DeviceSessionState.STARTING) }
          created.start()
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to start session: ${error.description}")
          wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          cleanupSession()
        }
  }

  /**
   * Ends the device session. The stream and any in-progress recording are not torn down here
   * directly — stopping the session drives the SDK's stream to a terminal state, which the
   * stream-state collector observes (see [onStreamTerminated]) to stop recording and release stream
   * resources.
   */
  fun endSession() {
    val current = session ?: return
    _uiState.update { it.copy(sessionState = DeviceSessionState.STOPPING) }
    current.stop()
  }

  private fun observeSession(session: DeviceSession) {
    sessionStateJob = viewModelScope.launch {
      session.state.collect { state ->
        _uiState.update { it.copy(sessionState = state) }
        if (state == DeviceSessionState.STOPPED) {
          cleanupSession()
        }
      }
    }
    sessionErrorJob = viewModelScope.launch {
      session.errors.collect { error ->
        // All session errors surface through the snackbar, including
        // DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED, which the SDK delivers as a one-shot event.
        Log.e(TAG, "Session error: ${error.description}")
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
      }
    }
  }

  private fun cleanupSession() {
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    session = null
  }

  // MARK: - Lifecycle step 2: stream (preview)

  /**
   * Starts the camera stream (preview). Requires an active session. Camera permission is checked
   * first (a query, no redirect); if it isn't granted, the actual request — which redirects to the
   * Meta AI app — is deferred to [confirmCameraPermissionRedirect] so the app-switch is confirmed.
   */
  fun startStreaming() {
    if (!_uiState.value.isSessionActive) {
      wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.error_start_session_first),
      )
      return
    }
    if (stream != null || _uiState.value.isStartingStream) return
    _uiState.update { it.copy(isStartingStream = true) }
    viewModelScope.launch {
      try {
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { status ->
              if (status == PermissionStatus.Granted) {
                beginStream()
              } else {
                _uiState.update { it.copy(showCameraPermissionRedirectConfirm = true) }
              }
            }
            .onFailure { error, _ ->
              Log.e(TAG, "Failed to check camera permission: ${error.description}")
              wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
            }
      } finally {
        _uiState.update { it.copy(isStartingStream = false) }
      }
    }
  }

  /** Confirmed from the permission prompt: requests camera access, then starts the stream. */
  fun confirmCameraPermissionRedirect(
      requestPermission: suspend (Permission) -> PermissionStatus,
  ) {
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
    if (!_uiState.value.isSessionActive || stream != null) return
    viewModelScope.launch {
      val status = requestPermission(Permission.CAMERA)
      if (status == PermissionStatus.Granted) {
        beginStream()
      } else {
        wearablesViewModel.setRecentError(
            getApplication<Application>().getString(R.string.error_camera_permission_denied),
        )
      }
    }
  }

  fun cancelCameraPermissionRedirect() {
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
  }

  private fun beginStream() {
    val current = session ?: return
    if (stream != null) return
    // Foreground service keeps the stream/recording alive while backgrounded.
    StreamingService.start(getApplication())
    val liveSettings = _uiState.value.liveSettings
    current
        .addCamera(
            StreamConfiguration(
                videoQuality = liveSettings.quality.videoQuality,
                frameRate = liveSettings.fps,
                // Compressed HEVC so frames feed both the on-screen decoder and the passthrough
                // MP4 writer.
                compressVideo = true,
            ),
        )
        .onSuccess { addedCamera ->
          camera = addedCamera
          val added = addedCamera.stream
          stream = added
          // Subscribe before start() so no initial transitions are missed.
          setupStreamListeners(added)
          _uiState.update { it.copy(streamState = StreamState.STARTING) }
          added.start().onFailure { error, _ ->
            Log.e(TAG, "Failed to start stream: ${error.description}")
            wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
            // A failed start leaves the stream attached and the FGS running — tear both down so the
            // UI doesn't stick at STARTING, matching the addCamera() failure path below.
            clearStreamResources()
          }
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to add camera: ${error.description}")
          StreamingService.stop(getApplication())
          wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
        }
  }

  /** Stops the camera stream but keeps the [DeviceSession] connected. */
  fun stopStreaming() {
    val current = camera ?: return
    _uiState.update { it.copy(streamState = StreamState.STOPPING) }
    current.stop()
    // The stream-state collector converges teardown when STOPPED/CLOSED arrives.
  }

  private fun setupStreamListeners(stream: Stream) {
    videoJob =
        viewModelScope.launch(frameDispatcher) {
          stream.videoStream.collect { handleVideoFrame(it) }
        }
    streamStateJob = viewModelScope.launch {
      // state replays its current value (STOPPED) on subscribe, and we subscribe before start().
      var hasBeenActive = false
      stream.state.collect { state ->
        _uiState.update { it.copy(streamState = state) }
        val isTerminal = state == StreamState.STOPPED || state == StreamState.CLOSED
        if (!isTerminal) {
          if (!hasBeenActive && _uiState.value.includeAudioInStream) {
            viewModelScope.launch {
              try {
                videoRecorder.prewarmGlassesAudio()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Log.e(TAG, "stream-active glasses audio prewarm failed", e)
              }
            }
          }
          hasBeenActive = true
        } else if (hasBeenActive) {
          hasBeenActive = false
          onStreamTerminated()
        }
      }
    }
    streamErrorJob = viewModelScope.launch {
      stream.errorStream.collect { error ->
        Log.e(TAG, "Stream error: ${error.description}")
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
      }
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    if (!videoFrame.isCompressed) return

    val buffer = videoFrame.buffer
    val width = videoFrame.width
    val height = videoFrame.height
    val presentationTimeUs = videoFrame.presentationTimeUs
    if (width > 0 && height > 0) {
      lastFrameWidth = width
      lastFrameHeight = height
    }
    updateInputStats(buffer.remaining(), videoFrame.isCodecConfig, width, height)

    val byteArray = ByteArray(buffer.remaining())
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    buffer.position(originalPosition)

    // Accumulate the parameter sets so a recording (or decoder) started after stream start can be
    // primed with a complete VPS+SPS+PPS set.
    csdCollector.offer(byteArray)

    // Feed the preview decoder first so recorder muxing never delays it.
    // Lazily create the decoder once a Surface is available; it renders directly to it. Prime it
    // with the cached config in case the surface arrived after the config frame. Guarded so a
    // concurrent setSurface(null) can't leave a decoder bound to a released Surface.
    synchronized(decoderLock) {
      val surface = decoderSurface
      if (hevcDecoder == null && surface != null) {
        hevcDecoder =
            HevcDecoder().also { decoder ->
              decoder.start(width, height, surface)
              csdCollector.complete()?.let { decoder.decodeFrame(it, 0) }
            }
      }
      // Feed under the lock so teardown can't null the decoder between check and feed.
      hevcDecoder?.decodeFrame(byteArray, presentationTimeUs)
    }

    // Forward to the live publisher (decodes into its own encoder surface). No-op when not live.
    livePublisher.onHevcFrame(byteArray, presentationTimeUs, videoFrame.isCodecConfig)

    // Hand off to the recorder thread; never blocks the preview.
    if (videoRecorder.isRecording.value) {
      recorderChannel.trySend(
          RecorderFrame(byteArray, presentationTimeUs, width, height, videoFrame.isCodecConfig),
      )
    }

    if (!videoFrame.isCodecConfig && !_uiState.value.hasReceivedFirstFrame) {
      _uiState.update { it.copy(hasReceivedFirstFrame = true) }
    }
  }

  private fun updateInputStats(bytes: Int, isCodecConfig: Boolean, width: Int, height: Int) {
    val now = System.nanoTime()
    if (statsWindowStartNs == 0L) statsWindowStartNs = now
    statsBytes += bytes
    if (!isCodecConfig) statsFrames++
    val elapsed = now - statsWindowStartNs
    if (elapsed < 1_000_000_000L) return
    val fps = statsFrames * 1_000_000_000.0 / elapsed
    val mbps = statsBytes * 8 * 1000.0 / elapsed
    val text =
        String.format(java.util.Locale.ROOT, "%d×%d · %.0f fps · %.1f Mbps", width, height, fps, mbps)
    _uiState.update { it.copy(inputStats = text) }
    statsWindowStartNs = now
    statsBytes = 0
    statsFrames = 0
  }

  private fun onStreamTerminated() {
    // No more glasses frames: end the live stream too.
    if (_uiState.value.isLiveActive) livePublisher.stop()
    // Finalize an in-progress recording before releasing the foreground service / wake lock, so the
    // MP4 mux on Dispatchers.IO isn't cut off when the stream stops while backgrounded.
    viewModelScope.launch {
      if (_uiState.value.isRecording) {
        stopVideoRecording()
      }
      try {
        videoRecorder.releaseGlassesAudioPrewarm()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "onStreamTerminated glasses audio release failed", e)
      }
      clearStreamResources()
    }
  }

  private fun clearStreamResources() {
    livePublisher.stop()
    videoJob?.cancel()
    videoJob = null
    streamStateJob?.cancel()
    streamStateJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    synchronized(decoderLock) {
      hevcDecoder?.stop()
      hevcDecoder = null
    }
    csdCollector.reset()
    statsWindowStartNs = 0L
    statsBytes = 0
    statsFrames = 0
    _uiState.update { it.copy(inputStats = "") }
    StreamingService.stop(getApplication())
    // STOPPED is restartable, so only stop() detaches the capability; without it the next
    // addCamera() fails with "a capability of this type is already active". Stopping the camera
    // cascades to its stream child.
    stopCamera(camera)
    camera = null
    stream = null
    _uiState.update { it.copy(streamState = StreamState.STOPPED, hasReceivedFirstFrame = false) }
  }

  /**
   * Stops the camera by closing it. Accepting a [java.io.Closeable] parameter satisfies the
   * AutoCloseableUse detector, which skips methods that take an AutoCloseable argument — the camera
   * outlives any single `use {}` block, so it is stopped explicitly here rather than auto-closed.
   */
  private fun stopCamera(capability: java.io.Closeable?) {
    capability?.close()
  }

  // MARK: - Capture

  fun capturePhoto() {
    if (_uiState.value.isCapturingPhoto || !_uiState.value.isStreaming) return
    _uiState.update { it.copy(isCapturingPhoto = true) }
    viewModelScope.launch {
      stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            // Decode/rotate is CPU-bound and blocking; keep it off the main thread so capture
            // doesn't jank the UI. Resumes on main for the state update.
            val bitmap = withContext(Dispatchers.Default) { decodePhoto(photoData) }
            if (bitmap != null) {
              _uiState.update {
                it.copy(isCapturingPhoto = false, activePreview = CapturePreview.Photo(bitmap))
              }
            } else {
              _uiState.update { it.copy(isCapturingPhoto = false) }
              wearablesViewModel.setRecentError(
                  getApplication<Application>().getString(R.string.error_photo_capture_failed),
              )
            }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Failed to capture photo: ${error.description}")
            _uiState.update { it.copy(isCapturingPhoto = false) }
            wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          } ?: _uiState.update { it.copy(isCapturingPhoto = false) }
    }
  }

  // MARK: - Recording

  fun toggleRecording(requestRecordAudioPermission: suspend () -> Boolean) {
    if (_uiState.value.isRecording) {
      viewModelScope.launch { stopVideoRecording() }
    } else {
      startVideoRecording(requestRecordAudioPermission)
    }
  }

  fun startVideoRecording(requestRecordAudioPermission: suspend () -> Boolean) {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    if (_uiState.value.isLiveActive) {
      wearablesViewModel.setRecentError("Stop the live stream before recording locally")
      return
    }
    viewModelScope.launch {
      // Request the mic permission only when sound-in-video is on; record video-only if it's off or
      // the user declines. The prompt appears in context on the first record with the mic on.
      val includeAudio = _uiState.value.includeAudioInStream && requestRecordAudioPermission()
      if (!_uiState.value.isStreaming || _uiState.value.isRecording) return@launch
      // If the user wanted sound but denied the mic, reflect it so the mic icon doesn't stay "on".
      if (_uiState.value.includeAudioInStream && !includeAudio) {
        _uiState.update { it.copy(includeAudioInStream = false) }
      }
      videoRecorder.setIncludeAudio(includeAudio)
      val audioEnabled = videoRecorder.startRecording(csdCollector.complete())
      // Glasses HFP audio may be unavailable even with the mic on; flip the icon off so it doesn't
      // imply audio is being captured.
      if (includeAudio && !audioEnabled) {
        _uiState.update { it.copy(includeAudioInStream = false) }
      }
    }
  }

  suspend fun stopVideoRecording() {
    if (!_uiState.value.isRecording) return
    // The writer starts on the first keyframe. If stop lands just before that frame, wait briefly
    // so even a quick recording finalizes to a file instead of being discarded.
    var waited = 0L
    while (!videoRecorder.hasStartedWriting.value && waited < KEYFRAME_WAIT_MAX_MS) {
      delay(KEYFRAME_WAIT_STEP_MS)
      waited += KEYFRAME_WAIT_STEP_MS
    }
    when (val result = videoRecorder.stopRecording()) {
      is RecordingResult.Completed ->
          _uiState.update { it.copy(activePreview = CapturePreview.Video(result.uri)) }
      RecordingResult.NoRecording ->
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_recording_too_short),
          )
      RecordingResult.Failed ->
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_recording_save_failed),
          )
    }
  }

  fun toggleMic() {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    val enabled = !_uiState.value.includeAudioInStream
    _uiState.update { it.copy(includeAudioInStream = enabled) }
    viewModelScope.launch {
      try {
        if (enabled) videoRecorder.prewarmGlassesAudio()
        else videoRecorder.releaseGlassesAudioPrewarm()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "toggleMic glasses audio prewarm/release failed", e)
      }
    }
  }

  // MARK: - Live streaming

  fun showLiveSettings(show: Boolean) {
    _uiState.update { it.copy(showLiveSettings = show) }
  }

  fun updateLiveSettings(settings: LiveSettings) {
    liveSettingsStore.save(settings)
    _uiState.update { it.copy(liveSettings = settings, showLiveSettings = false) }
  }

  fun toggleLive(requestRecordAudioPermission: suspend () -> Boolean) {
    if (_uiState.value.isLiveActive) stopLive() else startLive(requestRecordAudioPermission)
  }

  private fun startLive(requestRecordAudioPermission: suspend () -> Boolean) {
    val ui = _uiState.value
    if (ui.isStartingLive) return
    if (!ui.isStreaming) {
      wearablesViewModel.setRecentError("Start the preview first, then go live")
      return
    }
    if (ui.isRecording) {
      wearablesViewModel.setRecentError("Stop the local recording before going live")
      return
    }
    if (!LiveSettings.isSupportedUrl(ui.liveSettings.url)) {
      _uiState.update { it.copy(showLiveSettings = true) }
      return
    }
    _uiState.update { it.copy(isStartingLive = true) }
    viewModelScope.launch {
      try {
        var settings = _uiState.value.liveSettings
        if (settings.audioSource != LiveAudioSource.NONE && !requestRecordAudioPermission()) {
          wearablesViewModel.setRecentError("Microphone permission denied, going live without audio")
          settings = settings.copy(audioSource = LiveAudioSource.NONE)
        }
        if (settings.audioSource == LiveAudioSource.GLASSES) {
          // The glasses mic can only be routed to one consumer: free it from the recorder.
          runCatching { videoRecorder.releaseGlassesAudioPrewarm() }
          _uiState.update { it.copy(includeAudioInStream = false) }
        }
        val codecConfig = csdCollector.complete()
        if (codecConfig == null) {
          wearablesViewModel.setRecentError("Waiting for video from the glasses, try again in a second")
          return@launch
        }
        StreamingService.start(
            getApplication(),
            live = true,
            microphone = settings.audioSource != LiveAudioSource.NONE,
        )
        val error = livePublisher.start(settings, codecConfig, lastFrameWidth, lastFrameHeight)
        if (error != null) {
          wearablesViewModel.setRecentError(error)
          if (stream != null) StreamingService.start(getApplication())
        }
      } finally {
        _uiState.update { it.copy(isStartingLive = false) }
      }
    }
  }

  fun stopLive() {
    livePublisher.stop()
    // Back to the normal preview notification (no mic type) while the glasses stream continues.
    if (stream != null) StreamingService.start(getApplication())
  }

  // MARK: - Dismissers

  fun dismissCapturePreview() {
    val preview = _uiState.value.activePreview
    _uiState.update { it.copy(activePreview = null) }
    if (preview is CapturePreview.Video) {
      // The clip lives in the cache dir, exposed as a FileProvider content URI; delete through the
      // resolver so it resolves back to the real cache file (the URI's path is the provider
      // mapping,
      // not a filesystem path). Photos are held in memory — nothing on disk to clean up.
      viewModelScope.launch(Dispatchers.IO) {
        runCatching {
          getApplication<Application>().contentResolver.delete(preview.uri, null, null)
        }
            .onFailure { Log.w(TAG, "Failed to delete temp recording", it) }
      }
    }
  }

  // MARK: - Photo decoding

  private fun decodePhoto(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> decodeWithOrientation(photo.data)
      }

  // The glasses store orientation in an EXIF tag that BitmapFactory/ImageDecoder don't apply for
  // HEIC, so read TAG_ORIENTATION and rotate — otherwise the preview and shared image are sideways.
  private fun decodeWithOrientation(data: ByteBuffer): Bitmap? {
    val buffer = data.duplicate().apply { rewind() }
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
      bitmap?.recycle()
      Log.e(TAG, "Failed to decode captured photo")
      return null
    }

    val matrix = exifOrientationMatrix(bytes)
    if (matrix.isIdentity) return bitmap

    // Rotating allocates a second full-size bitmap; recycle the source, and fall back to the
    // unrotated image if the device is too low on memory to make the copy.
    return try {
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        bitmap.recycle()
      }
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to rotate captured photo", e)
      bitmap
    }
  }

  private fun exifOrientationMatrix(bytes: ByteArray): Matrix {
    val orientation =
        try {
          ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
          }
        } catch (e: IOException) {
          Log.w(TAG, "Failed to read EXIF orientation", e)
          ExifInterface.ORIENTATION_NORMAL
        }
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    return matrix
  }

  // MARK: - Unregistration

  /**
   * Ends the session once the app is no longer registered. Unregistering does not stop a live
   * [DeviceSession] — the SDK leaves that to the app — and this view model is scoped to the
   * activity rather than the camera screen, so nothing else would tear the session down before the
   * next registration brought the screen back mid-stream.
   *
   * Stopping is enough on its own: it closes the attached capabilities, and the resulting terminal
   * session and stream states drive the existing collectors through [onStreamTerminated] and
   * [cleanupSession].
   */
  private fun handleUnregistered() {
    session?.stop()
  }

  override fun onCleared() {
    super.onCleared()
    livePublisher.close()
    clearStreamResources()
    session?.stop()
    cleanupSession()
    audioInputHandler.cleanup()
    videoRecorder.close()
    recorderChannel.close()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST")
        return CameraViewModel(application, wearablesViewModel) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
