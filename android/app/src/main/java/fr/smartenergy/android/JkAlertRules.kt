package fr.smartenergy.android

import java.util.Locale

/** Pure notification rules: no ESP32 writes, no persistent telemetry or history. */
data class JkCellReading(val number: Int, val voltageV: Double)
data class JkProbeReading(val name: String, val celsius: Double)
data class JkBatteryReading(
    val identity: String,
    val name: String,
    val soc: Int?,
    val capacityAh: Double?,
    val cells: List<JkCellReading>,
    val probes: List<JkProbeReading>,
    val alarmActive: Boolean?,
    val fresh: Boolean
)

data class JkAlertThresholds(
    val alarmEnabled: Boolean = true,
    val globalSocEnabled: Boolean = false,
    val globalSocBelow: Int = 20,
    val bmsSocEnabled: Boolean = false,
    val bmsSocBelow: Int = 20,
    val cellHighEnabled: Boolean = false,
    val cellAboveV: Double = 3.500,
    val tempHighEnabled: Boolean = false,
    val tempAboveC: Double = 50.0
)

data class JkAlertEvent(val key: String, val title: String, val message: String)
data class JkAlertEvaluation(
    val active: List<JkAlertEvent>,
    /** Conditions verified with FRESH data; missing/offline conditions must not auto-reset. */
    val evaluatedKeys: Set<String>
)

object JkAlertRules {
    private fun fr(value: Double, decimals: Int) =
        String.format(Locale.FRANCE, "%." + decimals + "f", value)

    fun globalSoc(readings: List<JkBatteryReading>, expectedCount: Int): Int? {
        if (expectedCount <= 0 || readings.size != expectedCount ||
            readings.map { it.identity }.distinct().size != expectedCount ||
            readings.any { !it.fresh || it.soc == null || it.soc !in 0..100 }) return null
        val haveCapacity = readings.all {
            it.capacityAh != null && it.capacityAh.isFinite() && it.capacityAh > 0.0
        }
        val soc = if (haveCapacity) {
            val total = readings.sumOf { it.capacityAh!! }
            if (!total.isFinite() || total <= 0.0) return null
            readings.sumOf { it.soc!!.toDouble() * it.capacityAh!! } / total
        } else readings.map { it.soc!!.toDouble() }.average()
        return if (soc.isFinite()) soc.toInt().coerceIn(0, 100) else null
    }

    /** Strict comparisons: below SOC target; above cell/temperature targets. */
    fun evaluate(
        readings: List<JkBatteryReading>,
        expectedCount: Int,
        thresholds: JkAlertThresholds
    ): JkAlertEvaluation {
        val active = mutableListOf<JkAlertEvent>()
        val verified = mutableSetOf<String>()
        val socGlobal = globalSoc(readings, expectedCount)
        if (thresholds.globalSocEnabled && socGlobal != null) {
            val key = "soc-global"
            verified += key
            if (socGlobal < thresholds.globalSocBelow)
                active += JkAlertEvent(key, "SOC global bas : $socGlobal %",
                    "SOC global $socGlobal % sous le seuil de ${thresholds.globalSocBelow} %.")
        }
        for (bms in readings) {
            if (!bms.fresh || bms.identity.isBlank()) continue
            val title = bms.name.ifBlank { "BMS" }
            if (thresholds.alarmEnabled && bms.alarmActive != null) {
                val key = "alarm:${bms.identity}"
                verified += key
                if (bms.alarmActive)
                    active += JkAlertEvent(key, "Alarme BMS : $title",
                        "$title : une alarme est active. Consulte les détails dans Smart Energy.")
            }
            if (thresholds.bmsSocEnabled && bms.soc != null && bms.soc in 0..100) {
                val key = "soc:${bms.identity}"
                verified += key
                if (bms.soc < thresholds.bmsSocBelow)
                    active += JkAlertEvent(key, "SOC bas : $title",
                        "$title : ${bms.soc} % sous le seuil de ${thresholds.bmsSocBelow} %.")
            }
            if (thresholds.cellHighEnabled) {
                val highest = bms.cells.filter {
                    it.number in 1..32 && it.voltageV.isFinite() && it.voltageV in 1.5..5.0
                }.maxByOrNull { it.voltageV }
                if (highest != null) {
                    val key = "cell:${bms.identity}"
                    verified += key
                    if (highest.voltageV > thresholds.cellAboveV)
                        active += JkAlertEvent(key, "Tension cellule haute : $title",
                            "$title · Cellule ${highest.number} : ${fr(highest.voltageV,3)} V " +
                                "(seuil ${fr(thresholds.cellAboveV,3)} V).")
                }
            }
            if (thresholds.tempHighEnabled) {
                val hottest = bms.probes.filter {
                    it.celsius.isFinite() && it.celsius in -40.0..125.0
                }.maxByOrNull { it.celsius }
                if (hottest != null) {
                    val key = "temp:${bms.identity}"
                    verified += key
                    if (hottest.celsius > thresholds.tempAboveC)
                        active += JkAlertEvent(key, "Température haute : $title",
                            "$title · ${hottest.name} : ${fr(hottest.celsius,1)} °C " +
                                "(seuil ${fr(thresholds.tempAboveC,1)} °C).")
                }
            }
        }
        return JkAlertEvaluation(active, verified)
    }
}
