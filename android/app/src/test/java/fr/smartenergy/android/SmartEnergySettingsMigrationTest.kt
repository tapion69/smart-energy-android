package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class SmartEnergySettingsMigrationTest {
    @Test fun onlyNonSecretSettingsAreAllowed() {
        assertEquals("string",SmartEnergySettingsMigration.typeFor("reader_3_local_ip"))
        assertEquals("string",SmartEnergySettingsMigration.typeFor("bms_name_AABBCCDDEEFF"))
        assertEquals("boolean",SmartEnergySettingsMigration.typeFor("remote_voltronic_enabled"))
        for(key in listOf("pairing_code_cipher","reader_1_pairing_code_cipher",
            "remote_gateway_token_cipher","premium_voltronic_code_cipher",
            "premium_voltronic_3_code_cipher","tailscale_key","wifi_password")) {
            assertNull(key,SmartEnergySettingsMigration.typeFor(key))
        }
    }
    @Test fun checksVersionFormatTypesAndRejectedSecrets() {
        val good="""{"format":"smart-energy-settings-migration","schema":1,
        "settings":{"device_id":"smartjk-123456","reader_2_local_ip":"192.168.1.42",
        "paired_bms_json":"[]",
        "active_reader_slot":2,"remote_voltronic_enabled":true}}"""
        val count=SmartEnergySettingsMigration.preview(good).first
        assertEquals(5,count)
        val bad=good.replace("\"device_id\"","\"pairing_code_cipher\"")
        try {
            SmartEnergySettingsMigration.preview(bad)
            fail("Must reject ciphertext field")
        }catch(_:IllegalArgumentException){}
        try {
            SmartEnergySettingsMigration.preview(good.replace("\"schema\":1","\"schema\":2"))
            fail("Must reject schema mismatch")
        }catch(_:IllegalArgumentException){}
    }
}
