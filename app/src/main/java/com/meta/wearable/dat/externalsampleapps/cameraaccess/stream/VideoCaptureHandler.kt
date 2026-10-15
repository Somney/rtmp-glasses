/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// VideoCaptureHandler - Streaming Compressed Video + Audio to MP4
//
// Receives compressed HEVC video frames from the DAT SDK and streams them directly
// to an MP4 file via MediaMuxer. No re-encoding of video is needed. Audio PCM is
// encoded incrementally to AAC and interleaved with the video track. The track opens on
// the first detectable keyframe so playback isn't black, falling back to any frame after a
// short grace period so recording always starts even when keyframes aren't detectable.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.annotation.GuardedBy
import java.nio.ByteBuffer

class VideoCaptureHandler {
  companion object {
    private const val TAG = "VideoCaptureHandler"
    private const val AAC_BIT_RATE = 128000
    private const val SAMPLE_RATE = AudioInputHandler.SAMPLE_RATE
    private const val BYTES_PER_SAMPLE = 2
    // If no detectable keyframe arrives within this many frames, open the video track anyway so
    // recording always starts (some streams, e.g. the emulator camera encoder, don't surface a
    // keyframe we can detect). ~2s at 24-30fps, comfortably longer than the 1s mock GOP.
    private const val MAX_FRAMES_BEFORE_RECORDING_START = 60
    // Nominal per-frame step used to space a sample that the monotonic floor clamp had to push past
    // the previous one.
    private const val FRAME_DURATION_US = 1_000_000L / 30
  }

  private class PendingSample(
      val data: ByteArray,
      val info: MediaCodec.BufferInfo,
      val isVideo: Boolean,
  )

  private val muxerLock = Any()
  private val audioEncoderLock = Any()

  // Muxer state — all writes serialized through muxerLock (see prepare/writeVideoFrame/etc.)
  @GuardedBy("muxerLock") private var muxer: MediaMuxer? = null
  @GuardedBy("muxerLock") private var videoTrackIndex = -1
  @GuardedBy("muxerLock") private var audioTrackIndex = -1
  @GuardedBy("muxerLock") private var muxerStarted = false
  // Latched if a MediaMuxer write throws so the recording aborts instead of crashing the host.
  @GuardedBy("muxerLock") private var muxerFailed = false

  // Video state — guarded by muxerLock; accessed by writeVideoFrame, tryStartMuxer, resetState.
  @GuardedBy("muxerLock") private var videoCsd: ByteArray? = null
  @GuardedBy("muxerLock") private var videoWidth = 0
  @GuardedBy("muxerLock") private var videoHeight = 0
  // PTS of the first written sample; later samples are offset from it so the track starts at 0.
  @GuardedBy("muxerLock") private var videoBaseTimeUs = 0L
  // True once the video track has opened — a dedicated started-flag rather than overloading
  // videoBaseTimeUs as a sentinel.
  @GuardedBy("muxerLock") private var videoStarted = false
  // Frames seen while still waiting to open the track, for the keyframe-wait fallback.
  @GuardedBy("muxerLock") private var framesBeforeStart = 0
  // PTS of the last written sample, for the monotonic floor clamp. -1 until the first sample.
  @GuardedBy("muxerLock") private var lastWrittenPtsUs = -1L

  // Audio encoder state — guarded by muxerLock for the reference; the MediaCodec's own
  // dequeue/queue operations happen on the caller thread under audioEncoderLock.
  @GuardedBy("muxerLock") private var audioEncoder: MediaCodec? = null
  @GuardedBy("muxerLock") private var audioOutputFormat: MediaFormat? = null
  @GuardedBy("muxerLock") private var audioInputTimeUs = 0L
  @GuardedBy("muxerLock") private var includeAudio = false

  // Pending samples buffered before muxer starts.
  @GuardedBy("muxerLock") private val pendingSamples = mutableListOf<PendingSample>()

  fun prepare(outputPath: String, includeAudio: Boolean) {
    synchronized(muxerLock) {
      this.includeAudio = includeAudio
      videoTrackIndex = -1
      audioTrackIndex = -1
      muxerStarted = false
      muxerFailed = false
      videoCsd = null
      videoBaseTimeUs = 0L
      videoStarted = false
      framesBeforeStart = 0
      lastWrittenPtsUs = -1L
      videoWidth = 0
      videoHeight = 0
      audioInputTimeUs = 0L
      audioOutputFormat = null
      pendingSamples.clear()

      muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

      if (includeAudio) {
        val format =
            MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    SAMPLE_RATE,
                    1,
                )
                .apply {
                  setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                  setInteger(
                      MediaFormat.KEY_AAC_PROFILE,
                      MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                  )
                }

        audioEncoder =
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { encoder ->
              encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
              encoder.start()
            }
      }
    }
  }

  /**
   * Primes the video CSD (VPS/SPS/PPS) from a codec-config frame captured during streaming. The SDK
   * delivers the config once at stream start, but recording usually begins later — without this the
   * muxer would never learn the format and the file would have no video track.
   */
  fun setInitialCodecConfig(data: ByteArray) {
    synchronized(muxerLock) {
      if (videoCsd == null) {
        extractHevcCsd(data)?.let { videoCsd = it }
      }
    }
  }

  /**
   * Writes a compressed HEVC frame. Returns true exactly once — when the video track opens. CSD
   * comes from [setInitialCodecConfig] or is extracted inline (real glasses embed VPS/SPS/PPS with
   * each keyframe). The track opens on the first detectable keyframe so playback isn't black, but
   * falls back to opening on any frame after [MAX_FRAMES_BEFORE_RECORDING_START] so recording
   * always starts even when the stream's keyframes aren't detectable as IRAP (e.g. the emulator
   * camera).
   */
  fun writeVideoFrame(
      data: ByteArray,
      presentationTimeUs: Long,
      width: Int,
      height: Int,
      isCodecConfig: Boolean = false,
  ): Boolean {
    synchronized(muxerLock) {
      if (muxer == null) return false
      if (muxerFailed) return false
      if (data.isEmpty()) return false

      // Capture CSD inline the first time we see it. The SDK's isCodecConfig flag is unreliable, so
      // we always parse: real glasses embed VPS/SPS/PPS alongside each keyframe, while the mock
      // sends them once (primed via setInitialCodecConfig).
      if (videoCsd == null) {
        extractHevcCsd(data)?.let { videoCsd = it }
      }

      // Capture dimensions from the first frame that reports them so the muxer can always open with
      // a valid format — without this, videoWidth/Height stayed 0 and addTrack later crashed.
      if (videoWidth <= 0 && width > 0) {
        videoWidth = width
        videoHeight = height
      }

      // Open the video track once the format is known. Prefer a keyframe so playback isn't black,
      // but don't wait forever — after a grace period open on any frame so the recording (and its
      // timer) always start. videoStarted is a dedicated flag rather than overloading
      // videoBaseTimeUs as a started-sentinel.
      var justStartedVideo = false
      if (!videoStarted) {
        if (videoCsd == null || videoWidth <= 0) return false
        framesBeforeStart++
        if (!isHevcKeyFrame(data) && framesBeforeStart < MAX_FRAMES_BEFORE_RECORDING_START) {
          return false
        }
        videoBaseTimeUs = presentationTimeUs
        videoStarted = true
        justStartedVideo = true
        tryStartMuxer()
      }

      // Offset by the base so the track starts at 0. Floor each sample at the previous + a frame so
      // a non-monotonic PTS across a pause/resume can't make MediaMuxer reject it
      // (ERROR_MALFORMED); forward gaps pass through.
      var adjustedPts = presentationTimeUs - videoBaseTimeUs
      if (lastWrittenPtsUs >= 0 && adjustedPts < lastWrittenPtsUs + FRAME_DURATION_US) {
        adjustedPts = lastWrittenPtsUs + FRAME_DURATION_US
      }
      lastWrittenPtsUs = adjustedPts
      // Flag the very first written sample as a sync sample so MediaMuxer produces a valid track
      // even when recording opened via the no-keyframe fallback; later frames use their real flag.
      val isKeyFrame = justStartedVideo || isHevcKeyFrame(data)
      val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

      val info = MediaCodec.BufferInfo()
      info.set(0, data.size, adjustedPts, flags)

      if (muxerStarted) {
        writeSample(videoTrackIndex, data, info)
      } else {
        pendingSamples.add(PendingSample(data.copyOf(), info, isVideo = true))
      }

      return justStartedVideo
    }
  }

  fun writeAudioPcm(data: ByteArray, offset: Int, size: Int) {
    // Use audioEncoderLock for the blocking encoder operations to avoid stalling
    // video writes on muxerLock. Only take muxerLock briefly for the actual muxer
    // writes inside drainAudioEncoder.
    synchronized(audioEncoderLock) {
      val encoder = synchronized(muxerLock) { audioEncoder } ?: return

      var remaining = size
      var currentOffset = offset

      while (remaining > 0) {
        val inputIndex = encoder.dequeueInputBuffer(5000)
        if (inputIndex < 0) {
          // Encoder is backed up; drop the rest of this chunk. Still advance the audio clock by the
          // dropped duration so the timeline stays aligned with elapsed capture — otherwise each
          // drop shifts later audio earlier and it progressively drifts ahead of the video.
          Log.w(TAG, "No audio encoder input buffer available, dropping $remaining bytes")
          synchronized(muxerLock) {
            audioInputTimeUs += (remaining.toLong() * 1_000_000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
          }
          break
        }
        val inputBuffer = encoder.getInputBuffer(inputIndex) ?: break
        inputBuffer.clear()
        val bytesToWrite = minOf(remaining, inputBuffer.remaining())
        inputBuffer.put(data, currentOffset, bytesToWrite)

        val pts: Long
        synchronized(muxerLock) {
          pts = audioInputTimeUs
          audioInputTimeUs +=
              (bytesToWrite.toLong() * 1_000_000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
        }
        encoder.queueInputBuffer(inputIndex, 0, bytesToWrite, pts, 0)
        currentOffset += bytesToWrite
        remaining -= bytesToWrite
      }

      synchronized(muxerLock) { drainAudioEncoder(endOfStream = false) }
    }
  }

  /**
   * Switches recording to video-only when the microphone is unavailable — either it never
   * initialized (no permission, no input device) or it initialized but delivers no PCM (e.g. an
   * emulator's virtual mic), so the AAC encoder never produces a format and the muxer would wait
   * for an audio track forever. Drops the audio track and starts the muxer so the video keyframe
   * isn't held indefinitely. A no-op once the muxer has already started (audio came through in
   * time).
   */
  fun markAudioUnavailable() {
    synchronized(audioEncoderLock) {
      synchronized(muxerLock) {
        if (muxerStarted) return
        forceVideoOnlyStart()
      }
    }
  }

  /** True once the muxer has started writing — i.e. the recording is actually capturing to disk. */
  fun isMuxerStarted(): Boolean = synchronized(muxerLock) { muxerStarted }

  // Caller must hold audioEncoderLock and muxerLock. Releases the audio encoder and starts the
  // muxer
  // video-only so any buffered video samples (the keyframe onward) finalize instead of being lost.
  @GuardedBy("muxerLock")
  private fun forceVideoOnlyStart() {
    includeAudio = false
    audioEncoder?.let { encoder ->
      try {
        encoder.stop()
        encoder.release()
      } catch (e: Exception) {
        Log.e(TAG, "Error releasing unused audio encoder: ${e.message}", e)
      }
    }
    audioEncoder = null
    audioOutputFormat = null
    pendingSamples.retainAll { it.isVideo }
    tryStartMuxer()
  }

  fun stopRecording(): Boolean {
    // Acquire audioEncoderLock first to ensure writeAudioPcm has finished,
    // then muxerLock for the final muxer operations.
    synchronized(audioEncoderLock) {
      synchronized(muxerLock) {
        // If audio was enabled but never produced a format (a silent/absent mic), the muxer is
        // still
        // waiting for the audio track. Finalize video-only so the buffered samples are written
        // instead of being lost — otherwise the file has no video track.
        if (!muxerStarted && videoCsd != null && videoStarted) {
          forceVideoOnlyStart()
        }

        audioEncoder?.let { encoder ->
          val eosIndex = encoder.dequeueInputBuffer(10000)
          if (eosIndex >= 0) {
            encoder.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          }
          drainAudioEncoder(endOfStream = true)
          try {
            encoder.stop()
            encoder.release()
          } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up audio encoder: ${e.message}", e)
          }
        }
        audioEncoder = null

        val hadVideo = videoTrackIndex >= 0 && !muxerFailed
        if (muxerStarted) {
          try {
            muxer?.stop()
          } catch (e: Exception) {
            Log.e(TAG, "Error stopping muxer: ${e.message}", e)
          }
        }
        try {
          muxer?.release()
        } catch (e: Exception) {
          Log.e(TAG, "Error releasing muxer: ${e.message}", e)
        }
        muxer = null
        muxerStarted = false
        pendingSamples.clear()

        return hadVideo
      }
    }
  }

  fun resetState() {
    synchronized(muxerLock) {
      pendingSamples.clear()
      muxerFailed = false
      videoCsd = null
      videoBaseTimeUs = 0L
      videoStarted = false
      framesBeforeStart = 0
      lastWrittenPtsUs = -1L
      videoWidth = 0
      videoHeight = 0
      audioInputTimeUs = 0L
      audioOutputFormat = null
    }
  }

  // MediaMuxer.writeSampleData throws IllegalStateException if it rejects a track (e.g. malformed
  // timestamps) or has already stopped. Swallow it and latch muxerFailed so one bad sample
  // finalizes
  // the recording as failed instead of crashing the host app from the stream thread.
  @GuardedBy("muxerLock")
  private fun writeSample(trackIndex: Int, data: ByteArray, info: MediaCodec.BufferInfo) {
    if (muxerFailed || trackIndex < 0) return
    try {
      muxer?.writeSampleData(trackIndex, ByteBuffer.wrap(data), info)
    } catch (e: IllegalStateException) {
      muxerFailed = true
      Log.e(TAG, "Muxer write failed; aborting recording", e)
    }
  }

  // Always called from inside `synchronized(muxerLock) { ... }` blocks. The outer
  // `synchronized(muxerLock)` here is redundant at runtime (Kotlin/Java monitors are reentrant) but
  // documents the locking contract for readers.
  private fun tryStartMuxer() {
    synchronized(muxerLock) {
      if (muxerStarted) return
      val mux = muxer ?: return
      val csd = videoCsd ?: return
      // Only start once the video track is genuinely ready. The audio encoder's format-change can
      // call this before any video frame; starting then would addTrack a 0x0 video format and
      // crash.
      if (!videoStarted || videoWidth <= 0 || videoHeight <= 0) return

      // addTrack only once: this can be called more than once before start() (e.g. the video track
      // opens while audio is pending, then markAudioUnavailable retries). Re-adding would create a
      // duplicate, sampleless track and MediaMuxer.stop() would fail to finalize the file.
      if (videoTrackIndex < 0) {
        val videoFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, videoWidth, videoHeight)
        videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        videoTrackIndex = mux.addTrack(videoFormat)
      }

      if (includeAudio) {
        val af = audioOutputFormat ?: return
        if (audioTrackIndex < 0) audioTrackIndex = mux.addTrack(af)
      }

      mux.start()
      muxerStarted = true

      for (sample in pendingSamples) {
        val trackIndex = if (sample.isVideo) videoTrackIndex else audioTrackIndex
        writeSample(trackIndex, sample.data, sample.info)
      }
      pendingSamples.clear()

      Log.d(TAG, "Muxer started: video=${videoWidth}x${videoHeight}, audio=$includeAudio")
    }
  }

  // See note on tryStartMuxer for why the redundant synchronized block is here.
  private fun drainAudioEncoder(endOfStream: Boolean) {
    synchronized(muxerLock) {
      val encoder = audioEncoder ?: return
      val bufferInfo = MediaCodec.BufferInfo()

      while (true) {
        val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10000 else 0)
        when {
          outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            audioOutputFormat = encoder.outputFormat
            tryStartMuxer()
          }
          outputIndex >= 0 -> {
            val outputBuffer = encoder.getOutputBuffer(outputIndex)
            if (outputBuffer != null && bufferInfo.size > 0) {
              val info = MediaCodec.BufferInfo()
              info.set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
              val dataCopy = ByteArray(bufferInfo.size)
              outputBuffer.position(bufferInfo.offset)
              outputBuffer.get(dataCopy)

              if (muxerStarted && audioTrackIndex >= 0) {
                writeSample(audioTrackIndex, dataCopy, info)
              } else {
                pendingSamples.add(PendingSample(dataCopy, info, isVideo = false))
              }
            }
            encoder.releaseOutputBuffer(outputIndex, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
          }
          else -> break
        }
      }
    }
  }
}

// --- HEVC NAL unit parsing (shared by VideoCaptureHandler and HevcParameterSetCollector) ---

private const val HEVC_NAL_VPS = 32
private const val HEVC_NAL_SPS = 33
private const val HEVC_NAL_PPS = 34
// IRAP (intra random-access point) NAL types: BLA 16-18, IDR 19-20, CRA 21. Any of them is a valid
// recording start point. Detecting only IDR misses CRA keyframes, which some encoders emit.
private const val HEVC_NAL_IRAP_FIRST = 16
private const val HEVC_NAL_IRAP_LAST = 21

private fun extractHevcCsd(data: ByteArray): ByteArray? {
  val parameterSets = mutableListOf<Pair<Int, Int>>()
  forEachNalUnit(data) { startCodeOffset, nalType, nextOffset ->
    if (nalType == HEVC_NAL_VPS || nalType == HEVC_NAL_SPS || nalType == HEVC_NAL_PPS) {
      parameterSets.add(startCodeOffset to nextOffset)
    }
  }
  if (parameterSets.isEmpty()) return null
  val totalSize = parameterSets.sumOf { it.second - it.first }
  val result = ByteArray(totalSize)
  var offset = 0
  for ((start, end) in parameterSets) {
    val length = end - start
    System.arraycopy(data, start, result, offset, length)
    offset += length
  }
  return result
}

private fun isHevcKeyFrame(data: ByteArray): Boolean {
  forEachNalUnit(data) { _, nalType, _ ->
    if (nalType in HEVC_NAL_IRAP_FIRST..HEVC_NAL_IRAP_LAST) return true
  }
  return false
}

private fun startsWithStartCode(data: ByteArray, offset: Int): Boolean {
  if (offset + 2 >= data.size) return false
  if (data[offset] != 0.toByte() || data[offset + 1] != 0.toByte()) return false
  if (data[offset + 2] == 1.toByte()) return true
  return offset + 3 < data.size && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()
}

private inline fun forEachNalUnit(
    data: ByteArray,
    action: (startCodeOffset: Int, nalType: Int, nextNalOffset: Int) -> Unit,
) {
  val nalStarts = mutableListOf<Pair<Int, Int>>()
  var i = 0
  while (i < data.size - 3) {
    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
      val startCodeLen: Int
      if (data[i + 2] == 1.toByte()) {
        startCodeLen = 3
      } else if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
        startCodeLen = 4
      } else {
        i++
        continue
      }
      val nalHeaderOffset = i + startCodeLen
      // Skip a zero-length NAL (a start code immediately followed by another). The SDK prefixes the
      // VPS with a double start code (00000001 00000001 40 01 ...); reading the second start code
      // as
      // a NAL header would misparse the VPS as type 0 and drop it from the CSD. Advance to the NAL
      // header (not past it) so the following real NAL is still detected.
      if (nalHeaderOffset < data.size && !startsWithStartCode(data, nalHeaderOffset)) {
        val nalType = (data[nalHeaderOffset].toInt() and 0x7E) shr 1
        nalStarts.add(i to nalType)
      }
      i = nalHeaderOffset
    } else {
      i++
    }
  }

  for (idx in nalStarts.indices) {
    val (offset, nalType) = nalStarts[idx]
    val nextOffset = if (idx + 1 < nalStarts.size) nalStarts[idx + 1].first else data.size
    action(offset, nalType, nextOffset)
  }
}

/**
 * Accumulates the HEVC parameter sets (VPS/SPS/PPS) seen across a stream so a recording started
 * mid-stream still gets a complete codec-specific-data set. The SDK emits the VPS once at stream
 * start, so deriving the CSD only from the frame present when recording begins can yield an
 * incomplete set — which makes MediaMuxer fail to finalize the file ("Missing codec specific
 * data"). Thread-safe: offered from the frame collector, read from the recording-control thread.
 */
class HevcParameterSetCollector {
  private val lock = Any()
  // nalType -> the full NAL unit (with start code); keeps the most recent of each.
  private val parameterSets = linkedMapOf<Int, ByteArray>()

  /** Records any VPS/SPS/PPS NAL units found in [frame]. */
  fun offer(frame: ByteArray) {
    synchronized(lock) {
      forEachNalUnit(frame) { startCodeOffset, nalType, nextOffset ->
        if (nalType == HEVC_NAL_VPS || nalType == HEVC_NAL_SPS || nalType == HEVC_NAL_PPS) {
          parameterSets[nalType] = frame.copyOfRange(startCodeOffset, nextOffset)
        }
      }
    }
  }

  /** The concatenated VPS+SPS+PPS in decode order, or null until all three have been seen. */
  fun complete(): ByteArray? {
    synchronized(lock) {
      val vps = parameterSets[HEVC_NAL_VPS] ?: return null
      val sps = parameterSets[HEVC_NAL_SPS] ?: return null
      val pps = parameterSets[HEVC_NAL_PPS] ?: return null
      return vps + sps + pps
    }
  }

  fun reset() {
    synchronized(lock) { parameterSets.clear() }
  }
}
