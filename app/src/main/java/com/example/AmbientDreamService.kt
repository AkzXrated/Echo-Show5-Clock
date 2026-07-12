package com.example

import android.service.dreams.DreamService
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout

class AmbientDreamService : DreamService() {

    private var surfaceView: AmbientSurfaceView? = null
    private var repository: WeatherRepository? = null

    companion object {
        private const val TAG = "AmbientDreamService"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "AmbientDreamService attached to window")

        isInteractive = true
        isFullscreen = true

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val repo = WeatherRepository(this)
        repository = repo

        val sfView = AmbientSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        surfaceView = sfView

        val state = repo.weatherState.value
        if (state != null) {
            sfView.weatherState = state
        }

        root.addView(sfView)
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Log.d(TAG, "AmbientDreamService dreaming started")
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        Log.d(TAG, "AmbientDreamService dreaming stopped")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "AmbientDreamService detached from window")
        surfaceView = null
        repository = null
    }
}
