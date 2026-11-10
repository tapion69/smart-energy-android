package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class SunSpecReadOnlyTest {
    @Test fun detectsStandardSignatureAndModels() {
        assertTrue(SunSpecReadOnly.isSunS(intArrayOf(0x5375,0x6e53)))
        val calls=mutableListOf<Pair<Int,Int>>()
        val data=mapOf(
            40000 to intArrayOf(0x5375,0x6e53,1,65),
            40002 to intArrayOf(1,65),
            40069 to intArrayOf(101,50),
            40121 to intArrayOf(0xffff,0)
        )
        val result=SunSpecReadOnly.discoverModels(40000) { at,len ->
            calls+=at to len
            data[at] ?: error("Unexpected register: $at")
        }
        assertEquals(40000,result.baseRegister)
        assertEquals(listOf(1,101),result.models.map {it.modelId})
        assertTrue(calls.contains(40121 to 2))
    }

    @Test fun rejectsBrokenChainsAndNonPrivateEndpoints() {
        assertFalse(SunSpecReadOnly.isPrivateIPv4("8.8.8.8"))
        assertTrue(SunSpecReadOnly.isPrivateIPv4("192.168.1.53"))
        assertTrue(SunSpecReadOnly.isPrivateIPv4("172.17.0.2"))
        assertFalse(SunSpecReadOnly.isPrivateIPv4("localhost"))
        try {
            SunSpecReadOnly.discoverModels(40000) { _,_ ->
                intArrayOf(0x5375,0x6e53,1,0)
            }
            fail("Expected exception")
        } catch(_:IllegalArgumentException) {}
    }
}
