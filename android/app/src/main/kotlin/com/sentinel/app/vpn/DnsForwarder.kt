package com.sentinel.app.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asynchronous DNS upstream forwarder.
 *
 * Replaces the old synchronous send+receive that ran inline on the single
 * tun read loop (which serialized every device DNS query one round-trip at
 * a time and could freeze all resolution for up to 2x5s). Here:
 *
 *  - ONE [DatagramSocket] is [protect]ed exactly once at [start] and
 *    reused for every query (no per-query socket/protect()/close churn).
 *    protect() is mandatory: an unprotected socket's upstream packets
 *    would re-enter the tun via the sinkhole route and loop forever.
 *  - [forward] returns immediately: it registers a pending entry and
 *    dispatches the send + timeout to [scope], so the read loop never
 *    blocks on the network.
 *  - A single [receiveLoop] coroutine reads all upstream replies and
 *    correlates them back to the requesting client.
 *
 * Correlation is NAT-style: each outgoing query is re-stamped with a
 * forwarder-allocated 16-bit id ([nextId]) used as the map key, and the
 * client's original transaction id is restored on the reply. With sub-
 * second entry lifetimes the 16-bit id space makes collisions negligible,
 * and the key is unambiguous regardless of how many clients share a txid.
 *
 * Timeout strategy (happy-eyeballs-ish): send to the primary upstream;
 * if no reply within [perAttemptTimeoutMs], re-send to the secondary on
 * the same socket (first reply wins); declare failure only after the
 * second window. Worst case ~2x[perAttemptTimeoutMs] (~3s) instead of the
 * old 10s, and many queries are in flight concurrently on one socket.
 */
internal class DnsForwarder(
    private val protect: (DatagramSocket) -> Boolean,
    private val tunWriter: TunWriter,
    private val cache: DnsAnswerCache,
    private val scope: CoroutineScope,
    private val perAttemptTimeoutMs: Long = DEFAULT_PER_ATTEMPT_TIMEOUT_MS,
) {
    private val socket = DatagramSocket()
    private val pending = ConcurrentHashMap<Int, PendingQuery>()
    private val idCounter = AtomicInteger(0)
    private var receiveJob: Job? = null

    private data class PendingQuery(
        val allocatedId: Int,
        val originalTxid: Int,
        val ip: Ipv4Header,
        val udp: UdpHeader,
        val qName: String,
        val qType: Int,
    )

    /**
     * Protect the upstream socket and start the receive loop. Throws if
     * protect() fails — the caller (SentinelVpnService.startTunnel) treats
     * that as a fatal tunnel-establish failure, because forwarding through
     * an unprotected socket would loop back into the tun.
     */
    fun start() {
        if (!protect(socket)) {
            // Close the socket we opened in the constructor so a failed
            // start (e.g. protect denied during boot) does not leak an FD.
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw IllegalStateException("protect(forwarder socket) returned false")
        }
        socket.soTimeout = 0 // blocking receive; we cancel via socket.close()
        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
    }

    /**
     * Register [query] and dispatch a non-blocking upstream send. Returns
     * immediately; the read loop is never blocked on the network.
     */
    fun forward(query: DnsQuery, ip: Ipv4Header, udp: UdpHeader, dnsPayload: ByteArray) {
        val allocatedId = registerPending(query, ip, udp)
        if (allocatedId < 0) {
            // Correlation table saturated (pathological in-flight count);
            // fail this query rather than risk delivering a reply to the
            // wrong client by overwriting a live entry.
            VpnStats.recordError()
            return
        }
        val outPayload = DnsPacketParser.rewriteTransactionId(dnsPayload, allocatedId)
        scope.launch { sendAttempt(allocatedId, outPayload, attempt = 1) }
    }

    /**
     * Allocate a 16-bit id that is NOT currently a live key, and register
     * [query] under it atomically. Returns the id, or -1 if no free id is
     * found within [MAX_ALLOC_ATTEMPTS] (only possible with tens of
     * thousands of simultaneous in-flight queries). The collision check is
     * what makes correlation unambiguous even under id wraparound.
     */
    private fun registerPending(query: DnsQuery, ip: Ipv4Header, udp: UdpHeader): Int {
        repeat(MAX_ALLOC_ATTEMPTS) {
            val id = nextId()
            val pq = PendingQuery(
                allocatedId = id,
                originalTxid = query.transactionId,
                ip = ip,
                udp = udp,
                qName = query.qName,
                qType = query.qType,
            )
            if (pending.putIfAbsent(id, pq) == null) return id
        }
        return -1
    }

    private suspend fun sendAttempt(allocatedId: Int, payload: ByteArray, attempt: Int) {
        val upstream = UpstreamDnsConfig.current()
        val server = when (attempt) {
            1 -> upstream.primary
            else -> upstream.secondary.ifBlank { upstream.primary }
        }
        try {
            val addr = InetAddress.getByName(server)
            socket.send(DatagramPacket(payload, payload.size, addr, DNS_PORT))
        } catch (error: Exception) {
            // Send itself failed — terminal error for this query.
            if (pending.remove(allocatedId) != null) {
                VpnStats.recordError()
            }
            return
        }
        delay(perAttemptTimeoutMs)
        // If the reply already arrived, the entry is gone and we stop.
        if (!pending.containsKey(allocatedId)) return
        val hasSecondary = upstream.secondary.isNotBlank() && upstream.secondary != upstream.primary
        if (attempt == 1 && hasSecondary) {
            sendAttempt(allocatedId, payload, attempt = 2)
        } else if (pending.remove(allocatedId) != null) {
            VpnStats.recordError()
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(MAX_REPLY)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (error: Exception) {
                if (!scope.isActive || socket.isClosed) break
                Log.w(TAG, "upstream receive failed: ${error.message}")
                continue
            }
            val length = packet.length
            if (length < 2) continue
            val replyId = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            val pq = pending.remove(replyId) ?: continue // late/duplicate/unsolicited
            val replyPayload = buffer.copyOf(length)
            // Restore the client's original transaction id, then wrap in an
            // IPv4/UDP datagram whose addresses are swapped so the client
            // sees it coming from the sinkhole DNS server.
            val restamped = DnsPacketParser.rewriteTransactionId(replyPayload, pq.originalTxid)
            val reply = IpPacketParser.buildIpv4UdpReply(pq.ip, pq.udp, restamped)
            // Count the outcome exactly once: a dropped enqueue is an error,
            // not a forward (and must not be counted as both).
            if (tunWriter.enqueue(reply)) {
                VpnStats.recordForwarded()
                maybeCache(pq, replyPayload)
            } else {
                VpnStats.recordError()
            }
        }
    }

    private fun maybeCache(pq: PendingQuery, replyPayload: ByteArray) {
        val info = DnsPacketParser.parseAnswerTtlAndRcode(replyPayload) ?: return
        // Only cache positive, untruncated answers with a real TTL. Negative
        // answers are intentionally not cached (no SOA parsing) so recovery
        // is immediate.
        if (!info.truncated && info.rcode == RCODE_NO_ERROR && info.minTtlSeconds > 0L) {
            cache.put(DnsAnswerCache.Key(pq.qName, pq.qType), replyPayload, info.minTtlSeconds)
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
        receiveJob?.cancel()
        pending.clear()
    }

    private fun nextId(): Int = idCounter.incrementAndGet() and 0xFFFF

    companion object {
        private const val TAG = "DnsForwarder"
        private const val DNS_PORT = 53
        private const val MAX_REPLY = 65535
        private const val RCODE_NO_ERROR = 0
        private const val MAX_ALLOC_ATTEMPTS = 1024
        const val DEFAULT_PER_ATTEMPT_TIMEOUT_MS = 1_500L
    }
}
