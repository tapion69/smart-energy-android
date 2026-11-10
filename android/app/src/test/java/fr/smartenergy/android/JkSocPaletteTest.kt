package fr.smartenergy.android

import org.junit.Assert.assertEquals
import org.junit.Test

class JkSocPaletteTest {
    @Test fun coloursFollowExplicitSocBoundaries() {
        assertEquals(JkSocPalette.LOW, JkSocPalette.color(0))
        assertEquals(JkSocPalette.LOW, JkSocPalette.color(29))
        assertEquals(JkSocPalette.MEDIUM, JkSocPalette.color(30))
        assertEquals(JkSocPalette.MEDIUM, JkSocPalette.color(59))
        assertEquals(JkSocPalette.HIGH, JkSocPalette.color(60))
        assertEquals(JkSocPalette.HIGH, JkSocPalette.color(100))
        assertEquals(JkSocPalette.UNKNOWN, JkSocPalette.color(null))
    }
}
