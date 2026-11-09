package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkBmsRegistryTest {
    private val mac1="C8:47:80:29:84:50"
    private val mac2="C8:47:80:2B:31:F6"
    private val mac3="C8:47:80:11:22:33"

    @Test fun registeredBmsRemainVisibleWithoutAdvertising() {
        val rows=JkBmsRegistry.merge(listOf(mac1,mac2),emptyList(),emptyList())
        assertEquals(listOf(mac1,mac2),rows.map { it.mac })
        assertTrue(rows.all { it.registeredOnEsp })
        assertTrue(rows.none { it.discoveredNow })
    }
    @Test fun removedBmsIsRememberedAndCanBeCheckedAgainLater() {
        val memory=JkBmsRegistry.remember(listOf(mac1,mac2),listOf(mac1))
        val rows=JkBmsRegistry.merge(listOf(mac1),memory,emptyList())
        val former=rows.single { it.mac==mac2 }
        assertFalse(former.registeredOnEsp)
        assertTrue(former.knownOnPhone)
        assertFalse(former.discoveredNow)
    }
    @Test fun registeredAndDiscoveredAreNotDuplicated() {
        val rows=JkBmsRegistry.merge(listOf(mac1),
            listOf(mac1.lowercase()),
            listOf(JkBmsRegistry.ScanItem(mac1.lowercase(),"JK V19",-58),
                   JkBmsRegistry.ScanItem(mac3,"Other",-69)))
        assertEquals(2,rows.size)
        assertEquals("JK V19",rows.single { it.mac==mac1 }.name)
        assertEquals(-58,rows.single { it.mac==mac1 }.rssi)
    }
    @Test fun rejectInvalidMacsAndNeverKeepScannedDevicesInHistoryAutomatically() {
        assertEquals(listOf(mac1),
            JkBmsRegistry.normalized(listOf("bad",mac1.lowercase(),mac1)))
        val rows=JkBmsRegistry.merge(emptyList(),emptyList(),
            listOf(JkBmsRegistry.ScanItem(mac3,"Nearby",-45)))
        assertTrue(rows.single().discoveredNow)
        assertFalse(rows.single().knownOnPhone)
    }
    @Test fun emptyRegisteredListIsLegalAndAllPreviousCanBeRestored() {
        val old=JkBmsRegistry.remember(emptyList(),listOf(mac1,mac2,mac3))
        val rows=JkBmsRegistry.merge(emptyList(),old,emptyList())
        assertEquals(3,rows.size)
        assertTrue(rows.none { it.registeredOnEsp })
    }
}
