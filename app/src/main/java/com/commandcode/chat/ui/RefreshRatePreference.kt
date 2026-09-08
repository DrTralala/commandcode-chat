package com.commandcode.chat.ui

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper

internal data class RefreshMode(val width: Int, val height: Int, val rate: Float)

internal fun highestRefreshRate(width: Int, height: Int, modes: List<RefreshMode>): Float =
    modes.filter { it.width == width && it.height == height && it.rate.isFinite() && it.rate > 0f }
        .maxOfOrNull { it.rate } ?: 0f

/** A display preference, not a promise of delivered FPS or an idle redraw loop. */
internal class RefreshRatePreference(private val activity: Activity) : DisplayManager.DisplayListener {
    private val manager = activity.getSystemService(DisplayManager::class.java)
    private var active = false
    private var previousRate = 0f

    fun start() {
        if (active) return
        active = true
        previousRate = activity.window.attributes.preferredRefreshRate
        manager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        update()
        activity.window.decorView.post { if (active) update() }
    }

    fun stop() {
        if (!active) return
        active = false
        manager.unregisterDisplayListener(this)
        setRate(previousRate)
    }

    private fun update() {
        if (!active) return
        val display = activity.display ?: return
        val current = display.mode
        val rate = highestRefreshRate(current.physicalWidth, current.physicalHeight,
            display.supportedModes.map { RefreshMode(it.physicalWidth, it.physicalHeight, it.refreshRate) })
        setRate(rate)
    }

    private fun setRate(rate: Float) {
        val attributes = activity.window.attributes
        if (attributes.preferredRefreshRate == rate) return
        attributes.preferredRefreshRate = rate
        activity.window.attributes = attributes
    }

    override fun onDisplayChanged(displayId: Int) { if (activity.display?.displayId == displayId) update() }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
}
