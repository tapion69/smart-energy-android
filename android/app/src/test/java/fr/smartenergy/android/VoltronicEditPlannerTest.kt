package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class VoltronicEditPlannerTest {
    private fun p(name:String="battery_float_voltage")=
        VoltronicEditPlanner.prepare(1,name,55.2,54.0,54.0,true,true)

    @Test fun onlyConfirmedFreshBaselineCanPrepare() {
        val q=p()
        assertEquals("PBFT55.2",q.command)
        assertEquals("QPIRI",q.verificationQuery)
        try{VoltronicEditPlanner.prepare(1,"battery_float_voltage",55.2,54.0,54.0,true,false)
            fail("Must refuse unconfirmed")}catch(_:IllegalArgumentException){}
        try{VoltronicEditPlanner.prepare(1,"battery_float_voltage",55.2,54.0,54.0,false,true)
            fail("Must refuse stale")}catch(_:IllegalArgumentException){}
        try{VoltronicEditPlanner.prepare(1,"battery_float_voltage",55.2,54.0,53.1,true,true)
            fail("Must refuse changed baseline")}catch(_:IllegalArgumentException){}
    }
    @Test fun noClampingNoUnsafeAlternative() {
        try{VoltronicEditPlanner.prepare(1,"max_discharging_current",155.0,100.0,100.0,true,true)
            fail("step 10 required")}catch(_:IllegalArgumentException){}
        try{VoltronicEditPlanner.prepare(1,"max_discharging_current",250.0,100.0,100.0,true,true)
            fail("out of range")}catch(_:IllegalArgumentException){}
        assertEquals("PCVV55.2",VoltronicEditPlanner.prepare(
            1,"battery_bulk_voltage",55.2,54.0,54.0,true,true).command)
        assertEquals("PBCV55.2",VoltronicEditPlanner.prepare(
            1,"battery_recharge_voltage",55.2,54.0,54.0,true,true).command)
    }
    @Test fun setAndVerifyHavePriorityOverTelemetry() {
        val q=VoltronicPriorityTransactionQueue()
        q.enqueueRead("QPIGS")
        q.enqueueRead("QPIGS2")
        q.stage(p())
        assertEquals("PBFT55.2",q.next())
        assertNull(q.next()) // strictly single in-flight command
        q.onSetReply(true)
        assertEquals("QPIRI",q.next())
        assertNull(q.next())
        assertTrue(q.onReadback(55.2,true))
        assertEquals(VoltronicPriorityTransactionQueue.State.VERIFIED,q.state)
        assertEquals("QPIGS",q.next())
        assertEquals("QPIGS2",q.next())
    }
    @Test fun ackWithoutFreshReadbackNeverConfirms() {
        val q=VoltronicPriorityTransactionQueue()
        q.stage(p());q.next();q.onSetReply(true);q.next()
        assertFalse(q.onReadback(55.2,false))
        assertEquals(VoltronicPriorityTransactionQueue.State.UNCONFIRMED,q.state)
    }
    @Test fun mismatchDoesNotTryAnotherSetAutomatically() {
        val q=VoltronicPriorityTransactionQueue()
        q.stage(p());q.next();q.onSetReply(true);q.next()
        assertFalse(q.onReadback(54.0,true))
        assertNull(q.next())
    }
    @Test fun nakCannotTriggerReadbackOrSecondCandidate() {
        val q=VoltronicPriorityTransactionQueue()
        q.stage(p());q.next();q.onSetReply(false)
        assertEquals(VoltronicPriorityTransactionQueue.State.REJECTED,q.state)
        assertNull(q.next())
    }
}
