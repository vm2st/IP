package com.vm2st.ip

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class UrlPolicyTest {
    @Test fun acceptsHttpsPublicHostsAndLiterals() {
        for (value in listOf("https://api64.ipify.org", "HTTPS://EXAMPLE.COM/ip?format=text", "https://8.8.8.8:8443/ip", "https://[2001:4860:4860::8888]/ip", "https://пример.рф/ip")) {
            assertTrue(UrlPolicy.validate(value).isHttps)
        }
        IpSources.all.mapNotNull { it.url }.forEach { UrlPolicy.validate(it) }
    }
    @Test fun rejectsCredentialsOtherSchemesControlsAndFragments() {
        for (value in listOf("http://example.com", "file:///etc/passwd", "ftp://example.com/ip", "https:example.com", "//example.com/ip",
            "https://u:p@example.com", "https://u@example.com", "https://@example.com", "https://example.com#x", "https://example.com/#",
            "https://example.com/\r\nx", "https://example.com/a b", "https://example.com\\@8.8.8.8", "https://example.com/"+"a".repeat(2049), "https://example.com/"+"я".repeat(600))) {
            assertEquals(value, LookupError.INVALID_URL, assertThrows(LookupFailure::class.java) { UrlPolicy.validate(value) }.error)
        }
    }
    @Test fun rejectsPrivateHostsAndLegacyIpv4Notation() {
        for (host in listOf("localhost", "printer.local", "x.localhost", "x.internal", "home.arpa", "router.home.arpa", "127.0.0.1", "10.0.0.1",
            "169.254.169.254", "192.168.1.1", "[::1]", "[::ffff:127.0.0.1]", "[fd00::1]", "192.0.2.1", "2130706433", "0177.0.0.1", "0x7f.0.0.1", "127.1", "1234.56")) {
            assertThrows(host, LookupFailure::class.java) { UrlPolicy.validate("https://$host/ip") }
        }
    }
    @Test fun dnsRejectsEmptyMixedAndPrivateAnswers() {
        fun dns(vararg ips: String) = PublicOnlyDns(object : Dns { override fun lookup(hostname: String) = ips.map { InetAddress.getByName(it) } })
        assertEquals(2, dns("8.8.8.8", "2606:4700:4700::1111").lookup("example.com").size)
        assertThrows(UnknownHostException::class.java) { dns().lookup("example.com") }
        assertThrows(UnknownHostException::class.java) { dns("8.8.8.8", "127.0.0.1").lookup("example.com") }
        assertThrows(UnknownHostException::class.java) { dns("fd00::1").lookup("example.com") }
        assertEquals(1, dns("64:ff9b::808:808").lookup("example.com").size)
    }
    @Test fun redirectTargetsAreValidatedOnEveryHop() {
        val base = "https://example.com/old/ip".toHttpUrl()
        assertEquals("https://example.com/new", SafeHttpTransport.redirect(base, "../new").toString())
        for (location in listOf("http://example.com/ip", "https://127.0.0.1/ip", "https://u:p@example.com", "//localhost/x", "", "a\nb", "x".repeat(2050))) {
            assertThrows(location, LookupFailure::class.java) { SafeHttpTransport.redirect(base, location) }
        }
    }
}
