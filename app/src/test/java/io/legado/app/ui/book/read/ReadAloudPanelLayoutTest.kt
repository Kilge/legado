package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudPanelLayoutTest {

    @Test
    fun centeredItemNeedsNoScroll() {
        assertEquals(
            0f,
            ReadAloudPanelLayout.centeredScrollDelta(
                viewportHeight = 1000,
                itemOffset = 450,
                itemSize = 100
            )
        )
    }

    @Test
    fun itemBelowCenterScrollsForward() {
        assertEquals(
            350f,
            ReadAloudPanelLayout.centeredScrollDelta(
                viewportHeight = 1000,
                itemOffset = 800,
                itemSize = 100
            )
        )
    }

    @Test
    fun itemAboveCenterScrollsBackward() {
        assertEquals(
            -350f,
            ReadAloudPanelLayout.centeredScrollDelta(
                viewportHeight = 1000,
                itemOffset = 100,
                itemSize = 100
            )
        )
    }
}
