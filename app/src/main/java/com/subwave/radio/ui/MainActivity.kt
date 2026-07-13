package com.subwave.radio.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.subwave.radio.player.RadioPlaybackService

private val SubwaveBackground = Color(0xFF100E0C)

class MainActivity : ComponentActivity() {

    private var mediaController: MediaController? = null
    private lateinit var viewModel: PlayerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = SessionToken(
            this,
            ComponentName(this, RadioPlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            mediaController = controller

            // PlayerViewModel expects an ExoPlayer-shaped Player; MediaController
            // implements the same Player interface used for playback commands.
            viewModel = ViewModelProvider(
                this,
                PlayerViewModelFactory(controller, applicationContext)
            )[PlayerViewModel::class.java]

            viewModel.reconnectToLastServerIfAvailable()

            setContent {
                MaterialTheme(colorScheme = darkColorScheme(background = SubwaveBackground)) {
                    PlayerScreen(viewModel = viewModel)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onDestroy() {
        mediaController?.release()
        super.onDestroy()
    }
}

class PlayerViewModelFactory(
    private val player: androidx.media3.common.Player,
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(player, context) as T
    }
}
