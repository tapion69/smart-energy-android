package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkEditRecoveryPolicyTest {
    @Test fun neverAllowsSecondWriteBeforeFirmwareSafetyWindow() {
        val accepted=20_000L
        val next=JkEditRecoveryPolicy.nextAllowedAt(accepted)
        assertTrue(next-accepted>=15_000L)
        assertEquals(15_200L,next-accepted)
    }

    @Test fun recoveryReReadsAfterCooldownWithoutRestart() {
        val ready=JkEditRecoveryPolicy.nextAllowedAt(0L)
        assertEquals(15_300L,JkEditRecoveryPolicy.delayBeforeRefresh(ready,0L))
        assertEquals(100L,JkEditRecoveryPolicy.delayBeforeRefresh(ready,ready+3_000L))
    }
}
