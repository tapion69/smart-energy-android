package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkDashboardVisibilityTest {
    @Test fun homeDisplaysGlobalForAnyReaderCount() {
        assertTrue(JkDashboardVisibility.showFleet(""))
    }

    @Test fun selectingABmsAlwaysHidesGlobalRegardlessOfReader() {
        assertFalse(JkDashboardVisibility.showFleet("C8:47:80:11:22:33"))
        assertFalse(JkDashboardVisibility.showFleet("AA:BB:CC:01:02:03"))
    }
}
