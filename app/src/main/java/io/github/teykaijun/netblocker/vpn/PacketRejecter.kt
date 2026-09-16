package io.github.teykaijun.netblocker.vpn

/**
 * Builds immediate "connection refused" replies for packets that blocked apps send into the tunnel.
 *
 * Silently dropping those packets would already block the app, but it would then hang until its
 * network timeouts expire. Instead, TCP connection attempts are answered with a RST and UDP
 * datagrams (including DNS lookups) with an ICMP "administratively prohibited" error, so the
 * blocked app fails fast. Anything else - ICMP, IPv6 extension headers, fragments, multicast,
 * malformed input - yields `null` and is simply dropped.
 *
 * This object is pure byte manipulation with no Android dependencies, so it is unit tested on the JVM.
 */
internal object PacketRejecter {

    private const val PROTO_ICMP = 1
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val PROTO_ICMPV6 = 58

    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val TCP_HEADER = 20
    private const val UDP_HEADER = 8
    private const val ICMP_HEADER = 8

    private const val TCP_FIN = 0x01
    private const val TCP_SYN = 0x02
    private const val TCP_RST = 0x04
    private const val TCP_ACK = 0x10

    private const val ICMPV4_DEST_UNREACHABLE = 3
    private const val ICMPV4_ADMIN_PROHIBITED = 13
    private const val ICMPV6_DEST_UNREACHABLE = 1
    private const val ICMPV6_ADMIN_PROHIBITED = 1

    // Maximum ICMP error sizes from RFC 1812 (IPv4) and RFC 4443 (IPv6).
    private const val MAX_ICMPV4_REPLY = 576
    private const val MAX_ICMPV6_REPLY = 1280

    private const val HOP_LIMIT = 64

    /**
     * Returns the reply to write back into the tunnel for the first [length] bytes of [packet],
     * or `null` if the packet should just be dropped.
     */
    fun reject(packet: ByteArray, length: Int): ByteArray? {
        if (length < 1 || length > packet.size) return null
        return when ((packet[0].toInt() ushr 4) and 0x0F) {
            4 -> rejectIpv4(packet, length)
            6 -> rejectIpv6(packet, length)
            else -> null
        }
    }

    private fun rejectIpv4(p: ByteArray, length: Int): ByteArray? {
        if (length < IPV4_HEADER) return null
        val headerLength = (p[0].toInt() and 0x0F) * 4
        val packetLength = p.u16(2)
        if (headerLength < IPV4_HEADER || packetLength < headerLength || packetLength > length) return null
        // Only the first fragment carries the transport header.
        if ((p.u16(6) and 0x1FFF) != 0) return null
        // Never answer multicast, broadcast or reserved destinations (224.0.0.0 and above).
        if (p.u8(16) >= 224) return null

        return when (p.u8(9)) {
            PROTO_TCP -> tcpReset(p, headerLength, packetLength, ipv6 = false)
            PROTO_UDP ->
                if (packetLength >= headerLength + UDP_HEADER) icmpv4Prohibited(p, packetLength) else null
            else -> null
        }
    }

    private fun rejectIpv6(p: ByteArray, length: Int): ByteArray? {
        if (length < IPV6_HEADER) return null
        val packetLength = IPV6_HEADER + p.u16(4)
        if (packetLength > length) return null
        // Never answer multicast destinations (ff00::/8).
        if (p.u8(24) == 0xFF) return null

        return when (p.u8(6)) {
            PROTO_TCP -> tcpReset(p, IPV6_HEADER, packetLength, ipv6 = true)
            PROTO_UDP ->
                if (packetLength >= IPV6_HEADER + UDP_HEADER) icmpv6Prohibited(p, packetLength) else null
            else -> null
        }
    }

    /** Builds a TCP reset following RFC 9293, section 3.10.7.1. */
    private fun tcpReset(p: ByteArray, tcpOffset: Int, packetLength: Int, ipv6: Boolean): ByteArray? {
        if (packetLength < tcpOffset + TCP_HEADER) return null
        val dataOffset = ((p.u8(tcpOffset + 12) ushr 4) and 0x0F) * 4
        if (dataOffset < TCP_HEADER || tcpOffset + dataOffset > packetLength) return null
        val flags = p.u8(tcpOffset + 13)
        if ((flags and TCP_RST) != 0) return null // never answer a reset

        val replySeq: Long
        val replyAck: Long
        val replyFlags: Int
        if ((flags and TCP_ACK) != 0) {
            // <SEQ=SEG.ACK><CTL=RST>
            replySeq = p.u32(tcpOffset + 8)
            replyAck = 0
            replyFlags = TCP_RST
        } else {
            // <SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>, where SYN and FIN each count as one.
            var segmentLength = (packetLength - tcpOffset - dataOffset).toLong()
            if ((flags and TCP_SYN) != 0) segmentLength++
            if ((flags and TCP_FIN) != 0) segmentLength++
            replySeq = 0
            replyAck = (p.u32(tcpOffset + 4) + segmentLength) and 0xFFFFFFFFL
            replyFlags = TCP_RST or TCP_ACK
        }

        val ipHeader = if (ipv6) IPV6_HEADER else IPV4_HEADER
        val reply = ByteArray(ipHeader + TCP_HEADER)
        writeIpHeader(reply, p, PROTO_TCP, ipv6)
        val t = ipHeader
        reply.put16(t, p.u16(tcpOffset + 2)) // source port = original destination port
        reply.put16(t + 2, p.u16(tcpOffset)) // destination port = original source port
        reply.put32(t + 4, replySeq)
        reply.put32(t + 8, replyAck)
        reply[t + 12] = ((TCP_HEADER / 4) shl 4).toByte() // data offset, no options
        reply[t + 13] = replyFlags.toByte()
        // Window, checksum and urgent pointer start as zero.
        reply.put16(t + 16, transportChecksum(reply, ipv6, PROTO_TCP, t))
        return reply
    }

    private fun icmpv4Prohibited(p: ByteArray, packetLength: Int): ByteArray {
        val quoted = minOf(packetLength, MAX_ICMPV4_REPLY - IPV4_HEADER - ICMP_HEADER)
        val reply = ByteArray(IPV4_HEADER + ICMP_HEADER + quoted)
        writeIpHeader(reply, p, PROTO_ICMP, ipv6 = false)
        val i = IPV4_HEADER
        reply[i] = ICMPV4_DEST_UNREACHABLE.toByte()
        reply[i + 1] = ICMPV4_ADMIN_PROHIBITED.toByte()
        p.copyInto(reply, destinationOffset = i + ICMP_HEADER, startIndex = 0, endIndex = quoted)
        reply.put16(i + 2, checksum(sum(reply, i, reply.size - i)))
        return reply
    }

    private fun icmpv6Prohibited(p: ByteArray, packetLength: Int): ByteArray {
        val quoted = minOf(packetLength, MAX_ICMPV6_REPLY - IPV6_HEADER - ICMP_HEADER)
        val reply = ByteArray(IPV6_HEADER + ICMP_HEADER + quoted)
        writeIpHeader(reply, p, PROTO_ICMPV6, ipv6 = true)
        val i = IPV6_HEADER
        reply[i] = ICMPV6_DEST_UNREACHABLE.toByte()
        reply[i + 1] = ICMPV6_ADMIN_PROHIBITED.toByte()
        p.copyInto(reply, destinationOffset = i + ICMP_HEADER, startIndex = 0, endIndex = quoted)
        reply.put16(i + 2, transportChecksum(reply, ipv6 = true, PROTO_ICMPV6, i))
        return reply
    }

    /** Writes the IP header of [reply], addressed from the original destination back to its sender. */
    private fun writeIpHeader(reply: ByteArray, original: ByteArray, protocol: Int, ipv6: Boolean) {
        if (ipv6) {
            reply[0] = 0x60 // version 6, no traffic class or flow label
            reply.put16(4, reply.size - IPV6_HEADER)
            reply[6] = protocol.toByte()
            reply[7] = HOP_LIMIT.toByte()
            original.copyInto(reply, destinationOffset = 8, startIndex = 24, endIndex = 40)
            original.copyInto(reply, destinationOffset = 24, startIndex = 8, endIndex = 24)
        } else {
            reply[0] = 0x45 // version 4, 20-byte header
            reply.put16(2, reply.size)
            reply.put16(6, 0x4000) // don't fragment
            reply[8] = HOP_LIMIT.toByte()
            reply[9] = protocol.toByte()
            original.copyInto(reply, destinationOffset = 12, startIndex = 16, endIndex = 20)
            original.copyInto(reply, destinationOffset = 16, startIndex = 12, endIndex = 16)
            reply.put16(10, checksum(sum(reply, 0, IPV4_HEADER)))
        }
    }

    /** Checksum of the transport segment starting at [offset], including the IPv4/IPv6 pseudo-header. */
    private fun transportChecksum(packet: ByteArray, ipv6: Boolean, protocol: Int, offset: Int): Int {
        val length = packet.size - offset
        val pseudoHeader = if (ipv6) {
            sum(packet, 8, 32) + (length ushr 16) + (length and 0xFFFF) + protocol
        } else {
            sum(packet, 12, 8) + length + protocol
        }
        return checksum(pseudoHeader + sum(packet, offset, length))
    }

    /** Sums 16-bit big-endian words, padding an odd trailing byte with zero. */
    private fun sum(data: ByteArray, offset: Int, length: Int): Long {
        var total = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            total += data.u16(i)
            i += 2
        }
        if (i < end) total += data.u8(i) shl 8
        return total
    }

    /** Folds a word sum into a 16-bit one's complement checksum. */
    private fun checksum(sum: Long): Int {
        var folded = sum
        while ((folded ushr 16) != 0L) folded = (folded and 0xFFFF) + (folded ushr 16)
        return folded.inv().toInt() and 0xFFFF
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    private fun ByteArray.u32(index: Int): Long = (u16(index).toLong() shl 16) or u16(index + 2).toLong()

    private fun ByteArray.put16(index: Int, value: Int) {
        this[index] = (value ushr 8).toByte()
        this[index + 1] = value.toByte()
    }

    private fun ByteArray.put32(index: Int, value: Long) {
        put16(index, (value ushr 16).toInt() and 0xFFFF)
        put16(index + 2, value.toInt() and 0xFFFF)
    }
}
