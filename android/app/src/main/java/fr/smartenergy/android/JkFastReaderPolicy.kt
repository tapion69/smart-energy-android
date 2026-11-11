package fr.smartenergy.android

/**
 * A warm-up read is an ordinary authenticated HTTP GET of an ESP32's BLE cache.
 * It does not initiate another BLE session or change JK firmware/poll cadence.
 */
object JkFastReaderPolicy {
    const val MAX_LIVE_AGE_MS = 15_000L

    fun isFresh(sampleAtMs: Long, nowMs: Long): Boolean =
        sampleAtMs > 0L && nowMs >= sampleAtMs &&
        nowMs - sampleAtMs <= MAX_LIVE_AGE_MS

    /** Pre-associated MAC identities must match before showing early data. */
    fun recognized(expectedMacs: Collection<String>, receivedMacs: Collection<String>): Boolean {
        val expected=expectedMacs.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        val received=receivedMacs.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        return expected.isNotEmpty() && received.isNotEmpty() &&
            received.all { it in expected } && received.any { it in expected }
    }

    /** An old cached sample may navigate the UI but must not be labelled live. */
    fun cacheStatus(sampleAtMs:Long, nowMs:Long):String =
        if(isFresh(sampleAtMs,nowMs)) "recent" else "expired"
}
