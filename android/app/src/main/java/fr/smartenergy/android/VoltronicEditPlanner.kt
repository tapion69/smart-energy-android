package fr.smartenergy.android

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A hardware-independent transaction planner, deliberately NOT connected to
 * ESP32 UART or HTTP SET routes. It models the next opt-in write phase.
 *
 * Safety rules:
 * - explicit user confirmation, current native readback and matching previous value
 * - one candidate command only; no blind auto-fallback to a different SET opcode
 * - priority SET followed by priority QPIRI or QDOP
 * - ACK alone is never success; a *new* native readback is mandatory
 * - failure is unconfirmed, never silently retried
 */
internal object VoltronicEditPlanner {
    data class Plan(
        val slot:Int, val parameter:String, val command:String,
        val verificationQuery:String, val expected:Double,
        val oldValue:Double, val tolerance:Double,
        val candidateKey:String
    )
    private data class Spec(
        val query:String, val min:Double, val max:Double, val step:Double,
        val tolerance:Double, val prefix:String, val key:String
    )
    private val specs=mapOf(
        "battery_recharge_voltage" to Spec("QPIRI",12.0,65.0,0.1,0.11,"PBCV","RECHARGE_PBCV_BASE"),
        "battery_re_discharge_voltage" to Spec("QPIRI",12.0,65.0,0.1,0.11,"PBDV","REDISCHARGE_PBDV_BASE"),
        "battery_under_voltage" to Spec("QPIRI",12.0,65.0,0.1,0.11,"PSDV","UNDERVOLT_PSDV_BASE"),
        "battery_bulk_voltage" to Spec("QPIRI",12.0,65.0,0.1,0.11,"PCVV","BULK_PCVV_BASE"),
        "battery_float_voltage" to Spec("QPIRI",12.0,65.0,0.1,0.11,"PBFT","FLOAT_PBFT_BASE"),
        "max_charging_current" to Spec("QPIRI",0.0,200.0,1.0,1.0,"MNCHGC","MNCHGC_BASE"),
        "max_ac_charging_current" to Spec("QPIRI",0.0,200.0,1.0,1.0,"MUCHGC","MUCHGC_BASE"),
        // User-observed Voltronic discharge-current settings are 10 A steps.
        "max_discharging_current" to Spec("QPIRI",10.0,200.0,10.0,1.0,"PBATMAXDISC","PBATMAXDISC_BASE"),
        "battery_recharge_capacity_pct" to Spec("QDOP",5.0,95.0,1.0,1.0,"PBCC","PBCC_BASE"),
        "battery_redischarge_capacity_pct" to Spec("QDOP",10.0,100.0,1.0,1.0,"PBDC","PBDC_BASE"),
        "battery_under_capacity_pct" to Spec("QDOP",0.0,90.0,1.0,1.0,"PSDC","PSDC_BASE"),
        "output_source_priority" to Spec("QPIRI",0.0,2.0,1.0,0.01,"POP","POP_BASE"),
        "charger_source_priority" to Spec("QPIRI",1.0,3.0,1.0,0.01,"PCP","PCP_BASE"),
        "battery_type" to Spec("QPIRI",0.0,9.0,1.0,0.01,"PBT","PBT_BASE")
    )
    private val validBatteryCodes=setOf(0,1,2,3,5,6,8,9)
    fun supportedParameters():Set<String> = specs.keys

    fun prepare(
        slot:Int, parameter:String, expected:Double, oldValue:Double,
        currentNativeValue:Double?, hasFreshReadback:Boolean,
        explicitUserConfirmation:Boolean, parallelMachineId:Int=0
    ):Plan {
        require(slot in 1..3){"Invalid inverter slot"}
        require(explicitUserConfirmation){"User confirmation required"}
        require(hasFreshReadback && currentNativeValue!=null &&
            currentNativeValue.isFinite()){"Fresh QPIRI/QDOP required before writing"}
        val spec=specs[parameter] ?: throw IllegalArgumentException("Unsupported parameter")
        require(expected.isFinite() && oldValue.isFinite()){"Non-finite parameter"}
        require(expected in spec.min..spec.max){"Out of range: rejected, not clamped"}
        require(abs(expected/spec.step-(expected/spec.step).roundToInt())<0.0001){
            "Unsupported device step"
        }
        require(abs(currentNativeValue-oldValue)<=spec.tolerance){
            "Changed since preview; new readback required"
        }
        require(parameter!="battery_type" || expected.toInt() in validBatteryCodes){
            "Unknown battery type"
        }
        require(parallelMachineId in 0..9){"Invalid parallel machine ID"}
        val number=expected.roundToInt()
        val command=when(spec.prefix) {
            "POP","PCP","PBT" -> spec.prefix+number.toString().padStart(2,'0')
            "MNCHGC","MUCHGC" -> spec.prefix+parallelMachineId+
                number.toString().padStart(3,'0')
            "PBATMAXDISC","PBCC","PBDC","PSDC" ->
                spec.prefix+number.toString().padStart(3,'0')
            else -> spec.prefix+String.format(java.util.Locale.ROOT,"%.1f",expected)
        }
        return Plan(slot,parameter,command,spec.query,expected,oldValue,
            spec.tolerance,spec.key)
    }
}

/** Abstract serial priority queue: no network or UART link in this class. */
internal class VoltronicPriorityTransactionQueue {
    enum class State { IDLE, WAITING_SET_REPLY, READY_VERIFY, WAITING_READBACK,
        VERIFIED, REJECTED, UNCONFIRMED }
    private val regular=ArrayDeque<String>()
    private var plan:VoltronicEditPlanner.Plan?=null
    var state=State.IDLE
        private set
    var lastResult:String=""
        private set
    fun enqueueRead(query:String) {
        require(query.startsWith("Q")){"Regular queue cannot carry SET"}
        if(regular.size<64)regular.addLast(query)
    }
    fun stage(p:VoltronicEditPlanner.Plan) {
        require(state==State.IDLE || state==State.VERIFIED ||
                state==State.REJECTED || state==State.UNCONFIRMED){
            "A transaction is already running"
        }
        plan=p;state=State.IDLE;lastResult="pending"
    }
    /** Returns the only next command; caller must wait for its response. */
    fun next():String? {
        val p=plan
        if(p!=null && state==State.IDLE){
            state=State.WAITING_SET_REPLY
            return p.command
        }
        if(p!=null && state==State.READY_VERIFY) {
            state=State.WAITING_READBACK
            return p.verificationQuery
        }
        if(state==State.WAITING_SET_REPLY || state==State.WAITING_READBACK)return null
        return if(regular.isNotEmpty())regular.removeFirst() else null
    }
    fun onSetReply(ack:Boolean) {
        require(state==State.WAITING_SET_REPLY)
        state=if(ack)State.READY_VERIFY else State.REJECTED
        if(!ack){lastResult="NAK or timeout";plan=null}
    }
    fun onReadback(value:Double?, verifiedAfterWrite:Boolean):Boolean {
        require(state==State.WAITING_READBACK)
        val p=requireNotNull(plan)
        val confirmed=verifiedAfterWrite && value!=null && value.isFinite() &&
            abs(value-p.expected)<=p.tolerance
        state=if(confirmed)State.VERIFIED else State.UNCONFIRMED
        lastResult=if(confirmed)"Readback matched" else "No matching fresh readback"
        plan=null
        return confirmed
    }
    fun abortUnconfirmed() {
        if(state==State.WAITING_SET_REPLY || state==State.WAITING_READBACK ||
            state==State.READY_VERIFY) {
            state=State.UNCONFIRMED;lastResult="Interrupted; inverter state unknown";plan=null
        }
    }
}
