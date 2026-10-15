package com.example.tvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.tvremote.core.ui.TvRemoteTheme
import com.example.tvremote.feature.main.MainScreen
import com.example.tvremote.feature.main.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.repository.setAppActive(true)
                Lifecycle.Event.ON_STOP -> viewModel.repository.setAppActive(false)
                else -> Unit
            }
        })
        setContent { TvRemoteTheme { MainScreen(viewModel) } }
    }
}
