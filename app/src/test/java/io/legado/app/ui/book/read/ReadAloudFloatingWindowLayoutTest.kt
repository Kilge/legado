package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudFloatingWindowLayoutTest {

    @Test
    fun `bounds keep the overlay outside system bars`() {
        val bounds = ReadAloudFloatingWindowLayout.bounds(
            screenWidth = 1080,
            screenHeight = 2400,
            insetLeft = 0,
            insetTop = 96,
            insetRight = 0,
            insetBottom = 120,
            windowWidth = 462,
            windowHeight = 162,
            sideMargin = 54,
            bottomMargin = 84
        )

        assertEquals(ReadAloudFloatingWindowBounds(54, 564, 150, 2034), bounds)
    }

    @Test
    fun `position survives size changes as side and vertical percent`() {
        val portrait = ReadAloudFloatingWindowBounds(54, 564, 150, 2034)
        val landscape = ReadAloudFloatingWindowBounds(54, 1734, 96, 792)

        val storedSide = ReadAloudFloatingWindowLayout.sideForX(520, portrait)
        val storedPercent = ReadAloudFloatingWindowLayout.percentForY(1088, portrait)

        assertEquals(1, storedSide)
        assertEquals(50, storedPercent)
        assertEquals(1734, ReadAloudFloatingWindowLayout.xForSide(storedSide, landscape))
        assertEquals(444, ReadAloudFloatingWindowLayout.yForPercent(storedPercent, landscape))
    }

    @Test
    fun `undersized displays collapse to a safe single position`() {
        val bounds = ReadAloudFloatingWindowLayout.bounds(
            screenWidth = 300,
            screenHeight = 200,
            insetLeft = 30,
            insetTop = 40,
            insetRight = 30,
            insetBottom = 40,
            windowWidth = 400,
            windowHeight = 220,
            sideMargin = 20,
            bottomMargin = 30
        )

        assertEquals(ReadAloudFloatingWindowBounds(50, 50, 60, 60), bounds)
        assertEquals(0, ReadAloudFloatingWindowLayout.percentForY(1000, bounds))
    }
}
