package com.photosync.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * An ImageView with pinch-to-zoom, double-tap-to-zoom and pan, backed by an
 * image matrix. Self-contained (no third-party dependency). While zoomed it
 * asks the parent (a ViewPager2) not to intercept touches so panning works,
 * releasing back to the pager once panned to an edge.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : AppCompatImageView(context, attrs, defStyle) {

    private val matrixValues = FloatArray(9)
    private val imageMatrix0 = Matrix()
    private var minScale = 1f
    private val maxScale = 4f

    private val scaleDetector = ScaleGestureDetector(context, object :
        ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var factor = detector.scaleFactor
            val current = currentScale()
            // Clamp so we never zoom past [minScale, maxScale].
            if (current * factor < minScale) factor = minScale / current
            if (current * factor > maxScale) factor = maxScale / current
            imageMatrix0.postScale(factor, factor, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = imageMatrix0
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object :
        GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale() > minScale * 1.05f) {
                resetToFit()
            } else {
                val target = minScale * 2.5f
                imageMatrix0.postScale(target / minScale, target / minScale, e.x, e.y)
                fixTranslation()
                imageMatrix = imageMatrix0
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
        ): Boolean {
            if (currentScale() <= minScale * 1.05f) return false
            imageMatrix0.postTranslate(-distanceX, -distanceY)
            fixTranslation()
            imageMatrix = imageMatrix0
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // Glide sets the drawable asynchronously; refit once it's in place.
        post { fitToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    private fun fitToView() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return

        val scale = min(width / dw, height / dh)
        minScale = scale
        imageMatrix0.reset()
        imageMatrix0.postScale(scale, scale)
        // Center the image.
        val dx = (width - dw * scale) / 2f
        val dy = (height - dh * scale) / 2f
        imageMatrix0.postTranslate(dx, dy)
        imageMatrix = imageMatrix0
    }

    private fun resetToFit() = fitToView()

    private fun currentScale(): Float {
        imageMatrix0.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Keeps the image within view bounds (centering when smaller than the view). */
    private fun fixTranslation() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrix0.mapRect(rect)

        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) {
            dx = (width - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < width) dx = width - rect.right
        }
        if (rect.height() <= height) {
            dy = (height - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < height) dy = height - rect.bottom
        }
        imageMatrix0.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // While zoomed in, claim the gesture so ViewPager2 doesn't page mid-pan.
        val zoomed = currentScale() > minScale * 1.05f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(zoomed)
            MotionEvent.ACTION_POINTER_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
