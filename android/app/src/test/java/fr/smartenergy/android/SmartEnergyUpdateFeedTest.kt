package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class SmartEnergyUpdateFeedTest {
    @Test fun refuseNonOfficialDownloads() {
        for(url in listOf("http://github.com/tapion69/smart-energy-android/releases/download/v1/test.apk",
            "https://evil.tld/tapion69/smart-energy-android/releases/download/v1/app.apk",
            "https://github.com/other/repo/releases/download/v1/app.apk",
            "https://github.com/tapion69/smart-energy-android/releases/download/v1/app.apk?token=x"))
            assertFalse(SmartEnergyUpdateFeed.officialApkUrl(url))
        assertTrue(SmartEnergyUpdateFeed.officialApkUrl(
            "https://github.com/tapion69/smart-energy-android/releases/download/v1.0.1/app.apk"))
    }
    @Test fun onlyNewerPublishedSignedReleaseIsOffered() {
        val hash="1".repeat(64)
        val json="""{"schemaVersion":1,"product":"smart-energy-android","available":true,
        "versionName":"1.0.1","versionCode":101,
        "downloadUrl":"https://github.com/tapion69/smart-energy-android/releases/download/v1.0.1/app.apk",
        "sha256":"$hash","minimumSupportedVersionCode":40}"""
        assertEquals(101L,SmartEnergyUpdateFeed.parseAndroid(json,48)?.versionCode)
        assertNull(SmartEnergyUpdateFeed.parseAndroid(json,101))
        assertNull(SmartEnergyUpdateFeed.parseAndroid(json,20))
        assertNull(SmartEnergyUpdateFeed.parseAndroid(json.replace(hash,"bad"),48))
        assertNull(SmartEnergyUpdateFeed.parseAndroid(
            json.replace("\"available\":true","\"available\":false"),48))
    }
    @Test fun doNotDowngradeOrOfferAlreadyInstalledS3() {
        assertFalse(SmartEnergyUpdateFeed.newerFirmware(
            "0.3.2","0.3.2-dev-ota-gated-read-only"))
        assertFalse(SmartEnergyUpdateFeed.newerFirmware(
            "0.3.1","0.3.2-dev-ota-gated-read-only"))
        assertTrue(SmartEnergyUpdateFeed.newerFirmware(
            "0.3.3","0.3.2-dev-ota-gated-read-only"))
        assertFalse(SmartEnergyUpdateFeed.newerFirmware(
            "0.4.0","unknown"))
    }
    @Test fun noArbitraryFirmwareUrl() {
        val json="""{"schemaVersion":1,"product":"smart-voltronic-esp32","available":true,
        "version":"1.1.0","hardwareTargets":["esp32s3-n16r8"],
        "downloadUrl":"https://github.com/tapion69/smart-energy-android/releases/download/fw-1.1.0/s3.bin",
        "sha256":"${"a".repeat(64)}"}"""
        assertTrue(SmartEnergyUpdateFeed.parseVoltronic(json,"0.3.1")?.compatible==true)
        assertNull(SmartEnergyUpdateFeed.parseVoltronic(json,"1.1.0"))
    }
}
