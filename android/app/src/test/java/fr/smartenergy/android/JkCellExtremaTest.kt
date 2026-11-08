package fr.smartenergy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JkCellExtremaTest {
    @Test fun tiedMillivoltsSelectExactlyOneRedAndBlue() {
        val c = listOf(
            JkCellExtrema.Cell(1, 3295),
            JkCellExtrema.Cell(2, 3295),
            JkCellExtrema.Cell(3, 3297),
            JkCellExtrema.Cell(4, 3297),
            JkCellExtrema.Cell(5, 3295)
        )
        val result = JkCellExtrema.select(c)
        assertEquals(1, result.lowest)
        assertEquals(3, result.highest)
    }

    @Test fun trustedNativeIndexChoosesAmongEqualExtremes() {
        val c = listOf(
            JkCellExtrema.Cell(1, 3295), JkCellExtrema.Cell(2, 3295),
            JkCellExtrema.Cell(3, 3297), JkCellExtrema.Cell(4, 3297)
        )
        val selected = JkCellExtrema.select(c, reportedLowest = 2, reportedHighest = 4)
        assertEquals(2, selected.lowest)
        assertEquals(4, selected.highest)
        val bad = JkCellExtrema.select(c, reportedLowest = 4, reportedHighest = 2)
        assertEquals(1, bad.lowest)
        assertEquals(3, bad.highest)
    }

    @Test fun equalOrInvalidMeasurementsNeverPaintAllCells() {
        val allEqual = (1..16).map { JkCellExtrema.Cell(it, 3295) }
        val selected = JkCellExtrema.select(allEqual, 1, 16)
        assertNull(selected.lowest)
        assertNull(selected.highest)
        val invalid = listOf(JkCellExtrema.Cell(1, 0), JkCellExtrema.Cell(2, 3295))
        assertNull(JkCellExtrema.select(invalid).lowest)
    }

    @Test fun sparsePhysicalNumbersAreNotRenumbered() {
        val cells = listOf(JkCellExtrema.Cell(5, 3290), JkCellExtrema.Cell(15, 3310))
        val result = JkCellExtrema.select(cells)
        assertEquals(5, result.lowest)
        assertEquals(15, result.highest)
    }
}
