package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkNearbyWifiPolicyTest {
    @Test fun onlySetupSsidIsIncludedAndDuplicateScanCollapsed() {
        val items=JkNearbyWifiPolicy.candidates(listOf(
            JkNearbyWifi("HOME",-48,100),
            JkNearbyWifi("SmartJK-Setup-A1B2C3",-57,2_000),
            JkNearbyWifi("SmartJK-Setup-A1B2C3",-70,20_000),
            JkNearbyWifi("SmartJK-Setup-123ABC",-43,3_000)
        ))
        assertEquals(2,items.size)
        assertEquals("SmartJK-Setup-123ABC",items.first().ssid)
        assertEquals(2_000,items.last().ageMs)
    }

    @Test fun olderAndroidScanMustNeverBeTrustedAsCurrent() {
        val old=JkNearbyWifi("SmartJK-Setup-A1B2C3",-50,90_000)
        assertFalse(JkNearbyWifiPolicy.isFresh(old))
        assertTrue(JkNearbyWifiPolicy.isFresh(old.copy(ageMs=1_000)))
    }

    @Test fun firstReaderIsNeverAutoAssignedToSecondSlot() {
        val saved=mapOf(1 to "SmartJK-Setup-A1B2C3", 2 to "")
        assertEquals(1,JkNearbyWifiPolicy.assignedToAnotherSlot(
            "SmartJK-Setup-A1B2C3",2,saved))
        assertNull(JkNearbyWifiPolicy.assignedToAnotherSlot(
            "SmartJK-Setup-123ABC",2,saved))
        val label=JkNearbyWifiPolicy.label(
            JkNearbyWifi("SmartJK-Setup-A1B2C3",-60,1500),2,saved)
        assertTrue(label.contains("déjà enregistré sur ESP 1"))
    }
}
