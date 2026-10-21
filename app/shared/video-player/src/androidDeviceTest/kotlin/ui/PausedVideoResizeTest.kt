/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.platform.app.InstrumentationRegistry
import me.him188.ani.app.ui.foundation.LocalIsPreviewing
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Run on an API 34 emulator to cover androidx/media#1237, and on newer Android as a smoke test.
 * View bounds alone miss this bug: SurfaceFlinger can still display the buffer at the old bounds.
 * The screenshot must include the real SurfaceView, so captureToImage/assertScreenshot is insufficient.
 *
 * Fixture: a two-second H.264 clip with red/green/blue/yellow quadrants, generated with FFmpeg:
 * color=c=red:s=320x180:r=10:d=2,drawbox=x=160:y=0:w=160:h=90:color=lime:t=fill,
 * drawbox=x=0:y=90:w=160:h=90:color=blue:t=fill,drawbox=x=160:y=90:w=160:h=90:color=yellow:t=fill
 */
@OptIn(UnstableApi::class)
class PausedVideoResizeTest {
    @Test
    fun pausedFrameFollowsFullscreenSizeChanges() = runAniComposeUiTest {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaFile = File(instrumentation.targetContext.cacheDir, "paused-resize.mp4")
        instrumentation.context.assets.open("paused-resize.mp4").use { input ->
            mediaFile.outputStream().use { input.copyTo(it) }
        }
        val firstFrame = AtomicBoolean()
        lateinit var player: ExoPlayerMediampPlayer
        var viewport = Rect.Zero
        var buttonBounds = Rect.Zero
        var fullscreen by mutableStateOf(false)
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            player = remember { ExoPlayerMediampPlayer(context, scope.coroutineContext) }
            DisposableEffect(player) {
                player.impl.addAnalyticsListener(object : AnalyticsListener {
                    override fun onRenderedFirstFrame(
                        eventTime: AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long,
                    ) {
                        firstFrame.set(true)
                    }
                })
                player.impl.setMediaItem(MediaItem.fromUri(Uri.fromFile(mediaFile)))
                // Decode multiple frames before pausing, matching a user pausing active playback.
                player.impl.playWhenReady = true
                player.impl.prepare()
                onDispose { player.close() }
            }
            CompositionLocalProvider(LocalIsPreviewing provides false) {
                Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
                    VideoPlayer(
                        player,
                        Modifier.align(Alignment.Center)
                            .size(if (fullscreen) 280.dp else 160.dp, if (fullscreen) 200.dp else 90.dp)
                            .onGloballyPositioned { viewport = it.boundsInWindow() },
                    )
                    Button(
                        onClick = { fullscreen = !fullscreen },
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor.Magenta),
                        modifier = Modifier.align(Alignment.BottomCenter).testTag("fullscreen")
                            .onGloballyPositioned { buttonBounds = it.boundsInWindow() },
                    ) { Text("Fullscreen") }
                }
            }
        }
        waitUntil(timeoutMillis = 15_000) { firstFrame.get() }
        runOnIdle { assertEquals(Player.STATE_READY, player.impl.playbackState) }
        fun pauseAndReadPosition(): Long {
            runOnIdle { player.impl.pause() }
            // The playback thread can report its final milliseconds after pause() returns.
            var lastPosition = -1L
            var stableSince = SystemClock.uptimeMillis()
            waitUntil(timeoutMillis = 5_000) {
                val current = runOnIdle { player.impl.currentPosition }
                if (current != lastPosition) {
                    lastPosition = current
                    stableSince = SystemClock.uptimeMillis()
                }
                SystemClock.uptimeMillis() - stableSince >= 200
            }
            return lastPosition
        }
        waitUntil(timeoutMillis = 10_000) { runOnIdle { player.impl.currentPosition > 200 } }
        var position = pauseAndReadPosition()
        val surface = runOnIdle { checkNotNull(player.findAndroidVideoSurface()) }
        var captureIndex = 0

        fun assertPausedPicture() {
            waitForIdle()
            val bounds = runOnIdle { viewport }
            val controls = runOnIdle { buttonBounds }
            // FIT preserves the clip's 16:9 aspect within both viewports.
            val width = minOf(bounds.width, bounds.height * 16f / 9f)
            val height = width * 9f / 16f
            val left = bounds.center.x - width / 2
            val top = bounds.center.y - height / 2
            val captureFile = File(
                instrumentation.targetContext.getExternalFilesDir(null), "paused-resize-${captureIndex++}.png",
            )
            // Compose idleness and onRenderedFirstFrame do not wait for SurfaceFlinger to
            // present the frame. Allow its asynchronous transaction to complete while paused.
            waitUntil("paused video aligns with viewport at fullscreen=$fullscreen", timeoutMillis = 5_000) {
                val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
                try {
                    captureFile.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val chromePixel = screenshot.getPixel(
                        controls.center.x.roundToInt(), (controls.top + controls.height * 0.2f).roundToInt(),
                    )
                    val chromeVisible = Color.red(chromePixel) > 200 && Color.blue(chromePixel) > 200 &&
                            Color.green(chromePixel) < 40
                    chromeVisible && listOf(
                        Triple(0.2f, 0.2f, Color.RED),
                        Triple(0.8f, 0.2f, Color.GREEN),
                        Triple(0.2f, 0.8f, Color.BLUE),
                        Triple(0.8f, 0.8f, Color.YELLOW),
                    ).all { (x, y, expected) ->
                        val actual = screenshot.getPixel((left + x * width).roundToInt(), (top + y * height).roundToInt())
                        abs(Color.red(actual) - Color.red(expected)) < 40 &&
                                abs(Color.green(actual) - Color.green(expected)) < 40 &&
                                abs(Color.blue(actual) - Color.blue(expected)) < 40
                    }
                } finally {
                    screenshot.recycle()
                }
            }
            runOnIdle {
                assertSame(surface, player.findAndroidVideoSurface(), "Resizing must preserve the video surface")
                assertFalse(player.impl.playWhenReady)
                assertFalse(player.impl.isPlaying)
                assertEquals(position, player.impl.currentPosition, "Resizing must not seek or resume playback")
            }
        }

        assertPausedPicture()
        repeat(2) {
            onNodeWithTag("fullscreen").performClick()
            assertPausedPicture()
            onNodeWithTag("fullscreen").performClick()
            assertPausedPicture()
        }
        // Resume after resizing, then pause and repeat both directions.
        runOnIdle { player.impl.play() }
        waitUntil(timeoutMillis = 10_000) { runOnIdle { player.impl.currentPosition > position + 200 } }
        position = pauseAndReadPosition()
        onNodeWithTag("fullscreen").performClick()
        assertPausedPicture()
        onNodeWithTag("fullscreen").performClick()
        assertPausedPicture()
    }
}
