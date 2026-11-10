package fr.smartenergy.android

/** UI recovery timing only; the ESP32 separately enforces the BMS write limit. */
object JkEditRecoveryPolicy {
    const val SERVER_MINIMUM_MS = 15_000L
    private const val EXTRA_MARGIN_MS = 1_500L
    private const val AFTER_COOLDOWN_REFRESH_MS = 600L

    fun nextAllowedAt(acceptedAtMs:Long):Long =
        acceptedAtMs + SERVER_MINIMUM_MS + EXTRA_MARGIN_MS

    fun delayBeforeRefresh(nextAllowedAtMs:Long,nowMs:Long):Long =
        (nextAllowedAtMs-nowMs).coerceAtLeast(0L) + AFTER_COOLDOWN_REFRESH_MS
}
