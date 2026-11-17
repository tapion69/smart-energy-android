package fr.smartenergy.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoltronicWriteAccessPolicyTest {
    private fun reader(edit:Boolean=true,protocol:String="verified-write-v1") =
        JSONObject().put("product","smart-energy-voltronic-s3")
            .put("chip","esp32s3")
            .put("version","0.3.11-dev")
            .put("read_only",true)
            .put("parameter_edit_supported",edit)
            .put("parameter_edit_protocol",protocol)

    private fun health(enabled:Boolean?)=
        JSONObject().put("product","smart-energy-vpn-gateway")
            .put("vpn_connected",true)
            .put("vpn_only",true)
            .put("read_only",true)
            .put("version","0.3.11-dev-deye-relay-ready")
            .apply { if(enabled!=null)put("remote_parameter_writes_opt_in",enabled) }

    @Test fun optInAllowsEditsEvenWhenDefaultGetSaysReadOnly(){
        val decision=VoltronicWriteAccessPolicy.decide(reader(),health(true),viaVpn=true)
        assertTrue(decision.editable)
        assertEquals("",decision.reason)
    }
    @Test fun disabledNvsIsReportedWithoutFalselyBlamingInverter(){
        val decision=VoltronicWriteAccessPolicy.decide(reader(),health(false),viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("remote_parameter_writes_opt_in=false"))
        assertTrue(decision.reason.contains("Le firmware Voltronic supporte"))
    }
    @Test fun oldGatewayMissingFlagCannotEnableWrite(){
        val decision=VoltronicWriteAccessPolicy.decide(reader(),health(null),viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("n'est pas publiée"))
    }
    @Test fun intermittentHealthErrorNotMisreportedAsUsbOptInDisabled(){
        val decision=VoltronicWriteAccessPolicy.decide(
            reader(),gatewayError="HTTP 502",viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("non vérifiable"))
        assertFalse(decision.reason.contains("remote_parameter_writes_opt_in=false"))
    }
    @Test fun unsupportedOldVoltronicFirmwareCannotWrite(){
        val decision=VoltronicWriteAccessPolicy.decide(reader(edit=false),health(true),viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("Firmware Voltronic"))
    }
    @Test fun wrongProtocolCannotWriteEvenIfVpnAllowed(){
        assertFalse(VoltronicWriteAccessPolicy.decide(
            reader(protocol="unknown"),health(true),viaVpn=true).editable)
    }
    @Test fun localCapabilityIndependentFromVpn(){
        assertTrue(VoltronicWriteAccessPolicy.decide(reader()).editable)
        assertFalse(VoltronicWriteAccessPolicy.decide(reader(edit=false)).editable)
    }
    @Test fun spoofedGatewayHealthDenied(){
        val bad=health(true).put("product","not-a-gateway")
        val decision=VoltronicWriteAccessPolicy.decide(reader(),bad,viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("identité invalide"))
    }
}
