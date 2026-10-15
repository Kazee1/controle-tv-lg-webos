package com.example.tvremote.feature.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.tvremote.MainApplication

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as MainApplication).repository
    val state = repository.state
}
