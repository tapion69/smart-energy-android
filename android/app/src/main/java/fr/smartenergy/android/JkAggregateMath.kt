package fr.smartenergy.android

/**
 * Aggregate live battery readings without treating missing devices as zero.
 * SOC remains capacity-weighted in the dashboard; current and power are sums.
 * Unknown totals must not be presented as whole-bank measurements.
 */
object JkAggregateMath {
    fun sumWhenComplete(samples: List<Double>, configuredCount: Int): Double? {
        if (configuredCount <= 0 || samples.size != configuredCount ||
            samples.any { !it.isFinite() }) return null
        return samples.sum()
    }
}
