/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewTreeObserver
import android.window.SurfaceSyncGroup
import androidx.annotation.RequiresApi

/**
 * Commits a paused SurfaceView's resize together with the enclosing Compose frame.
 *
 * Media3's setEnableComposeSurfaceSyncWorkaround covers API 34 only. This applies the same
 * transaction synchronization to paused resizes on newer devices, without requesting another
 * decoded frame (which would require resuming or seeking).
 */
@RequiresApi(35)
internal class PausedSurfaceResizeSync(
    private val surfaceView: SurfaceView,
    private val isPaused: () -> Boolean,
) : SurfaceHolder.Callback, AutoCloseable {
    private var pendingSync: SurfaceSyncGroup? = null
    private var drawObserver: ViewTreeObserver? = null
    private val drawListener = ViewTreeObserver.OnDrawListener { finishSync() }
    private val registerSync = Runnable {
        val root = surfaceView.rootSurfaceControl ?: return@Runnable
        if (!surfaceView.holder.surface.isValid || !isPaused()) return@Runnable
        finishSync()
        val observer = surfaceView.viewTreeObserver
        if (drawObserver !== observer) {
            removeDrawListener()
            observer.addOnDrawListener(drawListener)
            drawObserver = observer
        }
        val group = SurfaceSyncGroup("animeko-paused-surface-resize")
        if (!group.add(root) {}) {
            group.markSyncReady()
            return@Runnable
        }
        pendingSync = group
        surfaceView.invalidate()
        SurfaceControl.Transaction().use { root.applyTransactionOnDraw(it) }
    }

    init {
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!isPaused()) return
        // Coalesce intermediate layouts during rotation, and register after AndroidView.layout.
        surfaceView.removeCallbacks(registerSync)
        surfaceView.post(registerSync)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        clear()
    }

    override fun close() {
        surfaceView.holder.removeCallback(this)
        clear()
    }

    private fun clear() {
        surfaceView.removeCallbacks(registerSync)
        finishSync()
        removeDrawListener()
    }

    private fun finishSync() {
        pendingSync?.markSyncReady()
        pendingSync = null
    }

    private fun removeDrawListener() {
        drawObserver?.takeIf { it.isAlive }?.removeOnDrawListener(drawListener)
        drawObserver = null
    }
}
