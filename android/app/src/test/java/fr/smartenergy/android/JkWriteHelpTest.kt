package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkWriteHelpTest {
    @Test fun neverHideMissingIdentity() {
        assertTrue(JkWriteHelp.reason("unverified_bms_identity").contains("bloquée"))
    }
    @Test fun staleSettingsAreExplained() {
        assertTrue(JkWriteHelp.reason("stale_or_missing_settings").contains("60 s"))
    }
    @Test fun rejectedWriteIsNotSuccessful() {
        assertTrue(JkWriteHelp.reason("timeout_unverified").contains("Aucune confirmation"))
    }
}
