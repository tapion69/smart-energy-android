package fr.smartenergy.android

import org.junit.Assert.assertEquals
import org.junit.Test

class JkBmsTabOrderTest {
    @Test fun sortsNumbersAcrossMultipleEspWithoutDisplayingEsp() {
        val items = listOf(
            JkBmsTab("AA", "Batt-3", 2),
            JkBmsTab("BB", "Batt-1", 1),
            JkBmsTab("CC", "Batt-4", 2),
            JkBmsTab("DD", "Batt-2", 1)
        )
        assertEquals(listOf("Batt-1", "Batt-2", "Batt-3", "Batt-4"),
            JkBmsTabOrder.sorted(items).map { it.name })
    }
    @Test fun unnumberedNamesRemainStableAfterNumberedNames() {
        val items = listOf(
            JkBmsTab("A", "Maison", 1), JkBmsTab("B", "Batt 12", 2),
            JkBmsTab("C", "Garage", 3), JkBmsTab("D", "Batt 2", 1)
        )
        assertEquals(listOf("D", "B", "A", "C"),
            JkBmsTabOrder.sorted(items).map { it.mac })
    }
}
