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

    private fun legacyHealth(enabled:Boolean?) =
        JSONObject().put("product","smart-energy-vpn-gateway")
            .put("vpn_connected",true)
            .put("vpn_only",true)
            .put("read_only",true)
            .put("version","0.3.8-dev-vpn-discovery")
            .apply { if(enabled!=null)put("remote_parameter_writes_opt_in",enabled) }

    private fun confirmedHealth(supported:Boolean=true,confirmed:Boolean=true) =
        legacyHealth(false)
            .put("version","0.3.12-dev-confirmed-writes")
            .put("remote_parameter_writes_supported",supported)
            .put("remote_write_confirmation_required",confirmed)

    @Test fun updatedVpnDoesNotRequireNvsUsbOptIn(){
        val decision=VoltronicWriteAccessPolicy.decide(
            reader(),confirmedHealth(),viaVpn=true)
        assertTrue(decision.editable)
        assertEquals("",decision.reason)
    }
    @Test fun updatedVpnRefusesIfConfirmationProtocolNotAnnounced(){
        val decision=VoltronicWriteAccessPolicy.decide(
            reader(),confirmedHealth(confirmed=false),viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("confirmation"))
    }
    @Test fun unsupportedWriteCapabilityRemainsReadOnly(){
        val decision=VoltronicWriteAccessPolicy.decide(
            reader(),confirmedHealth(supported=false),viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("prise en charge"))
    }
    @Test fun oldGatewayWithUsbFlagFalseStillBlocks(){
        val decision=VoltronicWriteAccessPolicy.decide(
            reader(),legacyHealth(false),viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("0.3.12"))
    }
    @Test fun oldGatewayWithUsbFlagTrueIsCompatible(){
        assertTrue(VoltronicWriteAccessPolicy.decide(
            reader(),legacyHealth(true),viaVpn=true).editable)
    }
    @Test fun oldGatewayWithMissingFlagStillBlocks(){
        val decision=VoltronicWriteAccessPolicy.decide(
            reader(),legacyHealth(null),viaVpn=true)
        assertFalse(decision.editable)
    }
    @Test fun networkErrorCannotSilentlyEnableWrite(){
        val decision=VoltronicWriteAccessPolicy.decide(
            reader(),gatewayError="HTTP 502",viaVpn=true)
        assertFalse(decision.editable)
        assertTrue(decision.reason.contains("non vérifiable"))
    }
    @Test fun unsupportedOldVoltronicFirmwareCannotWrite(){
        val decision=VoltronicWriteAccessPolicy.decide(
            reader(edit=false),confirmedHealth(),viaVpn=true)
        assertFalse(decision.editable)
    }
    @Test fun wrongProtocolCannotWriteEvenOnNewestVpn(){
        assertFalse(VoltronicWriteAccessPolicy.decide(
            reader(protocol="unknown"),confirmedHealth(),viaVpn=true).editable)
    }
    @Test fun localCapabilityIndependentFromVpn(){
        assertTrue(VoltronicWriteAccessPolicy.decide(reader()).editable)
        assertFalse(VoltronicWriteAccessPolicy.decide(reader(edit=false)).editable)
    }
    @Test fun spoofedGatewayHealthDenied(){
        val bad=confirmedHealth().put("product","not-a-gateway")
        assertFalse(VoltronicWriteAccessPolicy.decide(
            reader(),bad,viaVpn=true).editable)
    }
}
