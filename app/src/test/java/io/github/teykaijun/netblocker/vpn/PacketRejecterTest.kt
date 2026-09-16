package io.github.teykaijun.netblocker.vpn

import java.net.InetAddress
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketRejecterTest {

    @Test
    fun checksumVerifierAcceptsKnownIpv4Header() {
        // Well-known sample header whose checksum field (0xb861) is correct.
        val header = hex("45 00 00 73 00 00 40 00 40 11 b8 61 c0 a8 00 01 c0 a8 00 c7")
        assertEquals(0xFFFF, onesComplementSum(header))
    }

    @Test
    fun ipv4TcpSynIsAnsweredWithResetAck() {
        val syn = ipv4(
            PROTO_TCP, APP_V4, SERVER_V4,
            tcp(srcPort = 40000, dstPort = 443, seq = 0x12345678, ack = 0, flags = SYN, options = ByteArray(12) { 1 }),
        )

        val reply = rejectInLargeBuffer(syn)

        assertEquals(40, reply.size)
        assertEquals(0x45, reply.u8(0))
        assertEquals(40, reply.u16(2))
        assertEquals(PROTO_TCP, reply.u8(9))
        assertArrayEquals(addr(SERVER_V4), reply.copyOfRange(12, 16))
        assertArrayEquals(addr(APP_V4), reply.copyOfRange(16, 20))
        assertEquals(443, reply.u16(20))
        assertEquals(40000, reply.u16(22))
        assertEquals(0L, reply.u32(24))
        assertEquals(0x12345679L, reply.u32(28)) // options are not payload, SYN counts as one
        assertEquals(0x50, reply.u8(32))
        assertEquals(RST or ACK, reply.u8(33))
        assertValidIpv4Header(reply)
        assertValidTransportChecksum(reply, ipv6 = false)
    }

    @Test
    fun resetAcknowledgesSynPayloadAndFinAndWrapsAround() {
        val syn = ipv4(
            PROTO_TCP, APP_V4, SERVER_V4,
            tcp(srcPort = 40001, dstPort = 80, seq = 0xFFFFFFF0L, ack = 0, flags = SYN or FIN, data = ByteArray(20)),
        )

        val reply = rejectInLargeBuffer(syn)

        // 0xFFFFFFF0 + 20 bytes of data + SYN + FIN wraps around to 6.
        assertEquals(6L, reply.u32(28))
        assertValidTransportChecksum(reply, ipv6 = false)
    }

    @Test
    fun segmentWithAckIsAnsweredWithPlainResetUsingItsAckNumber() {
        val segment = ipv4(
            PROTO_TCP, APP_V4, SERVER_V4,
            tcp(srcPort = 40002, dstPort = 443, seq = 1000, ack = 0xCAFEBABEL, flags = ACK or PSH, data = ByteArray(5)),
        )

        val reply = rejectInLargeBuffer(segment)

        assertEquals(0xCAFEBABEL, reply.u32(24))
        assertEquals(0L, reply.u32(28))
        assertEquals(RST, reply.u8(33))
        assertValidTransportChecksum(reply, ipv6 = false)
    }

    @Test
    fun resetsAreNeverAnswered() {
        for (flags in listOf(RST, RST or ACK)) {
            val packet = ipv4(PROTO_TCP, APP_V4, SERVER_V4, tcp(40003, 443, seq = 1, ack = 1, flags = flags))
            assertNull(PacketRejecter.reject(packet, packet.size))
        }
    }

    @Test
    fun ipv4UdpIsAnsweredWithAdministrativelyProhibited() {
        val query = ipv4(PROTO_UDP, APP_V4, DNS_V4, udp(srcPort = 5353, dstPort = 53, data = ByteArray(31) { it.toByte() }))

        val reply = rejectInLargeBuffer(query)

        assertEquals(20 + 8 + query.size, reply.size)
        assertEquals(PROTO_ICMP, reply.u8(9))
        assertArrayEquals(addr(DNS_V4), reply.copyOfRange(12, 16))
        assertArrayEquals(addr(APP_V4), reply.copyOfRange(16, 20))
        assertEquals(3, reply.u8(20)) // destination unreachable
        assertEquals(13, reply.u8(21)) // communication administratively prohibited
        assertArrayEquals(query, reply.copyOfRange(28, reply.size))
        assertValidIpv4Header(reply)
        assertEquals("ICMP checksum", 0xFFFF, onesComplementSum(reply.copyOfRange(20, reply.size)))
    }

    @Test
    fun ipv4IcmpErrorIsCappedAt576Bytes() {
        val datagram = ipv4(PROTO_UDP, APP_V4, SERVER_V4, udp(50000, 443, ByteArray(1400)))

        val reply = rejectInLargeBuffer(datagram)

        assertEquals(576, reply.size)
        assertArrayEquals(datagram.copyOfRange(0, 548), reply.copyOfRange(28, 576))
        assertEquals(0xFFFF, onesComplementSum(reply.copyOfRange(20, reply.size)))
    }

    @Test
    fun ipv6TcpSynIsAnsweredWithResetAck() {
        val syn = ipv6(PROTO_TCP, APP_V6, SERVER_V6, tcp(srcPort = 41000, dstPort = 443, seq = 7, ack = 0, flags = SYN))

        val reply = rejectInLargeBuffer(syn)

        assertEquals(60, reply.size)
        assertEquals(0x60, reply.u8(0))
        assertEquals(20, reply.u16(4))
        assertEquals(PROTO_TCP, reply.u8(6))
        assertEquals(64, reply.u8(7))
        assertArrayEquals(addr(SERVER_V6), reply.copyOfRange(8, 24))
        assertArrayEquals(addr(APP_V6), reply.copyOfRange(24, 40))
        assertEquals(443, reply.u16(40))
        assertEquals(41000, reply.u16(42))
        assertEquals(8L, reply.u32(48))
        assertEquals(RST or ACK, reply.u8(53))
        assertValidTransportChecksum(reply, ipv6 = true)
    }

    @Test
    fun ipv6UdpIsAnsweredWithAdministrativelyProhibited() {
        val query = ipv6(PROTO_UDP, APP_V6, DNS_V6, udp(srcPort = 5353, dstPort = 53, data = ByteArray(33) { 7 }))

        val reply = rejectInLargeBuffer(query)

        assertEquals(40 + 8 + query.size, reply.size)
        assertEquals(PROTO_ICMPV6, reply.u8(6))
        assertEquals(reply.size - 40, reply.u16(4))
        assertArrayEquals(addr(DNS_V6), reply.copyOfRange(8, 24))
        assertArrayEquals(addr(APP_V6), reply.copyOfRange(24, 40))
        assertEquals(1, reply.u8(40)) // destination unreachable
        assertEquals(1, reply.u8(41)) // administratively prohibited
        assertArrayEquals(query, reply.copyOfRange(48, reply.size))
        assertValidTransportChecksum(reply, ipv6 = true)
    }

    @Test
    fun ipv6IcmpErrorIsCappedAt1280Bytes() {
        val datagram = ipv6(PROTO_UDP, APP_V6, SERVER_V6, udp(50001, 443, ByteArray(1452)))

        val reply = rejectInLargeBuffer(datagram)

        assertEquals(1280, reply.size)
        assertValidTransportChecksum(reply, ipv6 = true)
    }

    @Test
    fun multicastAndBroadcastDestinationsAreDropped() {
        for (destination in listOf("224.0.0.251", "239.255.255.250", "255.255.255.255")) {
            val packet = ipv4(PROTO_UDP, APP_V4, destination, udp(5353, 5353, ByteArray(10)))
            assertNull(destination, PacketRejecter.reject(packet, packet.size))
        }
        val v6 = ipv6(PROTO_UDP, APP_V6, "ff02::fb", udp(5353, 5353, ByteArray(10)))
        assertNull(PacketRejecter.reject(v6, v6.size))
    }

    @Test
    fun nonFirstFragmentsAreDropped() {
        val fragment = ipv4(PROTO_UDP, APP_V4, SERVER_V4, ByteArray(64), fragment = 185)
        assertNull(PacketRejecter.reject(fragment, fragment.size))
    }

    @Test
    fun otherProtocolsAreDropped() {
        val ping = ipv4(PROTO_ICMP, APP_V4, SERVER_V4, hex("08 00 f7 ff 00 00 00 00"))
        assertNull(PacketRejecter.reject(ping, ping.size))
        val ping6 = ipv6(PROTO_ICMPV6, APP_V6, SERVER_V6, hex("80 00 00 00 00 00 00 00"))
        assertNull(PacketRejecter.reject(ping6, ping6.size))
        val hopByHop = ipv6(0, APP_V6, SERVER_V6, ByteArray(16))
        assertNull(PacketRejecter.reject(hopByHop, hopByHop.size))
    }

    @Test
    fun declaredLengthsBeyondTheReadBytesAreDropped() {
        val syn = ipv4(PROTO_TCP, APP_V4, SERVER_V4, tcp(40004, 443, seq = 1, ack = 0, flags = SYN))
        assertNull(PacketRejecter.reject(syn, syn.size - 1))
        assertNull(PacketRejecter.reject(syn, syn.size + 1))
        val syn6 = ipv6(PROTO_TCP, APP_V6, SERVER_V6, tcp(40005, 443, seq = 1, ack = 0, flags = SYN))
        assertNull(PacketRejecter.reject(syn6, syn6.size - 1))
    }

    @Test
    fun garbageInputNeverThrows() {
        val random = Random(1234)
        repeat(20_000) {
            val packet = ByteArray(random.nextInt(0, 120))
            random.nextBytes(packet)
            if (packet.isNotEmpty()) {
                val version = if (random.nextBoolean()) 0x40 else 0x60
                packet[0] = (version or (packet[0].toInt() and 0x0F)).toByte()
            }
            PacketRejecter.reject(packet, packet.size)
        }
    }

    private fun rejectInLargeBuffer(packet: ByteArray): ByteArray {
        // The service reads into a large reusable buffer, so trailing bytes must be ignored.
        val buffer = packet.copyOf(32_767)
        buffer.fill(0x5A, fromIndex = packet.size)
        return requireNotNull(PacketRejecter.reject(buffer, packet.size)) { "expected a reply" }
    }

    private fun assertValidIpv4Header(packet: ByteArray) {
        assertEquals("IPv4 header checksum", 0xFFFF, onesComplementSum(packet.copyOfRange(0, 20)))
    }

    private fun assertValidTransportChecksum(packet: ByteArray, ipv6: Boolean) {
        val headerLength = if (ipv6) 40 else 20
        val segment = packet.copyOfRange(headerLength, packet.size)
        val pseudoHeader = if (ipv6) {
            packet.copyOfRange(8, 40) + int32(segment.size) + byteArrayOf(0, 0, 0, packet[6])
        } else {
            packet.copyOfRange(12, 20) + byteArrayOf(0, packet[9]) + int16(segment.size)
        }
        assertEquals("transport checksum", 0xFFFF, onesComplementSum(pseudoHeader, segment))
    }

    private companion object {
        const val PROTO_ICMP = 1
        const val PROTO_TCP = 6
        const val PROTO_UDP = 17
        const val PROTO_ICMPV6 = 58

        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10

        const val APP_V4 = "10.111.222.1"
        const val DNS_V4 = "10.111.222.2"
        const val SERVER_V4 = "93.184.216.34"
        const val APP_V6 = "fdb4:1c3e:9a27::1"
        const val DNS_V6 = "fdb4:1c3e:9a27::2"
        const val SERVER_V6 = "2606:2800:220:1:248:1893:25c8:1946"

        fun addr(literal: String): ByteArray = InetAddress.getByName(literal).address

        fun hex(text: String): ByteArray = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()

        fun int16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

        fun int32(value: Int) = int16(value ushr 16) + int16(value and 0xFFFF)

        /** Independent reference implementation: folded one's complement sum of all parts. */
        fun onesComplementSum(vararg parts: ByteArray): Int {
            var sum = 0L
            for (part in parts) {
                for (i in part.indices step 2) {
                    val high = part[i].toInt() and 0xFF
                    val low = if (i + 1 < part.size) part[i + 1].toInt() and 0xFF else 0
                    sum += (high shl 8) or low
                }
            }
            while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum shr 16)
            return sum.toInt()
        }

        fun ipv4(protocol: Int, src: String, dst: String, payload: ByteArray, fragment: Int = 0x4000): ByteArray {
            val packet = ByteArray(20 + payload.size)
            packet[0] = 0x45
            int16(packet.size).copyInto(packet, 2)
            int16(fragment).copyInto(packet, 6)
            packet[8] = 64
            packet[9] = protocol.toByte()
            addr(src).copyInto(packet, 12)
            addr(dst).copyInto(packet, 16)
            payload.copyInto(packet, 20)
            return packet
        }

        fun ipv6(nextHeader: Int, src: String, dst: String, payload: ByteArray): ByteArray {
            val packet = ByteArray(40 + payload.size)
            packet[0] = 0x60
            int16(payload.size).copyInto(packet, 4)
            packet[6] = nextHeader.toByte()
            packet[7] = 64
            addr(src).copyInto(packet, 8)
            addr(dst).copyInto(packet, 24)
            payload.copyInto(packet, 40)
            return packet
        }

        fun tcp(
            srcPort: Int,
            dstPort: Int,
            seq: Long,
            ack: Long,
            flags: Int,
            options: ByteArray = ByteArray(0),
            data: ByteArray = ByteArray(0),
        ): ByteArray {
            require(options.size % 4 == 0)
            val headerLength = 20 + options.size
            val segment = ByteArray(headerLength + data.size)
            int16(srcPort).copyInto(segment, 0)
            int16(dstPort).copyInto(segment, 2)
            int32(seq.toInt()).copyInto(segment, 4)
            int32(ack.toInt()).copyInto(segment, 8)
            segment[12] = ((headerLength / 4) shl 4).toByte()
            segment[13] = flags.toByte()
            int16(65535).copyInto(segment, 14)
            options.copyInto(segment, 20)
            data.copyInto(segment, headerLength)
            return segment
        }

        fun udp(srcPort: Int, dstPort: Int, data: ByteArray): ByteArray {
            val datagram = ByteArray(8 + data.size)
            int16(srcPort).copyInto(datagram, 0)
            int16(dstPort).copyInto(datagram, 2)
            int16(datagram.size).copyInto(datagram, 4)
            data.copyInto(datagram, 8)
            return datagram
        }

        fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

        fun ByteArray.u16(index: Int) = (u8(index) shl 8) or u8(index + 1)

        fun ByteArray.u32(index: Int) = (u16(index).toLong() shl 16) or u16(index + 2).toLong()
    }
}
