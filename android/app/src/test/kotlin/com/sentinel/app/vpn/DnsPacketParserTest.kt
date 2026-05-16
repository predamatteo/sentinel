package com.sentinel.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM unit tests for the hand-rolled DNS parser. Each test builds a
 * tiny DNS wire-format buffer rather than reading captured bytes so the
 * expected output is unambiguous.
 */
class DnsPacketParserTest {

    @Test
    fun parsesSingleQuestion() {
        // Header: txid=0x1234, flags=0x0100 (standard query, recursion
        // desired), qd=1, an=0, ns=0, ar=0
        // Question: "example.com" type A class IN
        val payload = byteArrayOf(
            0x12, 0x34,
            0x01, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            // example
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            // com
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            // root
            0,
            // type A = 0x0001
            0x00, 0x01,
            // class IN = 0x0001
            0x00, 0x01,
        )
        val query = DnsPacketParser.parseQuery(payload)
        assertNotNull(query)
        assertEquals(0x1234, query!!.transactionId)
        assertEquals("example.com", query.qName)
        assertEquals(1, query.qType)
        assertEquals(1, query.qClass)
    }

    @Test
    fun rejectsResponseFlag() {
        val payload = byteArrayOf(
            0x12, 0x34,
            // QR=1 (response)
            0x80.toByte(), 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
        )
        assertNull(DnsPacketParser.parseQuery(payload))
    }

    @Test
    fun rejectsTruncated() {
        assertNull(DnsPacketParser.parseQuery(ByteArray(5)))
    }

    @Test
    fun buildsNxdomainResponse() {
        val payload = byteArrayOf(
            0x55, 0x66,
            0x01, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            3, 'a'.code.toByte(), 'd'.code.toByte(), 's'.code.toByte(),
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(),
            'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0,
            0x00, 0x01,
            0x00, 0x01,
        )
        val query = DnsPacketParser.parseQuery(payload)
        assertNotNull(query)
        val response = DnsPacketParser.buildNxdomainResponse(query!!)
        // Transaction id is echoed.
        assertEquals(0x55.toByte(), response[0])
        assertEquals(0x66.toByte(), response[1])
        // Response flags = QR(0x8000) | AA(0x0400) | RD echo(0x0100) |
        // RCODE NXDOMAIN(0x0003) = 0x8503.
        assertEquals(0x85.toByte(), response[2])
        assertEquals(0x03.toByte(), response[3])
        // ANCOUNT/NSCOUNT/ARCOUNT all zero, QDCOUNT=1
        assertEquals(0.toByte(), response[4])
        assertEquals(1.toByte(), response[5])
        assertEquals(0.toByte(), response[6])
        assertEquals(0.toByte(), response[7])
        // Question echoed: response length must match the original
        // question end (header 12 + qname 17 + 4 type/class = 33).
        assertEquals(payload.size, response.size)
    }
}
