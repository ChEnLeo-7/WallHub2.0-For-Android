package com.wallhub.android.feature.detail

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.Button
import android.widget.ImageButton

private const val ICON_SHADOW_RADIUS_DP = 3f
private const val ICON_SHADOW_OFFSET_Y_DP = 2f
private const val ICON_SHADOW_COLOR = 0x99000000.toInt()

/** Renders a small downward shadow from the icon pixels, not the button touch bounds. */
class WallHubIconShadowImageButton
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ImageButton(context, attrs, defStyleAttr) {
        private val iconShadowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                setShadowLayer(
                    context.dpToPixels(ICON_SHADOW_RADIUS_DP),
                    0f,
                    context.dpToPixels(ICON_SHADOW_OFFSET_Y_DP),
                    ICON_SHADOW_COLOR,
                )
            }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val shadowLayer = canvas.saveLayer(null, iconShadowPaint)
            super.onDraw(canvas)
            canvas.restoreToCount(shadowLayer)
            super.onDraw(canvas)
        }
    }

/** Applies the same icon-level shadow to Media3's rewind and fast-forward controls. */
class WallHubIconShadowButton
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : Button(context, attrs, defStyleAttr) {
        private val iconShadowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                setShadowLayer(
                    context.dpToPixels(ICON_SHADOW_RADIUS_DP),
                    0f,
                    context.dpToPixels(ICON_SHADOW_OFFSET_Y_DP),
                    ICON_SHADOW_COLOR,
                )
            }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val shadowLayer = canvas.saveLayer(null, iconShadowPaint)
            super.onDraw(canvas)
            canvas.restoreToCount(shadowLayer)
            super.onDraw(canvas)
        }
    }

private fun Context.dpToPixels(dp: Float): Float = dp * resources.displayMetrics.density
