package com.vm2st.ip

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IpRepositoryTest {
    private val ip = IpAddress.parsePublic("8.8.8.8")!!
    @Test fun ipBodyMustBeStrictPublicLiteral() = runTest {
        for (body in listOf("hello", "<html>Forbidden</html>", "127.0.0.1", "8.8.8.8\n1.1.1.1", "{\"ip\":\"8.8.8.8\"}")) {
            val result = runCatching { IpRepository(HttpFetcher { _, max -> assertEquals(256, max); body }).lookupIp("https://example.com") }
            assertEquals(body, LookupError.INVALID_IP, (result.exceptionOrNull() as LookupFailure).error)
        }
        assertEquals(ip, IpRepository(HttpFetcher { _, _ -> "\n8.8.8.8\n" }).lookupIp("https://example.com"))
    }
    @Test fun invalidUrlNeverReachesHttpFetcher() = runTest {
        var called = false
        val repo = IpRepository(HttpFetcher { _, _ -> called = true; "8.8.8.8" })
        assertTrue(runCatching { repo.lookupIp("http://example.com") }.isFailure)
        assertFalse(called)
    }
    @Test fun geoUrlUsesValidatedIpAndBoundedResponse() = runTest {
        val repo = IpRepository(HttpFetcher { url, max ->
            assertEquals("https://get.geojs.io/v1/ip/geo/8.8.8.8.json", url)
            assertEquals(16 * 1024, max)
            """{"ip":"8.8.8.8","country":"United States","country_code":"us","region":"California"}"""
        })
        assertEquals("🇺🇸 United States, California", repo.lookupLocation(ip).label)
    }
    @Test fun mismatchingOrMissingGeoIpIsNotDisplayed() {
        for (body in listOf("bad", "{}", "[]", """{"ip":"1.1.1.1","country":"wrong"}""", """{"ip":123}""")) {
            assertEquals(LookupError.INVALID_RESPONSE, assertThrows(LookupFailure::class.java) { IpRepository.parseLocation(body, ip) }.error)
        }
    }
    @Test fun geolocationTextAndCountryCodeAreSanitized() {
        val info = IpRepository.parseLocation("""{"ip":"8.8.8.8","country":"  A\u202eB\u0000  C ","region":45,"country_code":"ßß"}""", ip)
        assertEquals("AB C", info.country)
        assertEquals("", info.region)
        assertEquals("", info.countryCode)
        assertEquals("", GeoInfo("", "", "X").label)
        assertEquals("🇺🇸 A", GeoInfo("A", "A", "US").label)
        assertEquals("", GeoInfo("", "", "äA").label)
    }
    @Test fun excessiveFieldsAreBoundedAndMissingFieldsAreSafe() {
        val json = """{"ip":"8.8.8.8","country":"${"x".repeat(5000)}","region":"${"y".repeat(500)}","country_code":null}"""
        val info = IpRepository.parseLocation(json, ip)
        assertEquals(80, info.country.length)
        assertEquals(128, info.region.length)
        assertEquals("", info.countryCode)
        assertEquals("", IpRepository.parseLocation("""{"ip":"8.8.8.8"}""", ip).label)
    }
}
