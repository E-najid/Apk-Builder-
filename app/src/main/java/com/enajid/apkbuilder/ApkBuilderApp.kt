package com.enajid.apkbuilder

import android.app.Application
import com.enajid.apkbuilder.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ApkBuilderApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Keep the in-memory token cache in sync with DataStore (including sign-out).
        appScope.launch {
            // Seal any legacy plaintext token first (no-op when already sealed).
            container.tokenStore.migrateLegacyToken()
            container.tokenStore.tokenFlow.collect { container.tokenStore.updateCached(it) }
        }
    }
}
