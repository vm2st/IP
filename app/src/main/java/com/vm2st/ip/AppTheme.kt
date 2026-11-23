package com.vm2st.ip

import androidx.appcompat.app.AppCompatDelegate

enum class AppTheme(val style: Int, val nightMode: Int) {
    LIGHT(R.style.Theme_MyIP_Light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK(R.style.Theme_MyIP_Dark, AppCompatDelegate.MODE_NIGHT_YES),
    BURGUNDY(R.style.Theme_MyIP_Burgundy, AppCompatDelegate.MODE_NIGHT_YES)
}
