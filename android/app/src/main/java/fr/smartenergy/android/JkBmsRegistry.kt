package fr.smartenergy.android

/**
 * The BLE scan is NOT the registry: already-connected JKs often no longer
 * advertise. The ESP32 /api/v1/bms list is authoritative for enabled BMSs.
 * A local-only MAC history allows users to re-enable a deliberately removed
 * device without waiting for it to appear in a new Bluetooth scan.
 */
object JkBmsRegistry {
    data class ScanItem(val mac: String, val name: String, val rssi: Int)
    data class Row(
        val mac: String,
        val name: String,
        val rssi: Int?,
        val registeredOnEsp: Boolean,
        val knownOnPhone: Boolean,
        val discoveredNow: Boolean
    )

    private val addressPattern=Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    fun validMac(mac: String): String? {
        val clean=mac.trim().uppercase(java.util.Locale.ROOT)
        return clean.takeIf { addressPattern.matches(it) }
    }

    fun normalized(macs: Iterable<String>): List<String> =
        macs.mapNotNull(::validMac).distinct()

    fun remember(previous: Iterable<String>, registered: Iterable<String>): List<String> =
        normalized(previous + registered).takeLast(64)

    fun merge(
        registered: Iterable<String>,
        remembered: Iterable<String>,
        scanned: List<ScanItem>
    ): List<Row> {
        val saved=normalized(registered)
        val history=normalized(remembered)
        val seen=linkedMapOf<String,ScanItem>()
        for (item in scanned) {
            val id=validMac(item.mac) ?: continue
            seen[id]=item
        }
        val ordered=linkedSetOf<String>()
        ordered.addAll(saved)
        ordered.addAll(history)
        ordered.addAll(seen.keys)
        return ordered.map { id ->
            val live=seen[id]
            Row(
                mac=id,
                name=live?.name?.takeIf { it.isNotBlank() }
                    ?: if(id in saved) "BMS enregistré" else "BMS connu",
                rssi=live?.rssi,
                registeredOnEsp=id in saved,
                knownOnPhone=id in history,
                discoveredNow=live!=null
            )
        }
    }
}
