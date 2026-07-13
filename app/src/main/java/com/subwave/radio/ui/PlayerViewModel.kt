package com.subwave.radio.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.subwave.radio.R
import com.subwave.radio.data.ServerPrefs
import com.subwave.radio.data.TrackMetadataLookup
import com.subwave.radio.player.buildIcecastStreamUrl
import com.subwave.radio.player.getReadableErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val context: Context,
    private val metadataLookup: TrackMetadataLookup = TrackMetadataLookup()
) : ViewModel() {

    val contextRef: Context get() = context

    private val fallbackArtworkUri: Uri =
        Uri.parse("android.resource://${context.packageName}/${R.drawable.ic_fallback_artwork}")

    var connectionState: ConnectionState by mutableStateOf(ConnectionState.Idle)
        private set

    var nowPlaying: NowPlaying by mutableStateOf(NowPlaying(artworkUri = fallbackArtworkUri))
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is IcyInfo) {
                        entry.title?.takeIf { it.isNotBlank() }?.let(::handleRawIcyString)
                    }
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

    private fun handleRawIcyString(raw: String) {
        val (artist, title) = metadataLookup.parseIcyString(raw)
        fetchEnrichedMetadata(artist, title)
    }

    private fun fetchEnrichedMetadata(artist: String, title: String) {
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { metadataLookup.query(artist, title) }
            } catch (e: Exception) {
                null
            }

            val subtitle = if (result?.year != null) "$artist · ${result.year}" else artist
            val artworkUri = result?.artworkUrl?.let(Uri::parse) ?: fallbackArtworkUri

            nowPlaying = NowPlaying(title = title, subtitle = subtitle, artworkUri = artworkUri)

            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(artworkUri)
                .build()

            player.currentMediaItem?.let { currentItem ->
                val updatedItem = currentItem.buildUpon().setMediaMetadata(metadata).build()
                player.replaceMediaItem(0, updatedItem)
            }
        }
    }
}
