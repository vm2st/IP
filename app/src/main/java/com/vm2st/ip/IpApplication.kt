package com.vm2st.ip

import android.app.Application

open class IpApplication : Application() {
    val repository: IpDataSource by lazy { createRepository() }
    open fun createRepository(): IpDataSource = IpRepository(SafeHttpTransport())
}
