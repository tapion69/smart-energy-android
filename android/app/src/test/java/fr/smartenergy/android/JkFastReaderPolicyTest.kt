package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkFastReaderPolicyTest {
    @Test fun freshCachedValuesRemainLiveOnlyForFifteenSeconds() {
        assertFalse(JkFastReaderPolicy.isFresh(0,100))
        assertTrue(JkFastReaderPolicy.isFresh(1000,1001))
        assertTrue(JkFastReaderPolicy.isFresh(1000,16000))
        assertFalse(JkFastReaderPolicy.isFresh(1000,16001))
        assertFalse(JkFastReaderPolicy.isFresh(1000,999))
    }
    @Test fun fastGetMustShowOnlyAlreadyPairedBms() {
        val paired=listOf("AA:BB:CC:01:02:03","aa:bb:cc:01:02:04")
        assertTrue(JkFastReaderPolicy.recognized(paired,listOf("AA:BB:CC:01:02:04")))
        assertFalse(JkFastReaderPolicy.recognized(paired,listOf("AA:BB:CC:01:02:05")))
        assertFalse(JkFastReaderPolicy.recognized(emptyList(),listOf("AA:BB:CC:01:02:03")))
        assertFalse(JkFastReaderPolicy.recognized(paired,emptyList()))
        assertFalse(JkFastReaderPolicy.recognized(
            paired,listOf("AA:BB:CC:01:02:03","AA:BB:CC:01:02:05")))
    }
}
