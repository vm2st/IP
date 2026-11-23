package com.vm2st.ip

import android.content.Context
import androidx.core.content.edit

interface SettingsStore {
    var selectedSource: String
    var customUrl: String
    var geoEnabled: Boolean
}

data class IpSource(val id: String, val title: String, val url: String?)
object IpSources {
    val all = listOf(
        IpSource("ipify", "ipify.org", "https://api64.ipify.org"),
        IpSource("icanhazip", "icanhazip.com", "https://icanhazip.com"),
        IpSource("ifconfig", "ifconfig.me", "https://ifconfig.me/ip"),
        IpSource("ident", "ident.me", "https://ident.me"),
        IpSource("seeip", "seeip.org", "https://api.seeip.org"),
        IpSource("wtfismyip", "wtfismyip.com", "https://text.wtfismyip.com"),
        IpSource("ident-v4", "v4.ident.me", "https://v4.ident.me"),
        IpSource("amazon", "checkip.amazonaws.com", "https://checkip.amazonaws.com"),
        IpSource("tyk", "ip.tyk.nu", "https://ip.tyk.nu"),
        IpSource("wgetip", "wgetip.com", "https://wgetip.com"),
        IpSource("custom", "", null)
    )
    const val CUSTOM = "custom"
    fun find(id: String): IpSource = all.firstOrNull { it.id == id } ?: all.first()
}

/** Only preferences, never IP or geolocation history. Not exported or backed up. */
class IpSettings(context: Context) : SettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences("IP_PREFS", Context.MODE_PRIVATE)
    override var selectedSource: String
        get() = IpSources.find(preferences.all["source_id"] as? String ?: "ipify").id
        set(value) { preferences.edit { putString("source_id", IpSources.find(value).id) } }
    override var customUrl: String
        get() = preferences.all["custom_url"] as? String ?: "https://api64.ipify.org"
        set(value) { preferences.edit { putString("custom_url", value) } }
    override var geoEnabled: Boolean
        get() = preferences.all["geo_enabled"] as? Boolean ?: false
        set(value) { preferences.edit { putBoolean("geo_enabled", value) } }
    var theme: AppTheme
        get() = AppTheme.entries.firstOrNull { it.name == preferences.all["theme"] as? String } ?: AppTheme.LIGHT
        set(value) { preferences.edit { putString("theme", value.name) } }
}
