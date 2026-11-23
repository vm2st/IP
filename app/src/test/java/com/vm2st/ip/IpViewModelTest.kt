@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vm2st.ip

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}
class MemorySettings : SettingsStore {
    override var selectedSource = "ipify"
    override var customUrl = "https://custom.example/ip"
    override var geoEnabled = false
}
class IpViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()
    private val first = IpAddress.parsePublic("8.8.8.8")!!
    private val second = IpAddress.parsePublic("1.1.1.1")!!
    private val location = GeoInfo("Country", "Region", "US")
    private fun model(settings: SettingsStore = MemorySettings(), repo: IpDataSource, clock: () -> Long = { 100L }): IpViewModel =
        IpViewModel(settings, repo, clock).also { vm -> stores += ViewModelStore().apply { put("vm", vm) } }
    @After fun cleanup() { stores.forEach { it.clear() } }
    private class FakeRepo(
        private val ip: suspend (String) -> IpAddress,
        private val geo: suspend (IpAddress) -> GeoInfo = { throw AssertionError("Unexpected geo request") }
    ) : IpDataSource {
        var ips = 0; var geos = 0
        override suspend fun lookupIp(url: String): IpAddress { ips++; return ip(url) }
        override suspend fun lookupLocation(ip: IpAddress): GeoInfo { geos++; return geo(ip) }
    }
    @Test fun startupAndInitialSpinnerSelectionMakeOnlyOneIpRequest() = runTest {
        val settings = MemorySettings()
        val repo = FakeRepo({ first })
        val vm = model(settings, repo)
        assertTrue(vm.state.value.loadingIp)
        vm.selectSource("ipify")
        vm.refreshIfStale()
        advanceUntilIdle()
        assertEquals(1, repo.ips)
        assertEquals(0, repo.geos)
        assertEquals(first, vm.state.value.ip)
        assertFalse(vm.state.value.loadingIp)
    }
    @Test fun copyableIpIsPublishedBeforeGeoFinishesAndSurvivesItsFailure() = runTest {
        val settings = MemorySettings().apply { geoEnabled = true }
        val pending = CompletableDeferred<GeoInfo>()
        val repo = FakeRepo({ first }, { pending.await() })
        val vm = model(settings, repo)
        runCurrent()
        assertEquals(first, vm.state.value.ip)
        assertFalse(vm.state.value.loadingIp)
        assertTrue(vm.state.value.loadingGeo)
        pending.completeExceptionally(LookupFailure(LookupError.NETWORK))
        advanceUntilIdle()
        assertEquals(first, vm.state.value.ip)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.geoFailed)
        assertFalse(vm.state.value.loadingGeo)
    }
    @Test fun sourceChangeIgnoresOldReplyEvenIfRepositoryIgnoresCancellation() = runTest {
        val replies = mutableListOf<Continuation<IpAddress>>()
        val settings = MemorySettings()
        val repo = FakeRepo({ suspendCoroutine { replies += it } })
        val vm = model(settings, repo)
        runCurrent()
        vm.selectSource("ident")
        runCurrent()
        assertEquals("ident", settings.selectedSource)
        assertEquals(2, replies.size)
        replies[1].resume(second)
        runCurrent()
        replies[0].resume(first)
        runCurrent()
        assertEquals(second, vm.state.value.ip)
        assertEquals("ident", vm.state.value.sourceId)
    }
    @Test fun disablingGeoCancelsItWithoutLosingIpAndLateReplyIsIgnored() = runTest {
        val settings = MemorySettings().apply { geoEnabled = true }
        lateinit var reply: Continuation<GeoInfo>
        val repo = FakeRepo({ first }, { suspendCoroutine { reply = it } })
        val vm = model(settings, repo)
        runCurrent()
        vm.setGeoEnabled(false)
        reply.resume(location)
        runCurrent()
        assertEquals(first, vm.state.value.ip)
        assertNull(vm.state.value.location)
        assertFalse(vm.state.value.loadingGeo)
        assertFalse(settings.geoEnabled)
        assertEquals(1, repo.ips)
    }
    @Test fun enablingGeoUsesCurrentIpWithoutRepeatingIpLookup() = runTest {
        val settings = MemorySettings()
        val repo = FakeRepo({ first }, { assertEquals(first, it); location })
        val vm = model(settings, repo)
        advanceUntilIdle()
        vm.setGeoEnabled(true)
        advanceUntilIdle()
        assertEquals(1, repo.ips)
        assertEquals(1, repo.geos)
        assertEquals(location, vm.state.value.location)
        assertTrue(settings.geoEnabled)
    }
    @Test fun newIpLookupInvalidatesGeoForThePreviousAddress() = runTest {
        val settings = MemorySettings().apply { geoEnabled = true }
        val replies = mutableListOf<Continuation<GeoInfo>>()
        var calls = 0
        val repo = FakeRepo({ if (++calls == 1) first else second }, { suspendCoroutine { replies += it } })
        val vm = model(settings, repo)
        runCurrent()
        vm.selectSource("amazon")
        runCurrent()
        replies[1].resume(GeoInfo("New", "", ""))
        runCurrent()
        replies[0].resume(GeoInfo("Old", "", ""))
        runCurrent()
        assertEquals(second, vm.state.value.ip)
        assertEquals("New", vm.state.value.location?.country)
    }
    @Test fun refreshWhileLoadingDoesNotSpawnThreadsOrDuplicateRequests() = runTest {
        val pending = CompletableDeferred<IpAddress>()
        val repo = FakeRepo({ pending.await() })
        val vm = model(repo = repo)
        repeat(30) { vm.refresh() }
        runCurrent()
        assertEquals(1, repo.ips)
        pending.complete(first)
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, repo.ips)
    }
    @Test fun invalidCustomUrlStaysUnchangedAndDoesNotStartNetwork() = runTest {
        val settings = MemorySettings().apply { selectedSource = "custom" }
        val repo = FakeRepo({ first })
        val vm = model(settings, repo)
        advanceUntilIdle()
        assertFalse(vm.saveCustomUrl("http://localhost"))
        assertEquals("https://custom.example/ip", settings.customUrl)
        assertEquals(1, repo.ips)
        assertTrue(vm.saveCustomUrl("https://example.com/new"))
        advanceUntilIdle()
        assertEquals("https://example.com/new", settings.customUrl)
        assertEquals(2, repo.ips)
        assertTrue(vm.saveCustomUrl("https://example.com/new"))
        advanceUntilIdle()
        assertEquals(2, repo.ips)
    }
    @Test fun failedIpLookupEndsProgressAndGeoNeverRuns() = runTest {
        val settings = MemorySettings().apply { geoEnabled = true }
        val repo = FakeRepo({ throw LookupFailure(LookupError.INVALID_IP) })
        val vm = model(settings, repo)
        advanceUntilIdle()
        assertEquals(LookupError.INVALID_IP, vm.state.value.error?.error)
        assertNull(vm.state.value.ip)
        assertFalse(vm.state.value.loadingIp)
        assertFalse(vm.state.value.loadingGeo)
        assertEquals(0, repo.geos)
    }
    @Test fun staleBackgroundResultRefreshesOnlyAfterOneMinute() = runTest {
        var now = 0L
        val repo = FakeRepo({ first })
        val vm = model(repo = repo, clock = { now })
        advanceUntilIdle()
        now = 59_999
        vm.refreshIfStale()
        advanceUntilIdle()
        assertEquals(1, repo.ips)
        now = 60_001
        vm.refreshIfStale()
        vm.refreshIfStale()
        advanceUntilIdle()
        assertEquals(2, repo.ips)
    }
    @Test fun invalidStoredSourceFallsBackWithoutIndexCrash() = runTest {
        val settings = MemorySettings().apply { selectedSource = "removed-source" }
        val repo = FakeRepo({ assertEquals("https://api64.ipify.org", it); first })
        val vm = model(settings, repo)
        advanceUntilIdle()
        assertEquals("ipify", vm.state.value.sourceId)
        vm.selectSource("nonsense")
        advanceUntilIdle()
        assertEquals(1, repo.ips)
    }
}
