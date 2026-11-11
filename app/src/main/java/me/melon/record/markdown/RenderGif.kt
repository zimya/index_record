package me.melon.record.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

import me.melon.record.BuildConfig

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun RenderGif(url: String, onLinkClick: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var attempt by remember(url) { mutableStateOf(0) }
    key(url, lifecycleOwner, attempt) {
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var aspectRatio by remember { mutableStateOf(16f / 9f) }
        val player = remember {
            ExoPlayer.Builder(context.applicationContext).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
            }
        }

        DisposableEffect(player, lifecycleOwner) {
            val lifecycle = lifecycleOwner.lifecycle
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    loading = playbackState != Player.STATE_READY
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (BuildConfig.DEBUG) Log.d("RecordGif", "Rendered first frame: $url")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w("RecordGif", "GIF playback failed (${error.errorCodeName}): $url", error)
                    failed = true
                    loading = false
                    player.pause()
                }
            }
            val observer = LifecycleEventObserver { _, _ ->
                player.playWhenReady = !failed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
            player.addListener(listener)
            lifecycle.addObserver(observer)
            player.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            onDispose {
                lifecycle.removeObserver(observer)
                player.removeListener(listener)
                player.release()
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (failed) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("GIF 播放失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { attempt++ }) { Text("重试") }
                        TextButton(onClick = { onLinkClick(url) }) { Text("外部打开") }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio).background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    // TextureView 随 Compose 一起位移、裁剪和淡出，避免独立 Surface 在返回动画中闪黑。
                    AndroidView(
                        factory = { viewContext ->
                            TextureView(viewContext).also { player.setVideoTextureView(it) }
                        },
                        onRelease = { player.clearVideoTextureView(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (loading) CircularProgressIndicator()
                }
            }
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GIF", url))
                    Toast.makeText(context, "GIF 链接已复制", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("复制 GIF 链接")
            }
        }
    }
}
