package fr.smartenergy.android

/** Wi-Fi scan results can be cached/throttled by Android. Never silently
 * auto-associate an ESP to the currently selected reader slot. */
data class JkNearbyWifi(
    val ssid:String,
    val signalDbm:Int,
    val ageMs:Long
)

object JkNearbyWifiPolicy {
    private val setupName=Regex("^SmartJK-Setup-[A-Fa-f0-9]{6}$")
    const val MAX_FRESH_AGE_MS=30_000L

    fun candidates(results:List<JkNearbyWifi>):List<JkNearbyWifi> =
        results.filter { setupName.matches(it.ssid) }
            .groupBy { it.ssid.uppercase() }
            .values.mapNotNull { group ->
                group.minWithOrNull(compareBy<JkNearbyWifi> { it.ageMs.coerceAtLeast(0L) }
                    .thenByDescending { it.signalDbm })
            }
            .sortedWith(compareBy<JkNearbyWifi> {
                !isFresh(it)
            }.thenByDescending { it.signalDbm })

    fun isFresh(item:JkNearbyWifi):Boolean =
        item.ageMs >= 0 && item.ageMs <= MAX_FRESH_AGE_MS

    fun assignedToAnotherSlot(ssid:String,selectedSlot:Int,
                              slots:Map<Int,String>):Int? =
        slots.entries.firstOrNull {
            it.key!=selectedSlot && it.value.equals(ssid,ignoreCase=true)
        }?.key

    /** Selection only, never pairing or changing stored credentials.
     * Auto-select when exactly one fresh, unclaimed device was found.
     * Existing ESP1 is never silently treated as a new ESP2.
     */
    fun uniqueFreshUnclaimed(results:List<JkNearbyWifi>,
                             activeSlot:Int,slots:Map<Int,String>):JkNearbyWifi? {
        val suitable=candidates(results).filter {
            isFresh(it) && assignedToAnotherSlot(it.ssid,activeSlot,slots)==null
        }
        return suitable.singleOrNull()
    }

    fun label(item:JkNearbyWifi,activeSlot:Int,slots:Map<Int,String>):String {
        val owner=slots.entries.firstOrNull {
            it.value.equals(item.ssid,ignoreCase=true)
        }?.key
        val identity=when {
            owner==null -> "nouveau / non associé"
            owner==activeSlot -> "déjà enregistré sur ESP $owner"
            else -> "déjà enregistré sur ESP $owner — ne pas réassocier"
        }
        val age=if(isFresh(item)) "" else " · ANCIEN résultat Wi-Fi"
        return "${item.ssid} · ${item.signalDbm} dBm · $identity$age"
    }
}
