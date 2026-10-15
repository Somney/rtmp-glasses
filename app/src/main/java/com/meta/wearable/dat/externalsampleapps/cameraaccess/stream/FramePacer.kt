// FramePacer - smoothing buffer for the bursty glasses video
//
// Frames from the glasses arrive over Bluetooth/Wi-Fi in clumps: nothing for a moment, then
// several at once. Passed straight through, that turns into stutter followed by a "fast-forward"
// burst on the stream. The pacer holds each frame for a fixed delay and releases it on the
// schedule given by its capture timestamp, so the output is evenly spaced.
//
// - A frame later than its slot (a stall longer than the buffer) re-anchors the schedule.
// - If the buffer grows past 1.5x the target (clock drift, a long stall's backlog), the schedule
//   is nudged earlier by up to 2 ms per frame: an invisible catch-up instead of a jump.

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
    private const val CAPACITY = 300
    private const val LATE_TOLERANCE_US = 40_000L
    private const val MAX_CATCHUP_PER_FRAME_US = 2_000L
    // Timestamp jumps bigger than this (stream restart) re-anchor instead of waiting.
    private const val MAX_PTS_GAP_US = 3_000_000L
  }

  /** Target buffering in microseconds. 0 = pass frames through immediately. */
  @Volatile var delayUs: Long = 0L

  private val queue = LinkedBlockingQueue<Item>(CAPACITY)
  @Volatile private var running = false
  private var thread: Thread? = null

  private var anchorPtsUs = Long.MIN_VALUE
  private var anchorClockUs = 0L
  private var lastPtsUs = Long.MIN_VALUE

  fun start() {
    if (running) return
    running = true
    resetSchedule()
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
    if (!queue.offer(Item(data, presentationTimeUs, isCodecConfig))) {
      // Consumer can't keep up: drop the backlog and start fresh (next keyframe recovers).
      Log.w(TAG, "Pacer overflow, dropping ${queue.size} frames")
      queue.clear()
      queue.offer(Item(data, presentationTimeUs, isCodecConfig))
    }
  }

  private fun resetSchedule() {
    anchorPtsUs = Long.MIN_VALUE
    anchorClockUs = 0L
    lastPtsUs = Long.MIN_VALUE
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
      val ptsJumped =
          lastPtsUs != Long.MIN_VALUE &&
              (item.ptsUs < lastPtsUs || item.ptsUs - lastPtsUs > MAX_PTS_GAP_US)
      if (anchorPtsUs == Long.MIN_VALUE || ptsJumped) {
        anchorPtsUs = item.ptsUs
        anchorClockUs = now + delay
      }
      lastPtsUs = item.ptsUs

      var due = anchorClockUs + (item.ptsUs - anchorPtsUs)
      val slack = due - now
      if (slack < -LATE_TOLERANCE_US) {
        // Stall longer than the buffer: show this frame now and schedule the rest from here.
        anchorPtsUs = item.ptsUs
        anchorClockUs = now
        due = now
      } else if (slack > delay + delay / 2) {
        // Too much buffered: drift the schedule earlier a little at a time.
        val step = minOf(slack - delay, MAX_CATCHUP_PER_FRAME_US)
        anchorClockUs -= step
        due -= step
      }

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
    } catch (e: Exception) {
      Log.e(TAG, "Sink failed", e)
    }
  }
}
