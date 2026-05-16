package com.sentinel.app.vpn

import java.nio.ByteBuffer

/**
 * Hand-rolled, minimal DNS wire-format parser and synthesiser.
 *
 * Only the subset needed by Sentinel is implemented:
 *  - parsing the first question record of a query
 *  - parsing label compression (RFC 1035 4.1.4) in question names
 *  - building an NXDOMAIN response that echoes the original question
 *
 * We deliberately ignore other sections (Answer, Authority, Additional)
 * on queries since standard clients put nothing useful there for our
 * purposes. EDNS0 OPT records in the Additional section are tolerated:
 * we copy the original payload verbatim into the response so the EDNS0
 * advertisement is preserved.
 */
internal object DnsPacketParser {

    private const val HEADER_LENGTH = 12
    private const val FLAG_RESPONSE = 0x8000
    private const val FLAG_AA = 0x0400
    private const val FLAG_RCODE_NXDOMAIN = 0x0003

    /**
     * Parse a DNS query from [payload]. Returns null if it is not a
     * standard query, has no questions, or the wire format is malformed.
     *
     * The returned [DnsQuery] holds the questioned name as a lower-case
     * dotted string ("example.com", no trailing dot) and the raw bytes of
     * the original payload so callers can echo it back when synthesising
     * a response.
     */
    fun parseQuery(payload: ByteArray): DnsQuery? {
        if (payload.size < HEADER_LENGTH) return null
        val buf = ByteBuffer.wrap(payload).order(NETWORK_ORDER)
        val transactionId = buf.short.toInt() and 0xFFFF
        val flags = buf.short.toInt() and 0xFFFF
        val isQuery = (flags and FLAG_RESPONSE) == 0
        if (!isQuery) return null
        val qdCount = buf.short.toInt() and 0xFFFF
        if (qdCount < 1) return null
        // skip an, ns, ar counts
        buf.short; buf.short; buf.short

        val nameBuilder = StringBuilder()
        val nameEnd = readName(payload, HEADER_LENGTH, nameBuilder) ?: return null
        if (nameEnd + 4 > payload.size) return null
        val type = ((payload[nameEnd].toInt() and 0xFF) shl 8) or
            (payload[nameEnd + 1].toInt() and 0xFF)
        val cls = ((payload[nameEnd + 2].toInt() and 0xFF) shl 8) or
            (payload[nameEnd + 3].toInt() and 0xFF)
        val questionEnd = nameEnd + 4

        return DnsQuery(
            transactionId = transactionId,
            flags = flags,
            qName = nameBuilder.toString().lowercase().trimEnd('.'),
            qType = type,
            qClass = cls,
            questionEnd = questionEnd,
            raw = payload,
        )
    }

    /**
     * Build an NXDOMAIN response for [query]. The response copies the
     * full question section verbatim (so the questioned name survives
     * untouched, label compression included) and sets QR=1, AA=1,
     * RCODE=NXDOMAIN, with answer/authority/additional counts at zero.
     *
     * Returned bytes are ready to be wrapped in a UDP datagram and shipped
     * back to the client.
     */
    fun buildNxdomainResponse(query: DnsQuery): ByteArray {
        val out = ByteArray(query.questionEnd)
        // Transaction id (echo)
        out[0] = (query.transactionId ushr 8).toByte()
        out[1] = query.transactionId.toByte()
        // Flags: response | AA | RCODE=NXDOMAIN. Preserve client's RD bit
        // so libcs that test for the bit do not get confused.
        val rdBit = query.flags and 0x0100
        val responseFlags = FLAG_RESPONSE or FLAG_AA or rdBit or FLAG_RCODE_NXDOMAIN
        out[2] = (responseFlags ushr 8).toByte()
        out[3] = responseFlags.toByte()
        // QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out[4] = 0; out[5] = 1
        out[6] = 0; out[7] = 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        // Question section: copy from original (already validated by parseQuery)
        System.arraycopy(query.raw, HEADER_LENGTH, out, HEADER_LENGTH, query.questionEnd - HEADER_LENGTH)
        return out
    }

    /**
     * Read a domain name encoded as a sequence of labels starting at
     * [offset] in [data]. Supports label compression by pointer.
     *
     * @return the offset immediately after the encoded name (NOT after a
     * pointer if one was followed), or null on malformed input.
     */
    private fun readName(data: ByteArray, offset: Int, out: StringBuilder): Int? {
        var i = offset
        var jumped = false
        var firstReturn = -1
        var hops = 0
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) {
                i += 1
                if (!jumped) firstReturn = i
                return firstReturn.takeIf { it >= 0 } ?: i
            }
            if ((len and 0xC0) == 0xC0) {
                if (i + 1 >= data.size) return null
                val pointer = ((len and 0x3F) shl 8) or (data[i + 1].toInt() and 0xFF)
                if (!jumped) firstReturn = i + 2
                i = pointer
                jumped = true
                hops += 1
                if (hops > 8) return null // defensive: avoid pointer loops
                continue
            }
            if ((len and 0xC0) != 0) return null // reserved bits set
            if (i + 1 + len > data.size) return null
            if (out.isNotEmpty()) out.append('.')
            out.append(String(data, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return null
    }
}

/**
 * A parsed DNS query. [raw] holds the original payload so the responder
 * can preserve label compression and any EDNS0 advertisements when
 * synthesising a reply.
 */
internal data class DnsQuery(
    val transactionId: Int,
    val flags: Int,
    val qName: String,
    val qType: Int,
    val qClass: Int,
    val questionEnd: Int,
    val raw: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
