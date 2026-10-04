package ro.upb.orarreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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

    private var locationPoint: PointF? = null
    private var locationAccuracyRadius = 0f
    private var locationHeadingDegrees: Float? = null

    private val accuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 66, 133, 244)
        style = Paint.Style.FILL
    }
    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 66, 133, 244)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val locationOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val locationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 133, 244)
        style = Paint.Style.FILL
    }

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = locationPoint ?: return
        val mapped = floatArrayOf(source.x, source.y)
        drawMatrix.mapPoints(mapped)
        val x = mapped[0]
        val y = mapped[1]

        if (locationAccuracyRadius > 0f) {
            val radius = drawMatrix.mapRadius(locationAccuracyRadius).coerceAtLeast(dp(4f))
            canvas.drawCircle(x, y, radius, accuracyFillPaint)
            canvas.drawCircle(x, y, radius, accuracyStrokePaint)
        }

        locationHeadingDegrees?.let { angle ->
            val headingPath = Path().apply {
                moveTo(dp(24f), 0f)
                lineTo(dp(5f), -dp(9f))
                lineTo(dp(5f), dp(9f))
                close()
            }
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(angle)
            canvas.drawPath(headingPath, locationOutlinePaint)
            canvas.save()
            canvas.scale(0.82f, 0.82f)
            canvas.drawPath(headingPath, locationPaint)
            canvas.restore()
            canvas.restore()
        }

        canvas.drawCircle(x, y, dp(10f), locationOutlinePaint)
        canvas.drawCircle(x, y, dp(7f), locationPaint)
    }

    fun setMapLocation(
        drawableX: Float,
        drawableY: Float,
        accuracyRadiusDrawable: Float,
        headingDegrees: Float?,
    ) {
        locationPoint = PointF(drawableX, drawableY)
        locationAccuracyRadius = accuracyRadiusDrawable.coerceAtLeast(0f)
        locationHeadingDegrees = headingDegrees
        invalidate()
    }

    fun clearMapLocation() {
        locationPoint = null
        locationAccuracyRadius = 0f
        locationHeadingDegrees = null
        invalidate()
    }

    fun focusOnDrawablePoint(
        drawableX: Float,
        drawableY: Float,
        relativeScale: Float = 2.5f,
    ) {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return

        val fitScale = min(
            width.toFloat() / d.intrinsicWidth,
            height.toFloat() / d.intrinsicHeight,
        )
        currentScale = relativeScale.coerceIn(1f, 6f)
        val totalScale = fitScale * currentScale

        drawMatrix.reset()
        drawMatrix.postScale(totalScale, totalScale)
        drawMatrix.postTranslate(
            width / 2f - drawableX * totalScale,
            height / 2f - drawableY * totalScale,
        )
        constrainTranslation()
        imageMatrix = drawMatrix
        invalidate()
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

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
