package com.wallhub.android.feature.detail

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import com.wallhub.android.R

/**
 * Native clipping seam between Compose media surfaces and Media3's View hierarchy.
 *
 * Compose clipping cannot constrain native children such as the media controller, so the host owns the
 * Android outline and child clipping contract. Inline presentation clips to a rounded rectangle;
 * fullscreen presentation disables the outline so the player can fill the window without card corners.
 */
internal class WallHubNativeMediaFrame(
    context: Context,
) : FrameLayout(context) {
    private var inlineCornerRadiusPx: Float? = null
    private var attachedPlayerView: PlayerView? = null

    private val outlineProviderDelegate =
        object : ViewOutlineProvider() {
            override fun getOutline(
                view: View,
                outline: Outline,
            ) {
                val radius = inlineCornerRadiusPx
                if (radius != null && radius > 0f) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                } else {
                    outline.setRect(0, 0, view.width, view.height)
                }
            }
        }

    init {
        id = R.id.wallhub_native_media_host
        clipChildren = true
        clipToPadding = true
        outlineProvider = outlineProviderDelegate
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidateOutline() }
    }

    fun playerView(): PlayerView? = attachedPlayerView

    fun attach(playerView: PlayerView) {
        if (attachedPlayerView === playerView && playerView.parent === this) return
        (playerView.parent as? FrameLayout)?.removeView(playerView)
        removeAllViews()
        playerView.clipChildren = true
        playerView.clipToPadding = true
        addView(
            playerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        attachedPlayerView = playerView
        clipToOutline = inlineCornerRadiusPx != null && inlineCornerRadiusPx!! > 0f
        invalidateOutline()
    }

    fun setInlineCornerRadius(cornerRadiusPx: Float?) {
        this.inlineCornerRadiusPx = cornerRadiusPx
        clipToOutline = cornerRadiusPx != null && cornerRadiusPx > 0f
        invalidateOutline()
    }
}
