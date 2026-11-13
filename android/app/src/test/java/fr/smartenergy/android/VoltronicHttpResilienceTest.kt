package fr.smartenergy.android

import org.junit.Test
import org.junit.Assert.*
import java.net.SocketTimeoutException

class VoltronicHttpResilienceTest {
    @Test fun retainsOnlyTwoYoungMisses() {
        assertTrue(VoltronicHttpResilience.recentOnFailure(1,10000,800,18000))
        assertTrue(VoltronicHttpResilience.recentOnFailure(2,10000,800,18000))
        assertFalse(VoltronicHttpResilience.recentOnFailure(3,10000,800,18000))
        assertFalse(VoltronicHttpResilience.recentOnFailure(1,10000,800,33000))
        assertFalse(VoltronicHttpResilience.recentOnFailure(1,-1,800,18000))
    }
    @Test fun retryStopsAfterTwoMisses() {
        assertTrue(VoltronicHttpResilience.quickRetry(1))
        assertTrue(VoltronicHttpResilience.quickRetry(2))
        assertFalse(VoltronicHttpResilience.quickRetry(3))
    }
    @Test fun diagnosisDoesNotExposeAddress() {
        assertEquals("DELAI_HTTP",VoltronicHttpResilience.errorKind(
            SocketTimeoutException("192.168.1.63 secret")))
    }
}
