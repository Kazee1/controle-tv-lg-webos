package com.example.tvremote

import android.app.Application
import com.example.tvremote.core.storage.PreferencesStore
import com.example.tvremote.core.storage.SecurePairingStore
import com.example.tvremote.tv.discovery.TvDiscoveryService
import com.example.tvremote.tv.repository.TvControlRepository

class MainApplication : Application() {
    val repository: TvControlRepository by lazy {
        TvControlRepository(
            preferences = PreferencesStore(this),
            secureStore = SecurePairingStore(this),
            discovery = TvDiscoveryService(this),
        )
    }
}
