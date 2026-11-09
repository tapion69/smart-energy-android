package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkAggregateMathTest {
    @Test fun twoBmsPowerMustBeSummed() {
        assertEquals(-488.0, JkAggregateMath.sumWhenComplete(listOf(-250.0, -238.0), 2)!!, 0.001)
    }
    @Test fun twoBmsCurrentMustBeSummed() {
        assertEquals(-9.2, JkAggregateMath.sumWhenComplete(listOf(-4.5, -4.7), 2)!!, 0.001)
    }
    @Test fun threeBmsWithMixedSigns() {
        assertEquals(180.0, JkAggregateMath.sumWhenComplete(listOf(400.0, -170.0, -50.0), 3)!!, 0.001)
    }
    @Test fun partialDataMustNotPretendToBeTotal() {
        assertNull(JkAggregateMath.sumWhenComplete(listOf(-250.0), 2))
        assertNull(JkAggregateMath.sumWhenComplete(listOf(-250.0, Double.NaN), 2))
        assertNull(JkAggregateMath.sumWhenComplete(emptyList(), 0))
    }
}
