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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
 * Sprint 2 deliberately limits the scope:
 *   - IPv4 only
 *   - plain UDP/53 upstream (no DoT/DoH yet)
 *   - no TCP/53 fallback (large responses are uncommon for the lists we
 *     bundle; truncated UDP responses are handled by the client)
 */
class SentinelVpnService : VpnService() {

    private var tunnelFd: ParcelFileDescriptor? = null
    private var workerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var blocklist: BlocklistRepository

    override fun onCreate() {
        super.onCreate()
        blocklist = BlocklistRepository(applicationContext)
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
            workerJob = scope.launch { runLoop(fd) }
            Log.i(TAG, "VPN tunnel established")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${error.message}", error)
            shutdown()
        }
    }

    private suspend fun runLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(32 * 1024)
        var notificationCounter = 0
        while (scope.isActive) {
            val read = try {
                input.read(buffer)
            } catch (error: Exception) {
                if (!scope.isActive) break
                Log.w(TAG, "tun read interrupted: ${error.message}")
                break
            }
            if (read <= 0) continue
            val byteBuffer = ByteBuffer.wrap(buffer, 0, read).asReadOnlyBuffer()
            handlePacket(byteBuffer, buffer, read, output)
            notificationCounter += 1
            if (notificationCounter and 0xFF == 0) {
                updateNotification()
            }
        }
        Log.i(TAG, "VPN read loop exited")
    }

    private fun handlePacket(
        readView: ByteBuffer,
        rawBuffer: ByteArray,
        length: Int,
        out: FileOutputStream,
    ) {
        val workBuffer = ByteBuffer.wrap(rawBuffer.copyOf(length))
        val ip = IpPacketParser.parseIpv4(workBuffer) ?: run {
            // Non-IPv4 (e.g. IPv6 if it ever sneaks through). Pass through.
            writeRaw(out, rawBuffer, length)
            return
        }
        if (ip.protocol != IpPacketParser.PROTOCOL_UDP) {
            writeRaw(out, rawBuffer, length)
            return
        }
        val udp = IpPacketParser.parseUdp(workBuffer) ?: run {
            writeRaw(out, rawBuffer, length)
            return
        }
        if (udp.destPort != DNS_PORT) {
            writeRaw(out, rawBuffer, length)
            return
        }

        val payloadStart = ip.headerStart + ip.headerLength + 8
        val payloadEnd = ip.headerStart + ip.totalLength
        if (payloadEnd > length || payloadStart >= payloadEnd) {
            writeRaw(out, rawBuffer, length)
            return
        }
        val dnsPayload = rawBuffer.copyOfRange(payloadStart, payloadEnd)
        val query = DnsPacketParser.parseQuery(dnsPayload)
        if (query == null) {
            writeRaw(out, rawBuffer, length)
            return
        }
        VpnStats.recordQuery()

        val verdict = blocklist.lookup(query.qName)
        if (verdict is MatchResult.Allowed) {
            forwardUpstream(ip, udp, dnsPayload, out)
            return
        }
        val category = verdict.category ?: return
        val response = DnsPacketParser.buildNxdomainResponse(query)
        val reply = IpPacketParser.buildIpv4UdpReply(ip, udp, response)
        try {
            out.write(reply)
            VpnStats.recordBlock(query.qName, category)
        } catch (error: Exception) {
            VpnStats.recordError()
            Log.w(TAG, "Failed to write NXDOMAIN reply: ${error.message}")
        }
    }

    private fun forwardUpstream(
        ip: Ipv4Header,
        udp: UdpHeader,
        dnsPayload: ByteArray,
        out: FileOutputStream,
    ) {
        val upstream = UpstreamDnsConfig.current()
        val socket = DatagramSocket().apply { soTimeout = UPSTREAM_TIMEOUT_MS }
        try {
            if (!protect(socket)) {
                Log.w(TAG, "protect(socket) returned false; dropping query")
                VpnStats.recordError()
                socket.close()
                return
            }
            val tryServers = listOfNotNull(upstream.primary, upstream.secondary).distinct()
            var response: ByteArray? = null
            for (server in tryServers) {
                response = sendRecv(socket, server, dnsPayload)
                if (response != null) break
            }
            if (response == null) {
                VpnStats.recordError()
                return
            }
            val reply = IpPacketParser.buildIpv4UdpReply(ip, udp, response)
            out.write(reply)
            VpnStats.recordForwarded()
        } catch (error: Exception) {
            Log.w(TAG, "Upstream DNS failure: ${error.message}")
            VpnStats.recordError()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendRecv(socket: DatagramSocket, server: String, payload: ByteArray): ByteArray? {
        return try {
            val addr = InetAddress.getByName(server)
            val outPacket = DatagramPacket(payload, payload.size, addr, DNS_PORT)
            socket.send(outPacket)
            val replyBuf = ByteArray(4096)
            val inPacket = DatagramPacket(replyBuf, replyBuf.size)
            socket.receive(inPacket)
            replyBuf.copyOf(inPacket.length)
        } catch (error: Exception) {
            null
        }
    }

    private fun writeRaw(out: FileOutputStream, buffer: ByteArray, length: Int) {
        try {
            out.write(buffer, 0, length)
        } catch (error: Exception) {
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
        private const val UPSTREAM_TIMEOUT_MS = 5_000
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
