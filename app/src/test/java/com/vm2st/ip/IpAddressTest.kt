package com.vm2st.ip

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class IpAddressTest {
    @Test fun ipv4IsStrictAndWhitespaceIsNormalized() {
        assertEquals("8.8.8.8", IpAddress.parse("\uFEFF 8.8.8.8\r\n")?.value)
        for (value in listOf("0.0.0.0", "255.255.255.255", "1.2.3.4", "99.100.199.200")) {
            assertEquals(value, IpAddress.parse(value)?.value)
        }
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.0.0.1", "01.2.3.4", "+1.2.3.4", "1.2.-1.4",
            "0x7f.0.0.1", "2130706433", "1.2.3.4:80", "[8.8.8.8]", "8.8.8.8\n1.1.1.1", "example.com", "<html>error</html>", "٨.٨.٨.٨")) {
            assertNull("Accepted: $bad", IpAddress.parse(bad))
        }
    }
    @Test fun ipv6CanonicalizesCompressionCaseAndMappedIpv4() {
        val values = mapOf(
            "2001:4860:4860:0:0:0:0:8888" to "2001:4860:4860::8888",
            "2001:0DB8:0:0:1:0:0:1" to "2001:db8::1:0:0:1",
            "0:0:0:0:0:0:0:0" to "::", "0:0:0:0:0:0:0:1" to "::1",
            "1:0:0:2:0:0:3:4" to "1::2:0:0:3:4",
            "1:2:3:4:5:6:7:0" to "1:2:3:4:5:6:7:0",
            "::FFFF:8.8.8.8" to "8.8.8.8", "::ffff:0808:0808" to "8.8.8.8",
            "2001:4860::1.2.3.4" to "2001:4860::102:304", "1::" to "1::"
        )
        for ((input, expected) in values) assertEquals(input, expected, IpAddress.parse(input)?.value)
        assertEquals(IpAddress.parse("8.8.8.8"), IpAddress.parse("::ffff:8.8.8.8"))
    }
    @Test fun malformedIpv6AndZonesAreRejectedWithoutDns() {
        for (value in listOf(":", ":::1", "1:::2", "1::2::3", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7:8::", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "::12345", "::gggg", "[::1]",
            "fe80::1%wlan0", "::1%25eth0", "::8.8.8.256", "1.2.3.4::", "::1.2.3.4:5", "1:2:3:4:5:1.2.3.4", "::1\u202e")) {
            assertNull(value, IpAddress.parse(value))
        }
    }
    @Test fun nonPublicIpv4IsNotAcceptedAsThePublicAddress() {
        for (value in listOf("0.1.2.3", "10.2.3.4", "100.64.0.1", "100.127.255.254", "127.0.0.1", "169.254.1.2",
            "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.0.0.7", "192.0.2.4", "192.88.99.1",
            "198.18.0.1", "198.19.255.254", "198.51.100.4", "203.0.113.4", "224.0.0.1", "255.255.255.255")) {
            assertNotNull(value, IpAddress.parse(value))
            assertNull(value, IpAddress.parsePublic(value))
        }
        for (value in listOf("8.8.8.8", "1.1.1.1", "100.63.255.254", "100.128.0.1", "172.32.0.1")) assertNotNull(value, IpAddress.parsePublic(value))
    }
    @Test fun nonPublicIpv6AndTransitionRangesAreRejected() {
        for (value in listOf("::", "::1", "::ffff:127.0.0.1", "fe80::1", "fd00::1", "ff02::1", "2001:db8::1",
            "2001::1", "2001:1ff::1", "2002:0808:0808::1", "3fff:fff::1", "64:ff9b::808:808")) {
            assertNull(value, IpAddress.parsePublic(value))
        }
        for (value in listOf("2001:4860:4860::8888", "2606:4700:4700::1111", "2a00:1450::1", "3fff:1000::1")) {
            assertNotNull(value, IpAddress.parsePublic(value))
        }
    }
    @Test fun nat64IsAllowedOnlyForDnsToPublicIpv4() {
        assertTrue(IpAddress.isPublicBytes(InetAddress.getByName("64:ff9b::808:808").address, true))
        assertFalse(IpAddress.isPublicBytes(InetAddress.getByName("64:ff9b::7f00:1").address, true))
        assertFalse(IpAddress.isPublicBytes(InetAddress.getByName("64:ff9b:1::808:808").address, true))
        assertFalse(IpAddress.isPublicBytes(byteArrayOf(8, 8)))
    }
    @Test fun canonicalRoundTripsNumericSamples() {
        val random = java.util.Random(912)
        repeat(300) {
            val bytes = ByteArray(16).also(random::nextBytes)
            val numeric = InetAddress.getByAddress(bytes).hostAddress!!.substringBefore('%')
            val parsed = IpAddress.parse(numeric)
            assertNotNull(numeric, parsed)
            assertEquals(parsed, IpAddress.parse(parsed!!.value))
        }
    }
}
