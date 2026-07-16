package com.subwave.radio.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.car.app.connection.CarConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.subwave.radio.R
import com.subwave.radio.data.ServerPrefs
import com.subwave.radio.player.CarDrivingState
import com.subwave.radio.player.buildIcecastStreamUrl
import com.subwave.radio.player.getReadableErrorMessage

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

data class NowPlaying(
    val title: String = "",
    val subtitle: String = "",
    val artworkUri: Uri? = null
)

class PlayerViewModel(
    private val player: Player,
    private val context: Context
) : ViewModel() {

    val contextRef: Context get() = context

    private val fallbackArtworkUri: Uri =
        Uri.parse("android.resource://${context.packageName}/${R.drawable.ic_fallback_artwork}")

    var connectionState: ConnectionState by mutableStateOf(ConnectionState.Idle)
        private set

    var nowPlaying: NowPlaying by mutableStateOf(NowPlaying(artworkUri = fallbackArtworkUri))
        private set

    /** True when the car requires distraction-optimized UI (i.e. is moving). */
    var requiresParkedMode: Boolean by mutableStateOf(false)
        private set

    // Native Automotive OS reports real UX restrictions via android.car; a
    // phone has no such service to query even while connected to Android
    // Auto (FEATURE_AUTOMOTIVE is false there), so it's only ever non-null
    // on an embedded automotive install.
    private var requiresDistractionOptimization = false

    // On a phone, Android Auto gives no distraction-optimization signal at
    // all, so treat "connected via projection" as reason enough to require
    // parked mode - more conservative than gating on actual motion, but the
    // best signal available from the phone side.
    private var isAndroidAutoProjectionConnected = false

    private fun refreshRequiresParkedMode() {
        requiresParkedMode = requiresDistractionOptimization || isAndroidAutoProjectionConnected
    }

    private val carDrivingState: CarDrivingState? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            CarDrivingState(context) {
                requiresDistractionOptimization = it
                refreshRequiresParkedMode()
            }
        } else {
            null
        }

    private val carConnection = CarConnection(context)
    private val carConnectionObserver = Observer<Int> { connectionType ->
        isAndroidAutoProjectionConnected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
        refreshRequiresParkedMode()
    }

    init {
        // No LifecycleOwner is available here, so observe for the
        // ViewModel's own lifetime and remove the observer in onCleared.
        carConnection.type.observeForever(carConnectionObserver)

        // RadioPlaybackService owns ICY (in-stream) metadata handling and
        // republishes it as the current MediaItem's MediaMetadata, which -
        // unlike raw Player.Listener.onMetadata events - is synced to this
        // MediaController automatically.
        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString() ?: return
                nowPlaying = NowPlaying(
                    title = title,
                    subtitle = mediaMetadata.artist?.toString().orEmpty(),
                    artworkUri = mediaMetadata.artworkUri ?: fallbackArtworkUri
                )
            }

            // Keeps the UI in sync if playback is stopped from outside this
            // ViewModel - e.g. RadioPlaybackService stopping itself when
            // Android Auto disconnects (phone unplugged from the car).
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_IDLE) {
                    connectionState = ConnectionState.Idle
                    nowPlaying = NowPlaying(artworkUri = fallbackArtworkUri)
                }
            }
        })
    }

    fun playFromUserInput(rawUrl: String) {
        val streamUrl = try {
            buildIcecastStreamUrl(rawUrl)
        } catch (e: IllegalArgumentException) {
            connectionState = ConnectionState.Failed(e.message ?: "Invalid server address.")
            return
        }

        connectionState = ConnectionState.Connecting
        nowPlaying = NowPlaying(title = "Loading…", artworkUri = fallbackArtworkUri)

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Loading…")
                    .setArtworkUri(fallbackArtworkUri)
                    .build()
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    connectionState = ConnectionState.Connected
                    ServerPrefs.saveLastServer(context, rawUrl)
                    player.removeListener(this)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val message = getReadableErrorMessage(error)
                connectionState = ConnectionState.Failed(message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                player.removeListener(this)
            }
        })

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    /** Call on app start to reconnect to the last address that worked, if any. */
    fun reconnectToLastServerIfAvailable() {
        ServerPrefs.getLastServer(context)?.let { playFromUserInput(it) }
    }

    /** Stops playback and releases the stream, without closing the app. */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        connectionState = ConnectionState.Idle
        nowPlaying = NowPlaying(artworkUri = fallbackArtworkUri)
    }

    override fun onCleared() {
        carConnection.type.removeObserver(carConnectionObserver)
        carDrivingState?.release()
        super.onCleared()
    }
}
