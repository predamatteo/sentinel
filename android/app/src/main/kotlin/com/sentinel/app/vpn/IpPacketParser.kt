package com.sentinel.app.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal IPv4 + UDP parser sufficient for DNS interception.
 *
 * We never touch IPv6 in Sprint 2: the VPN tunnel is built without
 * `addAddress` for an IPv6 prefix, so the kernel only routes IPv4 through
 * us. Anything non-IPv4 is forwarded back to the tun fd untouched by the
 * service (raw write-back is not the parser's job).
 *
 * Parsing is defensive: every shape mismatch returns null so the caller
 * can decide to drop or pass through the packet.
 */
internal object IpPacketParser {

    private const val IPV4_VERSION: Int = 4
    private const val MIN_HEADER_LENGTH: Int = 20
    const val PROTOCOL_UDP: Int = 17

    /**
     * Parse an IPv4 header from [buffer]. The buffer position is left at
     * the start of the IP payload on success.
     *
     * @return [Ipv4Header] on success, null on any structural problem.
     */
    fun parseIpv4(buffer: ByteBuffer): Ipv4Header? {
        if (buffer.remaining() < MIN_HEADER_LENGTH) return null
        val start = buffer.position()
        val versionAndIhl = buffer.get(start).toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != IPV4_VERSION) return null
        val ihlWords = versionAndIhl and 0x0F
        val headerLength = ihlWords * 4
        if (headerLength < MIN_HEADER_LENGTH) return null
        if (buffer.remaining() < headerLength) return null

        val totalLength = readUShort(buffer, start + 2)
        val protocol = buffer.get(start + 9).toInt() and 0xFF
        val sourceIp = ByteArray(4).also { buffer.duplicate().apply { position(start + 12) }.get(it) }
        val destIp = ByteArray(4).also { buffer.duplicate().apply { position(start + 16) }.get(it) }

        buffer.position(start + headerLength)
        return Ipv4Header(
            headerStart = start,
            headerLength = headerLength,
            totalLength = totalLength,
            protocol = protocol,
            sourceIp = sourceIp,
            destIp = destIp,
        )
    }

    /**
     * Parse a UDP header starting at [buffer]'s current position. Leaves
     * the buffer position at the start of the UDP payload on success.
     */
    fun parseUdp(buffer: ByteBuffer): UdpHeader? {
        if (buffer.remaining() < 8) return null
        val start = buffer.position()
        val sourcePort = readUShort(buffer, start)
        val destPort = readUShort(buffer, start + 2)
        val length = readUShort(buffer, start + 4)
        if (length < 8) return null
        buffer.position(start + 8)
        return UdpHeader(
            sourcePort = sourcePort,
            destPort = destPort,
            length = length,
        )
    }

    /**
     * Build a fully formed IPv4 + UDP packet that swaps source and
     * destination addresses/ports of [original] and carries [payload] as
     * its UDP body. The IP and UDP checksums are computed from scratch.
     *
     * Returns a fresh buffer ready to be written to the tun fd.
     */
    fun buildIpv4UdpReply(
        original: Ipv4Header,
        originalUdp: UdpHeader,
        payload: ByteArray,
    ): ByteArray {
        val ipHeaderLength = MIN_HEADER_LENGTH
        val udpLength = 8 + payload.size
        val totalLength = ipHeaderLength + udpLength
        val out = ByteArray(totalLength)

        // IP header
        out[0] = ((IPV4_VERSION shl 4) or (ipHeaderLength / 4)).toByte()
        out[1] = 0
        out[2] = (totalLength ushr 8).toByte()
        out[3] = totalLength.toByte()
        out[4] = 0; out[5] = 0
        out[6] = 0x40.toByte(); out[7] = 0 // Don't fragment
        out[8] = 64 // TTL
        out[9] = PROTOCOL_UDP.toByte()
        // checksum bytes 10-11 left zero for now
        System.arraycopy(original.destIp, 0, out, 12, 4) // swapped
        System.arraycopy(original.sourceIp, 0, out, 16, 4)

        val ipChecksum = checksum(out, 0, ipHeaderLength)
        out[10] = (ipChecksum ushr 8).toByte()
        out[11] = ipChecksum.toByte()

        // UDP header
        val udpStart = ipHeaderLength
        out[udpStart] = (originalUdp.destPort ushr 8).toByte()
        out[udpStart + 1] = originalUdp.destPort.toByte()
        out[udpStart + 2] = (originalUdp.sourcePort ushr 8).toByte()
        out[udpStart + 3] = originalUdp.sourcePort.toByte()
        out[udpStart + 4] = (udpLength ushr 8).toByte()
        out[udpStart + 5] = udpLength.toByte()
        // UDP checksum bytes 6-7 left zero (legal for IPv4)

        System.arraycopy(payload, 0, out, udpStart + 8, payload.size)

        // Pseudo-header UDP checksum. We compute it because some Android
        // kernels drop UDP packets with checksum=0 in VPN scenarios.
        val udpChecksum = udpChecksum(out, udpStart, udpLength)
        out[udpStart + 6] = (udpChecksum ushr 8).toByte()
        out[udpStart + 7] = udpChecksum.toByte()
        return out
    }

    private fun readUShort(buffer: ByteBuffer, index: Int): Int {
        val hi = buffer.get(index).toInt() and 0xFF
        val lo = buffer.get(index + 1).toInt() and 0xFF
        return (hi shl 8) or lo
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun udpChecksum(packet: ByteArray, udpStart: Int, udpLength: Int): Int {
        // Pseudo-header: src ip (4) + dst ip (4) + zero (1) + proto (1) + udp length (2)
        val pseudo = ByteArray(12)
        System.arraycopy(packet, 12, pseudo, 0, 4)
        System.arraycopy(packet, 16, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = PROTOCOL_UDP.toByte()
        pseudo[10] = (udpLength ushr 8).toByte()
        pseudo[11] = udpLength.toByte()

        var sum = 0
        for (i in 0 until pseudo.size step 2) {
            val word = ((pseudo[i].toInt() and 0xFF) shl 8) or (pseudo[i + 1].toInt() and 0xFF)
            sum += word
        }
        var i = udpStart
        val end = udpStart + udpLength
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val result = sum.inv() and 0xFFFF
        // RFC 768: a zero result is transmitted as 0xFFFF to distinguish
        // it from "checksum not computed".
        return if (result == 0) 0xFFFF else result
    }
}

/** Parsed IPv4 header (we only carry the fields needed downstream). */
internal data class Ipv4Header(
    val headerStart: Int,
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val sourceIp: ByteArray,
    val destIp: ByteArray,
) {
    // Avoid generated equals/hashCode warnings about ByteArray.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

internal data class UdpHeader(
    val sourcePort: Int,
    val destPort: Int,
    val length: Int,
)

/** Convenience: ByteOrder of network packets is always big-endian. */
internal val NETWORK_ORDER: ByteOrder = ByteOrder.BIG_ENDIAN
