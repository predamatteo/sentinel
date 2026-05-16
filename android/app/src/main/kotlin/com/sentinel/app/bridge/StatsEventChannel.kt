package com.sentinel.app.bridge

import com.sentinel.app.analysis.AnalysisStats
import com.sentinel.app.persistence.StatsRepository
import com.sentinel.app.vpn.VpnStats
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper

/**
 * Streams combined dashboard stats (VPN + analysis + recent block events
 * from Room) to the Flutter side over an EventChannel.
 *
 * Sprint Quality switches the dashboard from `Timer.periodic(getStats)`
 * polling to a push model: when the Dart side subscribes we listen to
 * the [StatsRepository.recentBlockEvents] Flow and emit a fresh payload
 * on every Room update, plus a heartbeat every 1.5s so atomic in-memory
 * counters (incremented by the VPN packet path) still reach the UI.
 *
 * The keep-it-simple stance: payload mirrors the structure of
 * `VpnStats.snapshot()` extended with `linksChecked` so the Dart parser
 * already in place is reused as-is.
 */
class StatsEventChannel(
    private val repository: StatsRepository,
) : EventChannel.StreamHandler {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var channel: EventChannel? = null
    private var scope: CoroutineScope? = null
    private var heartbeat: Runnable? = null

    fun attach(engine: FlutterEngine) {
        channel = EventChannel(engine.dartExecutor.binaryMessenger, CHANNEL_NAME).also {
            it.setStreamHandler(this)
        }
    }

    fun dispose() {
        channel?.setStreamHandler(null)
        channel = null
        cancelHeartbeat()
        scope?.cancel()
        scope = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        val local = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = local
        // Push an immediate snapshot so the dashboard is never empty.
        emit(events)
        // Flow-based push: every Room insert into block_events triggers
        // a new emit. This is the fast path for the "Blocchi recenti" list.
        local.launch {
            // Reuse the bounded Flow exposed by the events DAO. The
            // collected list is dropped: we always recompute the full
            // snapshot to keep the wire format identical for every emit.
            repository.observeRecentBlocksFlow(RECENT_LIMIT).collectLatest {
                withContext(Dispatchers.Main) { emit(events) }
            }
        }
        // Heartbeat: in-memory counters (atomics) can advance without a
        // DAO write between events, so we tick every 1.5s while a
        // subscriber is attached. Cancelled in onCancel.
        startHeartbeat(events)
    }

    override fun onCancel(arguments: Any?) {
        cancelHeartbeat()
        scope?.cancel()
        scope = null
    }

    private fun startHeartbeat(events: EventChannel.EventSink) {
        cancelHeartbeat()
        val task = object : Runnable {
            override fun run() {
                emit(events)
                mainHandler.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat = task
        mainHandler.postDelayed(task, HEARTBEAT_MS)
    }

    private fun cancelHeartbeat() {
        heartbeat?.let { mainHandler.removeCallbacks(it) }
        heartbeat = null
    }

    private fun emit(events: EventChannel.EventSink) {
        val snapshot = VpnStats.snapshot().toMutableMap()
        snapshot["linksChecked"] = AnalysisStats.linksCheckedToday()
        events.success(snapshot)
    }

    companion object {
        const val CHANNEL_NAME = "com.sentinel.app/stats_events"
        private const val HEARTBEAT_MS = 1_500L
        private const val RECENT_LIMIT = 100
    }
}
