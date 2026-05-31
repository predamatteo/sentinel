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

    @Test
    fun rewriteTransactionIdReplacesOnlyFirstTwoBytes() {
        val original = byteArrayOf(0x12, 0x34, 0x05, 0x06, 0x07, 0x08)
        val rewritten = DnsPacketParser.rewriteTransactionId(original, 0xABCD)
        // First two bytes are the new id, big-endian.
        assertEquals(0xAB.toByte(), rewritten[0])
        assertEquals(0xCD.toByte(), rewritten[1])
        // Everything else is untouched.
        assertEquals(0x05.toByte(), rewritten[2])
        assertEquals(0x06.toByte(), rewritten[3])
        assertEquals(0x07.toByte(), rewritten[4])
        assertEquals(0x08.toByte(), rewritten[5])
        // Original is not mutated (template stays reusable).
        assertEquals(0x12.toByte(), original[0])
        assertEquals(0x34.toByte(), original[1])
    }

    @Test
    fun parseAnswerExtractsMinTtlAcrossRecords() {
        // 1 question (a.com A IN) + 2 answer records (compressed names),
        // TTLs 300 and 60. Min TTL must be 60, rcode 0, not truncated.
        val payload = byteArrayOf(
            0xAA.toByte(), 0xBB.toByte(), // txid
            0x81.toByte(), 0x80.toByte(), // QR=1 RD=1 RA=1 rcode=0
            0x00, 0x01,                   // qd=1
            0x00, 0x02,                   // an=2
            0x00, 0x00,                   // ns=0
            0x00, 0x00,                   // ar=0
            // question name "a.com" @offset 12
            0x01, 'a'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00,
            0x00, 0x01,                   // qtype A
            0x00, 0x01,                   // qclass IN
            // answer 1: name ptr -> 12, A IN, ttl=300, rdlen=4
            0xC0.toByte(), 0x0C,
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x01, 0x2C,       // ttl 300
            0x00, 0x04,
            0x01, 0x02, 0x03, 0x04,
            // answer 2: name ptr -> 12, A IN, ttl=60, rdlen=4
            0xC0.toByte(), 0x0C,
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,       // ttl 60
            0x00, 0x04,
            0x05, 0x06, 0x07, 0x08,
        )
        val info = DnsPacketParser.parseAnswerTtlAndRcode(payload)
        assertNotNull(info)
        assertEquals(0, info!!.rcode)
        assertEquals(60L, info.minTtlSeconds)
        assertEquals(false, info.truncated)
    }

    @Test
    fun parseAnswerReportsNxdomainWithNoAnswers() {
        val payload = byteArrayOf(
            0x00, 0x01,
            0x81.toByte(), 0x83.toByte(), // QR=1 RD=1 RA=1 rcode=3 (NXDOMAIN)
            0x00, 0x01,                   // qd=1
            0x00, 0x00,                   // an=0
            0x00, 0x00,
            0x00, 0x00,
            0x01, 'a'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00,
            0x00, 0x01,
            0x00, 0x01,
        )
        val info = DnsPacketParser.parseAnswerTtlAndRcode(payload)
        assertNotNull(info)
        assertEquals(3, info!!.rcode)
        assertEquals(0L, info.minTtlSeconds)
    }

    @Test
    fun parseAnswerDetectsTruncationBit() {
        val payload = byteArrayOf(
            0x00, 0x01,
            0x82.toByte(), 0x80.toByte(), // QR=1 TC=1 rcode=0
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x01, 'a'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00,
            0x00, 0x01,
            0x00, 0x01,
        )
        val info = DnsPacketParser.parseAnswerTtlAndRcode(payload)
        assertNotNull(info)
        assertEquals(true, info!!.truncated)
    }

    @Test
    fun rejectsForwardCompressionPointer() {
        // Question name begins with a pointer to offset 32, which is at/after
        // the pointer's own position (not strictly backward) -> reject.
        val payload = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xC0.toByte(), 0x20,
        )
        assertNull(DnsPacketParser.parseQuery(payload))
    }

    @Test
    fun rejectsCompressionPointerIntoHeader() {
        // Pointer targets offset 6, inside the 12-byte fixed header -> reject.
        val payload = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xC0.toByte(), 0x06,
        )
        assertNull(DnsPacketParser.parseQuery(payload))
    }

    @Test
    fun exposesQdCountForMultiQuestion() {
        // qd=2: only the first question is parsed, but qdCount is exposed so
        // the service can route multi-question queries upstream.
        val payload = byteArrayOf(
            0x12, 0x34, 0x01, 0x00,
            0x00, 0x02, // qd = 2
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // q1: a.com A IN
            0x01, 'a'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x01, 0x00, 0x01,
            // q2: b.com A IN
            0x01, 'b'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x01, 0x00, 0x01,
        )
        val query = DnsPacketParser.parseQuery(payload)
        assertNotNull(query)
        assertEquals(2, query!!.qdCount)
        assertEquals("a.com", query.qName)
    }
}
