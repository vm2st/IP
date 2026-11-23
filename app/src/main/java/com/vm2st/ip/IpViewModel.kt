package com.vm2st.ip

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IpUiState(
    val sourceId: String,
    val geoEnabled: Boolean,
    val ip: IpAddress? = null,
    val location: GeoInfo? = null,
    val loadingIp: Boolean = false,
    val loadingGeo: Boolean = false,
    val error: LookupFailure? = null,
    val geoFailed: Boolean = false
)

class IpViewModel(
    private val settings: SettingsStore,
    private val repository: IpDataSource,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : ViewModel() {
    private val mutableState = MutableStateFlow(IpUiState(IpSources.find(settings.selectedSource).id, settings.geoEnabled))
    val state: StateFlow<IpUiState> = mutableState.asStateFlow()
    private var ipJob: Job? = null
    private var geoJob: Job? = null
    private var generation = 0L
    private var geoGeneration = 0L
    private var lastAttempt: Long? = null

    init { startLookup() }

    fun selectSource(id: String) {
        val source = IpSources.find(id)
        if (source.id == state.value.sourceId) return
        settings.selectedSource = source.id
        mutableState.value = state.value.copy(sourceId = source.id)
        startLookup()
    }

    fun refresh() {
        if (!state.value.loadingIp) startLookup()
    }

    fun refreshIfStale() {
        val last = lastAttempt ?: return
        if (!state.value.loadingIp && clock() - last >= 60_000) startLookup()
    }

    fun setGeoEnabled(enabled: Boolean) {
        if (enabled == state.value.geoEnabled) return
        settings.geoEnabled = enabled
        geoGeneration++
        geoJob?.cancel()
        mutableState.value = state.value.copy(geoEnabled = enabled, loadingGeo = false, location = null, geoFailed = false)
        if (enabled) state.value.ip?.let { startGeo(it, generation) }
    }

    fun customUrl(): String = settings.customUrl
    fun saveCustomUrl(input: String): Boolean {
        val url = try { UrlPolicy.validate(input).toString() } catch (e: LookupFailure) { return false }
        val changed = url != settings.customUrl
        settings.customUrl = url
        if (changed && state.value.sourceId == IpSources.CUSTOM) startLookup()
        return true
    }

    private fun startLookup() {
        val token = ++generation
        geoGeneration++
        ipJob?.cancel()
        geoJob?.cancel()
        lastAttempt = clock()
        mutableState.value = state.value.copy(ip = null, location = null, loadingIp = true,
            loadingGeo = false, error = null, geoFailed = false)
        val source = IpSources.find(state.value.sourceId)
        val url = source.url ?: settings.customUrl
        ipJob = viewModelScope.launch {
            try {
                val address = repository.lookupIp(url)
                if (token != generation) return@launch
                mutableState.value = state.value.copy(ip = address, loadingIp = false, error = null)
                if (state.value.geoEnabled) startGeo(address, token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) mutableState.value = state.value.copy(error = e.safeLookupError(), loadingIp = false)
            } finally {
                if (token == generation && state.value.loadingIp) mutableState.value = state.value.copy(loadingIp = false)
            }
        }
    }

    private fun startGeo(address: IpAddress, ipToken: Long) {
        val token = ++geoGeneration
        geoJob?.cancel()
        mutableState.value = state.value.copy(loadingGeo = true, location = null, geoFailed = false)
        geoJob = viewModelScope.launch {
            try {
                val info = repository.lookupLocation(address)
                if (token == geoGeneration && ipToken == generation && state.value.geoEnabled) {
                    mutableState.value = state.value.copy(location = info, loadingGeo = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == geoGeneration && ipToken == generation && state.value.geoEnabled) {
                    mutableState.value = state.value.copy(geoFailed = true, loadingGeo = false)
                }
            } finally {
                if (token == geoGeneration && ipToken == generation && state.value.loadingGeo) {
                    mutableState.value = state.value.copy(loadingGeo = false)
                }
            }
        }
    }
}
