package com.vm2st.ip

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Optional integration test; never logs the machine's address or location. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveNetworkTest {
    @Test fun sandboxHttpsSourcesAndGeoJsReturnValidatedData() {
        assumeTrue("Set MYIP_NETWORK_CHECK=1 to contact external services", System.getenv("MYIP_NETWORK_CHECK") == "1")
        runBlocking {
            val repository = IpRepository(SafeHttpTransport())
            val address = repository.lookupIp("https://api64.ipify.org")
            assertTrue(address.isPublic)
            assertTrue(repository.lookupIp("https://checkip.amazonaws.com").isPublic)
            val location = repository.lookupLocation(address)
            assertTrue(location.country.isNotEmpty())
        }
    }
}
