package ro.upb.orarreader

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

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    private val drawMatrix = Matrix()
    private var currentScale = 1f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val desired = (currentScale * detector.scaleFactor).coerceIn(1f, 6f)
            val factor = desired / currentScale
            if (factor == 1f) return true
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            currentScale = desired
            constrainTranslation()
            imageMatrix = drawMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.05f) resetZoom() else {
                drawMatrix.postScale(2f, 2f, e.x, e.y)
                currentScale = 2f
                constrainTranslation()
                imageMatrix = drawMatrix
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetZoom() }
    }

    fun resetZoom() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val scale = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        val dx = (width - d.intrinsicWidth * scale) / 2f
        val dy = (height - d.intrinsicHeight * scale) / 2f
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        currentScale = 1f
        activePointerId = MotionEvent.INVALID_POINTER_ID
        imageMatrix = drawMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (!scaleDetector.isInProgress && currentScale > 1f) {
                        drawMatrix.postTranslate(x - lastX, y - lastY)
                        constrainTranslation()
                        imageMatrix = drawMatrix
                    }
                    lastX = x
                    lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                if (liftedId == activePointerId) {
                    val replacementIndex = if (event.actionIndex == 0) 1 else 0
                    if (replacementIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(replacementIndex)
                        lastX = event.getX(replacementIndex)
                        lastY = event.getY(replacementIndex)
                    } else activePointerId = MotionEvent.INVALID_POINTER_ID
                } else {
                    val index = event.findPointerIndex(activePointerId)
                    if (index >= 0) {
                        lastX = event.getX(index)
                        lastY = event.getY(index)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activePointerId = MotionEvent.INVALID_POINTER_ID
        }
        return true
    }

    private fun constrainTranslation() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0) return
        val bounds = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> width / 2f - bounds.centerX()
            bounds.left > 0f -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> height / 2f - bounds.centerY()
            bounds.top > 0f -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        if (dx != 0f || dy != 0f) drawMatrix.postTranslate(dx, dy)
    }
}
