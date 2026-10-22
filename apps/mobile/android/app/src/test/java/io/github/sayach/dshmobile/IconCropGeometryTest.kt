package io.github.sayach.dshmobile

import org.junit.Assert.*
import org.junit.Test

class IconCropGeometryTest {
    @Test fun initialCropUsesTheShortEdgeAndStaysCentered() {
        val g = IconCropGeometry(1600, 900)
        assertEquals(900f, g.side, 0f)
        assertEquals(800f, g.centerX, 0f)
        assertEquals(450f, g.centerY, 0f)
    }

    @Test fun panNeverRevealsSpaceOutsideTheImage() {
        for ((width, height) in listOf(1600 to 900, 900 to 1600, 1 to 1)) {
            val g = IconCropGeometry(width, height)
            g.scale(4f)
            for (sign in listOf(-1, 1)) {
                g.pan(sign * 100000f, sign * 100000f)
                assertTrue(g.centerX - g.side / 2 >= 0)
                assertTrue(g.centerY - g.side / 2 >= 0)
                assertTrue(g.centerX + g.side / 2 <= width)
                assertTrue(g.centerY + g.side / 2 <= height)
            }
        }
    }

    @Test fun zoomIsBoundedAndPreservesOffCenterPinchFocus() {
        val g = IconCropGeometry(1000, 1000)
        g.scale(2f, 0.25f, -0.25f)
        assertEquals(625f, g.centerX, 0f)
        assertEquals(375f, g.centerY, 0f)
        assertEquals(750f, g.centerX + 0.25f * g.side, 0f)
        g.scale(100f)
        assertEquals(8f, g.zoom, 0f)
        g.scale(0.0001f)
        assertEquals(1f, g.zoom, 0f)
        assertEquals(500f, g.centerX, 0f)
    }

    @Test fun invalidStateCannotPoisonGeometry() {
        val g = IconCropGeometry(100, 200)
        g.restore(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        g.pan(Float.NaN, 1f)
        g.scale(Float.NaN)
        g.scale(-1f)
        assertEquals(50f, g.centerX, 0f)
        assertEquals(100f, g.centerY, 0f)
        assertEquals(1f, g.zoom, 0f)
    }
}
