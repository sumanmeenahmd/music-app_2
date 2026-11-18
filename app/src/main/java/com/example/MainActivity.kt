package com.example

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.example.network.NetworkMonitor
import com.example.ui.WebAppScreen
import com.example.ui.theme.SonoraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private lateinit var networkMonitor: NetworkMonitor
  private var isInitialLoadFinished = false

  override fun onCreate(savedInstanceState: Bundle?) {
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)

    splashScreen.setKeepOnScreenCondition { !isInitialLoadFinished }
    lifecycleScope.launch {
      delay(2500L)
      isInitialLoadFinished = true
    }

    volumeControlStream = AudioManager.STREAM_MUSIC
    networkMonitor = NetworkMonitor(this)
    networkMonitor.start()

    enableEdgeToEdge()
    setContent {
      SonoraTheme {
        WebAppScreen(
          networkMonitor = networkMonitor,
          onInitialLoadFinished = { isInitialLoadFinished = true },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }

  override fun onDestroy() {
    if (::networkMonitor.isInitialized) {
      networkMonitor.stop()
    }
    super.onDestroy()
  }
}
