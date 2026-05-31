package com.sentinel.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sentinel.app.LinkGateActivity
import com.sentinel.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * DNS-only VpnService. We claim the whole IPv4 routing table so the
 * kernel hands us every IPv4 packet from userspace; for non-DNS traffic
 * we just write the packet back to the tun fd (transparent passthrough).
 * For UDP/53 we run the query through the [BlocklistRepository] and either
 * synthesise an NXDOMAIN response or forward it via a protected
 * DatagramSocket to upstream Cloudflare (1.1.1.1).
 *
 * Foreground service with a sticky notification, as required by the VPN
 * platform contract on every Android version we target.
 *
 * Concurrency model (Sprint Quality refactor):
 *   - The read loop ([runLoop]) ONLY reads, parses, and makes the block
 *     decision, then hands work off without ever blocking on the network.
 *   - Allowed queries are served from [DnsAnswerCache] on a hit, otherwise
 *     dispatched to [DnsForwarder], which owns ONE persistent protected
 *     socket and forwards asynchronously (no head-of-line blocking).
 *   - ALL writes to the tun fd go through a single-writer [TunWriter], so
 *     the read loop and the forwarder's receive loop never corrupt the IP
 *     stream by writing concurrently.
 *
 * Scope (still deliberately limited):
 *   - IPv4 only
 *   - plain UDP/53 upstream (no DoT/DoH yet)
 *   - no TCP/53 fallback
 */
class SentinelVpnService : VpnService() {

    private var tunnelFd: ParcelFileDescriptor? = null
    private var workerJob: Job? = null
    private var notificationJob: Job? = null
    private var tunWriter: TunWriter? = null
    private var dnsForwarder: DnsForwarder? = null
    private val dnsCache = DnsAnswerCache()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var blocklist: BlocklistRepository

    override fun onCreate() {
        super.onCreate()
        // Singleton: shares state with the LinkGateActivity-side instance
        // so user-whitelist updates issued by Settings reach the running
        // tunnel without a service restart. The same instance also loads
        // the persisted user whitelist from disk, so it survives device
        // reboot when Android auto-restarts the VPN before Flutter starts.
        blocklist = BlocklistRepository.getInstance(applicationContext)
        VpnControllerHolder.markRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startForegroundWithNotification(buildStatusText(0))
        if (workerJob?.isActive == true) return START_STICKY
        startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        scope.launch { blocklist.ensureLoaded() }
        try {
            val builder = Builder()
                .setSession(SESSION_NAME)
                .addAddress(LOCAL_TUN_ADDRESS, 32)
                // Route only the sinkhole DNS IP through the tunnel.
                // Combined with addDnsServer below, this makes Android
                // send every DNS query to us (because we advertise the
                // DNS server) while leaving every other packet to the
                // system's default network. This is the standard
                // "DNS-only VPN" pattern (Blokada, DNS66, AdGuard Home).
                // Routing 0.0.0.0/0 here would capture all traffic and
                // require a full userspace IP forwarder, which is not
                // what we want.
                .addRoute(SINKHOLE_DNS, 32)
                .addDnsServer(SINKHOLE_DNS)
                .setBlocking(true)
                .setMtu(1500)
            // Exclude our own package from the VPN so our upstream
            // DatagramSocket (protected with protect()) is the only path
            // out. Older Androids ignore this gracefully.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (error: Exception) {
                Log.w(TAG, "addDisallowedApplication failed: ${error.message}")
            }
            val fd = builder.establish()
            if (fd == null) {
                Log.e(TAG, "VpnService.Builder.establish() returned null")
                shutdown()
                return
            }
            tunnelFd = fd

            // Single-writer tun sink, shared by the read loop and the
            // forwarder's receive loop.
            val writer = TunWriter(FileOutputStream(fd.fileDescriptor), scope)
            // Async forwarder with one persistent protected socket. start()
            // protect()s the socket and launches the receive loop; it
            // throws if protect() fails, which we treat as a fatal
            // establish failure (an unprotected socket would loop back
            // into the tun).
            val forwarder = DnsForwarder(
                protect = { socket -> protect(socket) },
                tunWriter = writer,
                cache = dnsCache,
                scope = scope,
            )
            forwarder.start()
            tunWriter = writer
            dnsForwarder = forwarder

            workerJob = scope.launch { runLoop(fd) }
            // Notification refresh moved OFF the packet path onto a slow
            // timer so it never adds latency or allocation to the hot loop.
            notificationJob = scope.launch {
                while (isActive) {
                    delay(NOTIFICATION_INTERVAL_MS)
                    updateNotification()
                }
            }
            Log.i(TAG, "VPN tunnel established")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${error.message}", error)
            shutdown()
        }
    }

    private suspend fun runLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(32 * 1024)
        while (scope.isActive) {
            val read = try {
                input.read(buffer)
            } catch (error: Exception) {
                if (!scope.isActive) break
                Log.w(TAG, "tun read interrupted: ${error.message}")
                break
            }
            if (read <= 0) continue
            handlePacket(buffer, read)
        }
        Log.i(TAG, "VPN read loop exited")
    }

    /**
     * Parse one packet off the (reused) read buffer and act on it WITHOUT
     * blocking: NXDOMAIN replies and cache hits are enqueued to the tun
     * writer, allowed misses are dispatched to the async forwarder, and
     * everything else is passed through. The read buffer is reused by the
     * next read, so any bytes that outlive this call are copied first.
     */
    private fun handlePacket(rawBuffer: ByteArray, length: Int) {
        val writer = tunWriter ?: return
        val view = ByteBuffer.wrap(rawBuffer, 0, length).asReadOnlyBuffer()
        val ip = IpPacketParser.parseIpv4(view) ?: return passthrough(writer, rawBuffer, length)
        if (ip.protocol != IpPacketParser.PROTOCOL_UDP) {
            return passthrough(writer, rawBuffer, length)
        }
        val udp = IpPacketParser.parseUdp(view) ?: return passthrough(writer, rawBuffer, length)
        if (udp.destPort != DNS_PORT) {
            return passthrough(writer, rawBuffer, length)
        }

        val payloadStart = ip.headerStart + ip.headerLength + 8
        val payloadEnd = ip.headerStart + ip.totalLength
        if (payloadEnd > length || payloadStart >= payloadEnd) {
            return passthrough(writer, rawBuffer, length)
        }
        val dnsPayload = rawBuffer.copyOfRange(payloadStart, payloadEnd)
        val query = DnsPacketParser.parseQuery(dnsPayload)
            ?: return passthrough(writer, rawBuffer, length)
        VpnStats.recordQuery()

        val verdict = blocklist.lookup(query.qName)
        if (verdict !is MatchResult.Allowed) {
            val category = verdict.category ?: return
            val response = DnsPacketParser.buildNxdomainResponse(query)
            val reply = IpPacketParser.buildIpv4UdpReply(ip, udp, response)
            if (writer.enqueue(reply)) {
                VpnStats.recordBlock(query.qName, category)
            } else {
                VpnStats.recordError()
            }
            return
        }

        // Allowed: serve from cache on a hit (no upstream round-trip),
        // otherwise hand off to the async forwarder.
        val cached = dnsCache.get(DnsAnswerCache.Key(query.qName, query.qType))
        if (cached != null) {
            val restamped = DnsPacketParser.rewriteTransactionId(cached, query.transactionId)
            val reply = IpPacketParser.buildIpv4UdpReply(ip, udp, restamped)
            // A cache hit is an allowed query served without an upstream
            // round-trip; counted as "forwarded" (i.e. successfully served)
            // for the dashboard, consistent with VpnStats.warmFromDb's
            // queries = forwarded + blocked partition.
            if (writer.enqueue(reply)) {
                VpnStats.recordForwarded()
            } else {
                VpnStats.recordError()
            }
            return
        }
        dnsForwarder?.forward(query, ip, udp, dnsPayload)
    }

    private fun passthrough(writer: TunWriter, rawBuffer: ByteArray, length: Int) {
        // Copy: the read buffer is reused by the next read while this write
        // is still pending in the TunWriter queue. Pass-through is the rare
        // path (the tunnel routes only the sinkhole DNS IP), so the copy is
        // acceptable and only happens on this branch.
        if (!writer.enqueue(rawBuffer.copyOf(length))) {
            VpnStats.recordError()
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        workerJob?.cancel()
        workerJob = null
        notificationJob?.cancel()
        notificationJob = null
        dnsForwarder?.close()
        dnsForwarder = null
        tunWriter?.close()
        tunWriter = null
        try {
            tunnelFd?.close()
        } catch (_: Exception) {}
        tunnelFd = null
        scope.cancel()
        VpnControllerHolder.markRunning(false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val text = buildStatusText(VpnStats.totalBlocksFast())
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        ensureNotificationChannel()
        val openIntent = Intent(this, LinkGateActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openPending = PendingIntent.getActivity(this, 0, openIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sentinel_notification)
            .setColor(ContextCompat.getColor(this, R.color.sentinel_brand_primary))
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun buildStatusText(blocks: Long): String {
        val tmpl = getString(R.string.vpn_notification_text_template)
        return tmpl.replace("{blocks}", blocks.toString())
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.vpn_notification_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SentinelVpnService"
        const val ACTION_STOP = "com.sentinel.app.vpn.STOP"
        private const val SESSION_NAME = "Sentinel"
        private const val LOCAL_TUN_ADDRESS = "10.0.0.2"
        private const val SINKHOLE_DNS = "10.0.0.1"
        private const val DNS_PORT = 53
        private const val NOTIFICATION_INTERVAL_MS = 2_000L
        private const val NOTIFICATION_ID = 9301
        const val CHANNEL_ID = "sentinel_vpn"
    }
}

/**
 * Tiny holder so the controller can observe the running state without
 * relying on the service object lifecycle.
 */
internal object VpnControllerHolder {
    @Volatile
    private var running: Boolean = false
    fun markRunning(value: Boolean) { running = value }
    fun isRunning(): Boolean = running
}
