package fr.smartenergy.android
import org.junit.Assert.*
import org.junit.Test

class VoltronicVpnReadOnlyClientTest {
    @Test fun tailnetOnly() {
        assertTrue(VoltronicVpnReadOnlyClient.isTailnetIpv4("100.64.0.1"))
        assertTrue(VoltronicVpnReadOnlyClient.isTailnetIpv4("100.127.255.254"))
        for(x in listOf("100.128.0.1","192.168.1.63","localhost","100.64.0.1:80",
            "100.064.1.1","100.64.1.01","8.8.8.8"))
            assertFalse(x,VoltronicVpnReadOnlyClient.isTailnetIpv4(x))
    }
    @Test fun routesAreFixed() {
        assertEquals("/api/v1/voltronic/1/telemetry",
            VoltronicVpnReadOnlyClient.fixedPath(1,false))
        assertEquals("/api/v1/voltronic/3/details",
            VoltronicVpnReadOnlyClient.fixedPath(3,true))
        try {VoltronicVpnReadOnlyClient.fixedPath(4,false);fail()}
        catch(_:IllegalArgumentException){}
    }
}
