package com.subwave.radio.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.subwave.radio.R
import com.subwave.radio.data.ServerPrefs
import com.subwave.radio.data.TrackMetadataLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

/**
 * Single-stream Icecast playback service.
 *
 * Exposes a minimal browse tree (Android Auto requires one even for a
 * single-item app) and auto-starts playback when the Auto head unit connects.
 *
 * ICY (in-stream) track metadata is handled here rather than by a connected
 * MediaController: Media3 only syncs the aggregated [MediaMetadata] of the
 * current item across the session, not raw [Player.Listener.onMetadata]
 * events, so this is the only place that ever sees them.
 */
class RadioPlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    var currentStreamUrl: String? = null

    private val metadataLookup = TrackMetadataLookup()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var hasReceivedTrackMetadata = false

    private val fallbackArtworkUri: Uri by lazy {
        Uri.parse("android.resource://$packageName/${R.drawable.ic_fallback_artwork}")
    }

    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val streamUrl = currentStreamUrl
            if (streamUrl == null) {
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
            val streamItem = MediaItem.Builder()
                .setMediaId("live_stream")
                .setUri(streamUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Listen live")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.of(streamItem), params)
            )
        }

        // Called when the system wants to resume playback on its own - e.g.
        // "Hey Google, play Subwave", or Android Auto's resume affordance -
        // without the phone app ever having been opened this session.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val rawServer = ServerPrefs.getLastServer(this@RadioPlaybackService)
                ?: return Futures.immediateFailedFuture(
                    UnsupportedOperationException("No saved server address")
                )

            val streamUrl = try {
                buildIcecastStreamUrl(rawServer)
            } catch (e: IllegalArgumentException) {
                return Futures.immediateFailedFuture(e)
            }
            currentStreamUrl = streamUrl

            val mediaItem = MediaItem.Builder()
                .setMediaId("live_stream")
                .setUri(streamUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Loading…")
                        .setArtworkUri(fallbackArtworkUri)
                        .setIsPlayable(true)
                        .build()
                )
                .build()

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(ImmutableList.of(mediaItem), 0, 0L)
            )
        }

        // Unplugging the phone tears down Android Auto's connection to this
        // session; stop playback rather than keep streaming in the
        // background with no car display attached. Kept as a second signal
        // alongside the power-disconnect receiver below - Android Auto's own
        // teardown timing isn't reliable enough to depend on alone.
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (controller.packageName == ANDROID_AUTO_PACKAGE) {
                stopPlayback()
            }
        }
    }

    // The same USB cable that carries Android Auto's data connection also
    // carries power in virtually every car setup, so losing external power
    // is a far more immediate, hardware-level signal that the phone was
    // unplugged than waiting on Android Auto's own app to disconnect.
    private val powerDisconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Icecast servers only embed in-stream track metadata (ICY) when the
        // client explicitly asks for it via this request header.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            // Lets ExoPlayer pause for phone calls/other audio and resume
            // automatically once focus is regained, instead of playing
            // through or over them.
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                hasReceivedTrackMetadata = false
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !hasReceivedTrackMetadata) {
                    applyFallbackNowPlaying()
                }
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is IcyInfo) {
                        entry.title?.takeIf { it.isNotBlank() }?.let(::handleIcyTitle)
                    }
                }
            }
        })

        mediaSession = MediaLibrarySession.Builder(this, player, librarySessionCallback).build()

        ContextCompat.registerReceiver(
            this,
            powerDisconnectedReceiver,
            IntentFilter(Intent.ACTION_POWER_DISCONNECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
    }

    private fun applyFallbackNowPlaying() {
        val host = player.currentMediaItem?.localConfiguration?.uri?.host ?: "live stream"
        val metadata = MediaMetadata.Builder()
            .setTitle("Live: $host")
            .setArtist("Streaming now")
            .setArtworkUri(fallbackArtworkUri)
            .build()
        replaceCurrentMetadata(metadata)
    }

    private fun handleIcyTitle(raw: String) {
        hasReceivedTrackMetadata = true
        val (artist, title) = metadataLookup.parseIcyString(raw)

        serviceScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { metadataLookup.query(artist, title) }
            } catch (e: Exception) {
                null
            }
            val artworkUri = result?.artworkUrl?.let(Uri::parse) ?: fallbackArtworkUri

            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artworkUri)
                .build()
            replaceCurrentMetadata(metadata)
        }
    }

    private fun replaceCurrentMetadata(metadata: MediaMetadata) {
        player.currentMediaItem?.let { currentItem ->
            val updatedItem = currentItem.buildUpon().setMediaMetadata(metadata).build()
            player.replaceMediaItem(0, updatedItem)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession = mediaSession

    fun getPlayer(): ExoPlayer = player

    override fun onDestroy() {
        unregisterReceiver(powerDisconnectedReceiver)
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
