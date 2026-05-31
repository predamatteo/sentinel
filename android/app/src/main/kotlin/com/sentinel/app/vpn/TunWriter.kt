package com.sentinel.app.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.OutputStream

/**
 * Single-writer guard over the tun file descriptor's [OutputStream].
 *
 * Going async, packets are produced from two places — the read/dispatch
 * loop (NXDOMAIN replies, cache hits, pass-through) and the upstream
 * receive loop (forwarded replies). A raw [java.io.FileOutputStream] is
 * NOT safe for concurrent writes from two threads: interleaved byte runs
 * corrupt the IP stream. All writes therefore funnel through this class,
 * which is drained by exactly ONE coroutine.
 *
 * Producers call [enqueue], which is non-blocking ([Channel.trySend]): if
 * the buffer is full the packet is dropped and an error is recorded, so a
 * slow tun can never block the read loop (back-pressure-with-drop). Drops
 * are observable in [VpnStats] so the concurrent-load test can confirm
 * they stay at zero under normal use.
 *
 * The caller owns [out] and must keep it open for the lifetime of this
 * writer; [close] stops the drain but does not close [out] (the service's
 * shutdown closes the tun fd).
 */
class TunWriter(
    private val out: OutputStream,
    scope: CoroutineScope,
    capacity: Int = 256,
) {
    private val channel = Channel<ByteArray>(capacity)

    private val drainJob: Job = scope.launch(Dispatchers.IO) {
        for (packet in channel) {
            try {
                out.write(packet)
            } catch (error: Exception) {
                VpnStats.recordError()
                Log.w(TAG, "tun write failed: ${error.message}")
            }
        }
    }

    /**
     * Enqueue a fully-formed packet for writing. Non-blocking: returns
     * immediately. Returns true if the packet was accepted, false if the
     * buffer was full and it was dropped. Callers decide how to account a
     * drop so a single packet is never counted as both a success and an
     * error.
     */
    fun enqueue(packet: ByteArray): Boolean = channel.trySend(packet).isSuccess

    /** Stop accepting packets and cancel the drain coroutine. */
    fun close() {
        channel.close()
        drainJob.cancel()
    }

    /**
     * Test-only: close the channel and wait for the drain coroutine to
     * flush every buffered packet (no cancel), so a test can assert on the
     * written bytes deterministically.
     */
    internal suspend fun closeAndJoinForTest() {
        channel.close()
        drainJob.join()
    }

    companion object {
        private const val TAG = "TunWriter"
    }
}
