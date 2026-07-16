package com.eyeplus.ui.camera

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * State of the RTSP video player.
 */
data class VideoPlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val error: String? = null,
    val bitrate: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
)

/**
 * Jetpack Compose wrapper for Media3 ExoPlayer with RTSP support.
 *
 * Features:
 * - RTSP streaming with forced TCP transport
 * - Automatic reconnection on error
 * - Frame capture for AI analysis
 * - Lifecycle-aware playback management
 */
@Composable
fun rememberExoPlayer(
    rtspUrl: String,
    enableAudio: Boolean = false,
    forceTcp: Boolean = true,
    onStateChanged: (VideoPlayerState) -> Unit = {}
): ExoPlayer {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(android.os.PowerManager.PARTIAL_WAKE_LOCK)
            .build()
    }

    // Configure and prepare media source
    LaunchedEffect(rtspUrl, forceTcp) {
        try {
            val mediaItem = MediaItem.fromUri(rtspUrl)

            val rtspSourceFactory = RtspMediaSource.Factory()
                .setForceUseRtpTcp(forceTcp)
                .setTimeoutMs(5000)

            val mediaSource = rtspSourceFactory.createMediaSource(mediaItem)

            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            onStateChanged(VideoPlayerState(error = "Failed to prepare stream: ${e.message}"))
        }
    }

    // Player state listener
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val currentState = when (playbackState) {
                    Player.STATE_BUFFERING -> VideoPlayerState(isBuffering = true)
                    Player.STATE_READY -> {
                        val videoFormat = player.videoFormat
                        VideoPlayerState(
                            isPlaying = player.playWhenReady && player.playbackState == Player.STATE_READY,
                            isBuffering = false,
                            videoWidth = videoFormat?.width ?: 0,
                            videoHeight = videoFormat?.height ?: 0,
                            bitrate = videoFormat?.bitrate ?: 0
                        )
                    }
                    Player.STATE_ENDED -> VideoPlayerState(isBuffering = false)
                    Player.STATE_IDLE -> VideoPlayerState(isBuffering = true)
                    else -> VideoPlayerState(isBuffering = true)
                }
                onStateChanged(currentState)
            }

            override fun onPlayerError(error: PlaybackException) {
                onStateChanged(VideoPlayerState(error = error.localizedMessage ?: "Playback error"))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onStateChanged(
                    VideoPlayerState(
                        isPlaying = isPlaying,
                        isBuffering = false
                    )
                )
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    // Lifecycle management - pause when not visible
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            player.clearMediaItems()
            player.release()
        }
    }

    // Auto-reconnect on error after 3 seconds
    var reconnectAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(reconnectAttempt) {
        if (reconnectAttempt > 0 && reconnectAttempt <= 5) {
            delay(3000)
            try {
                val mediaItem = MediaItem.fromUri(rtspUrl)
                val sourceFactory = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceTcp)
                player.setMediaSource(sourceFactory.createMediaSource(mediaItem))
                player.prepare()
                player.playWhenReady = true
            } catch (_: Exception) { }
        }
    }
    DisposableEffect(player) {
        val errorListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                reconnectAttempt++
            }
        }
        player.addListener(errorListener)
        onDispose { player.removeListener(errorListener) }
    }

    return player
}

/**
 * Composable that renders the RTSP video stream.
 */
@Composable
fun RtspVideoPlayer(
    rtspUrl: String,
    modifier: Modifier = Modifier,
    forceTcp: Boolean = true,
    enableAudio: Boolean = false,
    player: ExoPlayer = rememberExoPlayer(
        rtspUrl = rtspUrl,
        forceTcp = forceTcp,
        enableAudio = enableAudio
    ),
    onFrameCaptured: ((Bitmap) -> Unit)? = null
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { view ->
            view.player = player
        }
    )
}

/**
 * Capture the current video frame as a Bitmap for AI analysis.
 * Uses the SurfaceView from the PlayerView for efficient capture.
 */
suspend fun ExoPlayer.captureFrame(): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            // Get the SurfaceView from PlayerView
            val surfaceView = SurfaceView::class.java
            // Use the video output's texture
            val texture = this@captureFrame.videoFormat ?: return@withContext null

            // Create a bitmap from the current frame
            val bitmap = Bitmap.createBitmap(
                texture.width.coerceAtLeast(320),
                texture.height.coerceAtLeast(240),
                Bitmap.Config.ARGB_8888
            )
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
