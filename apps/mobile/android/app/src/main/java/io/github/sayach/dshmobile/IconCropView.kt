package io.github.sayach.dshmobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** The square viewport is also the exact exported area. No external crop intent is needed. */
internal class IconCropView(context: Context) : View(context) {
    enum class Mask { SQUARE, ROUND }
    var mask = Mask.SQUARE
        set(value) { field = value; invalidate() }
    var bitmap: Bitmap? = null
        private set
    var geometry: IconCropGeometry? = null
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val viewport = RectF()
    private var lastX = 0f
    private var lastY = 0f
    private val scales = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (viewport.width() <= 0f) return false
            geometry?.scale(detector.scaleFactor,
                (detector.focusX - viewport.centerX()) / viewport.width(),
                (detector.focusY - viewport.centerY()) / viewport.height())
            invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init { isFocusable = true; minimumHeight = (220 * resources.displayMetrics.density).toInt() }

    fun setImage(image: Bitmap) {
        bitmap = image
        geometry = IconCropGeometry(image.width, image.height)
        invalidate()
    }

    fun zoom(factor: Float) { geometry?.scale(factor); invalidate() }
    fun reset() { bitmap?.let { geometry = IconCropGeometry(it.width, it.height) }; invalidate() }

    private fun destination(bounds: RectF): RectF {
        val g = requireNotNull(geometry)
        val scale = bounds.width() / g.side
        return RectF(bounds.centerX() - g.centerX * scale, bounds.centerY() - g.centerY * scale,
            bounds.centerX() + (g.width - g.centerX) * scale, bounds.centerY() + (g.height - g.centerY) * scale)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height) * 0.9f
        viewport.set((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
        paint.color = context.getColor(R.color.app_surface_tinted)
        canvas.drawRect(viewport, paint)
        val image = bitmap ?: return
        canvas.save()
        val path = Path().apply {
            if (mask == Mask.ROUND) addOval(viewport, Path.Direction.CW)
            else addRoundRect(viewport, side * 0.18f, side * 0.18f, Path.Direction.CW)
        }
        canvas.clipPath(path)
        // A checkerboard keeps transparent, black and white artwork visible in either theme.
        val cell = side / 12
        for (row in 0..11) for (column in 0..11) {
            paint.color = if ((row + column) % 2 == 0) Color.LTGRAY else Color.WHITE
            canvas.drawRect(viewport.left + column * cell, viewport.top + row * cell,
                viewport.left + (column + 1) * cell, viewport.top + (row + 1) * cell, paint)
        }
        canvas.drawBitmap(image, null, destination(viewport), paint)
        canvas.restore()
    }

    fun export(): Bitmap {
        val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(requireNotNull(bitmap), null, destination(RectF(0f, 0f, 512f, 512f)), paint)
        return result
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || !isEnabled) return false
        parent.requestDisallowInterceptTouchEvent(true)
        scales.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scales.isInProgress && viewport.width() > 0) {
                val factor = requireNotNull(geometry).side / viewport.width()
                geometry?.pan((event.x - lastX) * factor, (event.y - lastY) * factor)
                invalidate()
            }
            MotionEvent.ACTION_UP -> { performClick(); parent.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        // Reset the anchor after pointer changes so lifting a pinch finger cannot jump the crop.
        val index = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0) 1 else 0
        lastX = event.getX(index)
        lastY = event.getY(index)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
