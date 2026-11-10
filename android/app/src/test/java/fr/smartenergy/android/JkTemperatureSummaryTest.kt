package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkTemperatureSummaryTest {
    @Test fun maxTemperatureAcrossThreeReadersUsesCorrectCustomName() {
        val result=JkTemperatureSummary.hottest(listOf(
            JkTemperatureSample("ESP 1 · BMS 1", 23.6),
            JkTemperatureSample("Batterie atelier", 33.2),
            JkTemperatureSample("ESP 3 · BMS 2", 27.8)))
        assertNotNull(result)
        assertEquals("Batterie atelier",result!!.label)
        assertEquals(33.2,result.celsius,0.001)
    }

    @Test fun invalidReadingsAreIgnored() {
        val result=JkTemperatureSummary.hottest(listOf(
            JkTemperatureSample("Incorrect",Double.NaN),
            JkTemperatureSample("Corrupt",989.0),
            JkTemperatureSample("Valid",22.5),
            JkTemperatureSample("Missing",Double.POSITIVE_INFINITY)))
        assertEquals("Valid",result?.label)
        assertEquals(22.5,result!!.celsius,0.001)
    }

    @Test fun noLiveTemperatureHasNoWinner() {
        assertNull(JkTemperatureSummary.hottest(emptyList()))
        assertNull(JkTemperatureSummary.hottest(
            listOf(JkTemperatureSample("Incorrect",Double.NaN))))
    }
}
