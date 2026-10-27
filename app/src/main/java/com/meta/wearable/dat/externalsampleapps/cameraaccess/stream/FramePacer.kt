// FramePacer - jitter buffer for the bursty glasses video
//
// Frames from the glasses arrive in clumps: nothing for a moment, then several at once. Sent on
// as-is, that becomes stutter followed by a "fast-forward" burst. The pacer holds frames for a
// target delay and releases them evenly, on the schedule of their capture timestamps.
//
// It keeps that buffer topped up for the whole session:
//  - margin = how far ahead of the output we are (queued video + time until the next frame is due)
//  - margin below half the target (after a stall) -> play ~8% slower until it's rebuilt
//  - margin above 1.5x the target -> play ~8% faster until it's back down
//  - a frame later than its slot (a stall longer than the whole buffer) is shown immediately and
//    the schedule restarts from it; the slowdown then rebuilds the buffer, so smoothing recovers
//    instead of being lost for the rest of the stream.
//
// [scheduleShiftUs] reports how far the schedule has moved from its starting point, so audio can
// be delayed by the same amount and stay in sync.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class FramePacer(
    private val name: String,
    private val sink: (data: ByteArray, presentationTimeUs: Long, isCodecConfig: Boolean) -> Unit,
) {
  private class Item(val data: ByteArray, val ptsUs: Long, val isConfig: Boolean)

  companion object {
    private const val TAG = "GlassesLive:Pacer"
    private const val CAPACITY = 400
    private const val LATE_TOLERANCE_US = 30_000L
    // Max schedule adjustment per frame: ~2.5 ms at 30 fps ≈ 8% speed change, not noticeable.
    private const val MAX_ADJUST_PER_FRAME_US = 2_500L
    // Timestamp jumps bigger than this (glasses restarted the stream) re-anchor everything.
    private const val MAX_PTS_GAP_US = 3_000_000L
  }

  /** Target buffering in microseconds. 0 = pass frames through immediately. */
  @Volatile var delayUs: Long = 0L

  /** Schedule movement since start (µs, positive = video playing later than originally). */
  @Volatile var scheduleShiftUs: Long = 0L
    private set

  /** Total frames released; the UI samples it for the "out" fps. */
  @Volatile var emittedFrames: Long = 0L
    private set

  private val queue = LinkedBlockingQueue<Item>(CAPACITY)
  @Volatile private var newestPtsUs = Long.MIN_VALUE
  @Volatile private var running = false
  private var thread: Thread? = null

  // Schedule: frame with pts P is due at clock (P + offsetUs).
  private var offsetUs = 0L
  private var initialOffsetUs = 0L
  private var anchored = false
  @Volatile private var reanchor = false
  private var lastPtsUs = Long.MIN_VALUE

  fun start() {
    if (running) return
    running = true
    anchored = false
    lastPtsUs = Long.MIN_VALUE
    newestPtsUs = Long.MIN_VALUE
    scheduleShiftUs = 0L
    emittedFrames = 0L
    thread =
        Thread({ loop() }, "FramePacer-$name").apply {
          priority = Thread.MAX_PRIORITY
          start()
        }
  }

  fun stop() {
    running = false
    thread?.interrupt()
    thread = null
    queue.clear()
  }

  fun offer(data: ByteArray, presentationTimeUs: Long, isCodecConfig: Boolean) {
    if (!running) return
    if (!isCodecConfig) newestPtsUs = presentationTimeUs
    if (!queue.offer(Item(data, presentationTimeUs, isCodecConfig))) {
      // Far more than the buffer can hold: the consumer is stuck. Start fresh.
      Log.w(TAG, "Pacer overflow, dropping ${queue.size} frames")
      queue.clear()
      reanchor = true
      queue.offer(Item(data, presentationTimeUs, isCodecConfig))
    }
  }

  private fun nowUs(): Long = System.nanoTime() / 1000

  private fun loop() {
    while (running) {
      val item =
          try {
            queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
          } catch (_: InterruptedException) {
            break
          }
      val delay = delayUs
      if (delay <= 0L || item.isConfig) {
        emit(item)
        continue
      }

      val now = nowUs()
      val jumped =
          lastPtsUs != Long.MIN_VALUE &&
              (item.ptsUs < lastPtsUs || item.ptsUs - lastPtsUs > MAX_PTS_GAP_US)
      if (!anchored || jumped || reanchor) {
        // First frame, glasses restarted, or overflow: schedule it one full buffer from now.
        offsetUs = now + delay - item.ptsUs
        // Keep the reported shift continuous (only the very first anchor defines zero).
        initialOffsetUs = if (!anchored) offsetUs else offsetUs - scheduleShiftUs
        anchored = true
        reanchor = false
      }
      lastPtsUs = item.ptsUs

      var due = item.ptsUs + offsetUs
      val slack = due - now
      if (slack < -LATE_TOLERANCE_US) {
        // Stall longer than the buffer: show this frame now and continue from here.
        offsetUs = now - item.ptsUs
        due = now
      } else {
        val newest = newestPtsUs
        val queued = if (newest != Long.MIN_VALUE) maxOf(0L, newest - item.ptsUs) else 0L
        val margin = slack + queued
        if (margin < delay / 2) {
          // Buffer ran low: slow down a touch to rebuild it.
          val step = minOf(delay - margin, MAX_ADJUST_PER_FRAME_US)
          offsetUs += step
          due += step
        } else if (margin > delay + delay / 2) {
          // Too much buffered: speed up a touch to bring delay back down.
          val step = minOf(margin - delay, MAX_ADJUST_PER_FRAME_US)
          offsetUs -= step
          due -= step
        }
      }
      scheduleShiftUs = offsetUs - initialOffsetUs

      var wait = due - nowUs()
      while (wait > 0 && running) {
        LockSupport.parkNanos(wait * 1000)
        wait = due - nowUs()
      }
      if (!running) break
      emit(item)
    }
  }

  private fun emit(item: Item) {
    try {
      sink(item.data, item.ptsUs, item.isConfig)
      if (!item.isConfig) emittedFrames++
    } catch (e: Exception) {
      Log.e(TAG, "Sink failed", e)
    }
  }
}
