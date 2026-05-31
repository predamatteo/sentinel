package com.sentinel.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Pure-JVM unit tests for the IPv6 parse/build path. The checksum test
 * verifies correctness independently: a receiver folding the one's-
 * complement sum over the IPv6 pseudo-header + UDP datagram (checksum
 * field included) must get 0xFFFF when the checksum is right.
 */
class IpPacketParserIpv6Test {

    private val clientIp = byteArrayOf(
        0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
    )
    private val sinkholeIp = byteArrayOf(
        0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02,
    )

    private fun ipv6UdpDnsPacket(): ByteArray {
        // 40-byte IPv6 header + 8-byte UDP header + 4-byte payload.
        val out = ByteArray(40 + 8 + 4)
        out[0] = 0x60 // version 6
        out[4] = 0x00; out[5] = 0x0C // payload length = 12
        out[6] = 17 // next header = UDP
        out[7] = 64 // hop limit
        System.arraycopy(clientIp, 0, out, 8, 16)
        System.arraycopy(sinkholeIp, 0, out, 24, 16)
        // UDP: src 0x1234 -> dst 53, length 12
        out[40] = 0x12; out[41] = 0x34
        out[42] = 0x00; out[43] = 0x35
        out[44] = 0x00; out[45] = 0x0C
        // checksum 46-47 left zero (request side, not validated here)
        out[48] = 0xAA.toByte(); out[49] = 0xBB.toByte()
        out[50] = 0xCC.toByte(); out[51] = 0xDD.toByte()
        return out
    }

    @Test
    fun parsesIpv6UdpHeader() {
        val buffer = ByteBuffer.wrap(ipv6UdpDnsPacket())
        val ip = IpPacketParser.parseIpv6(buffer)
        requireNotNull(ip)
        assertEquals(0, ip.headerStart)
        assertEquals(12, ip.payloadLength)
        assertEquals(IpPacketParser.PROTOCOL_UDP, ip.nextHeader)
        assertEquals(clientIp.toList(), ip.sourceIp.toList())
        assertEquals(sinkholeIp.toList(), ip.destIp.toList())
        // Position left at the start of the UDP header.
        assertEquals(40, buffer.position())
    }

    @Test
    fun rejectsNonIpv6Packet() {
        val ipv4ish = ByteArray(40)
        ipv4ish[0] = 0x45 // version 4
        assertNull(IpPacketParser.parseIpv6(ByteBuffer.wrap(ipv4ish)))
    }

    @Test
    fun buildsIpv6ReplyWithSwappedAddressesAndValidChecksum() {
        val ip = Ipv6Header(
            headerStart = 0,
            payloadLength = 12,
            nextHeader = 17,
            sourceIp = clientIp,
            destIp = sinkholeIp,
        )
        val udp = UdpHeader(sourcePort = 0x1234, destPort = 53, length = 12)
        val answer = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

        val reply = IpPacketParser.buildIpv6UdpReply(ip, udp, answer)

        assertEquals(40 + 8 + 4, reply.size)
        assertEquals(0x60.toByte(), reply[0])
        assertEquals(17.toByte(), reply[6])
        // payload length = UDP length = 12
        assertEquals(0x00.toByte(), reply[4])
        assertEquals(0x0C.toByte(), reply[5])
        // Reply source = original destination (sinkhole); dest = client.
        assertEquals(sinkholeIp.toList(), reply.copyOfRange(8, 24).toList())
        assertEquals(clientIp.toList(), reply.copyOfRange(24, 40).toList())
        // Ports swapped: src 53, dst 0x1234.
        assertEquals(0x00.toByte(), reply[40]); assertEquals(0x35.toByte(), reply[41])
        assertEquals(0x12.toByte(), reply[42]); assertEquals(0x34.toByte(), reply[43])

        // Checksum is mandatory and correct: receiver fold must be 0xFFFF.
        assertEquals(0xFFFF, receiverFold(reply, udpStart = 40, udpLength = 12))
        // ...and never transmitted as zero on IPv6.
        val checksum = ((reply[46].toInt() and 0xFF) shl 8) or (reply[47].toInt() and 0xFF)
        assertNotEquals(0, checksum)
    }

    /**
     * Independent reimplementation of the IPv6 UDP checksum *verification*
     * (sum includes the checksum field; result 0xFFFF means valid).
     */
    private fun receiverFold(packet: ByteArray, udpStart: Int, udpLength: Int): Int {
        var sum = 0L
        var i = 8
        while (i < 40) {
            sum += (((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        sum += (udpLength ushr 16).toLong() and 0xFFFF
        sum += udpLength.toLong() and 0xFFFF
        sum += 17L // next header
        var j = udpStart
        val end = udpStart + udpLength
        while (j + 1 < end) {
            sum += (((packet[j].toInt() and 0xFF) shl 8) or (packet[j + 1].toInt() and 0xFF)).toLong()
            j += 2
        }
        if (j < end) {
            sum += ((packet[j].toInt() and 0xFF) shl 8).toLong()
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum and 0xFFFF).toInt()
    }
}
