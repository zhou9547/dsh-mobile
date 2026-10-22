package io.github.sayach.dshmobile

import kotlin.math.max

/** Image coordinates under a square viewport. Independent of Android for boundary tests. */
internal class IconCropGeometry(val width: Int, val height: Int) {
    var centerX = width / 2f
        private set
    var centerY = height / 2f
        private set
    var zoom = 1f
        private set

    val side: Float get() = minOf(width, height) / zoom

    init { require(width > 0 && height > 0) }

    fun restore(x: Float, y: Float, scale: Float) {
        centerX = if (x.isFinite()) x else width / 2f
        centerY = if (y.isFinite()) y else height / 2f
        zoom = if (scale.isFinite()) scale.coerceIn(1f, 8f) else 1f
        clamp()
    }

    fun pan(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        centerX -= dx
        centerY -= dy
        clamp()
    }

    /** Preserve the image point under the pinch focus; focus is relative to viewport center. */
    fun scale(factor: Float, focusX: Float = 0f, focusY: Float = 0f) {
        if (!factor.isFinite() || factor <= 0f) return
        val oldSide = side
        zoom = (zoom * factor).coerceIn(1f, 8f)
        centerX += focusX * (oldSide - side)
        centerY += focusY * (oldSide - side)
        clamp()
    }

    private fun clamp() {
        val half = max(0.5f, side / 2f)
        centerX = centerX.coerceIn(half, width - half)
        centerY = centerY.coerceIn(half, height - half)
    }
}
