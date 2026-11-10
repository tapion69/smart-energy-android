package fr.smartenergy.android

/** Preferences shared by Android settings and the opt-in monitor. */
object JkAlertPreferences {
    const val MONITOR = "bms_alarm_notifications"
    const val ALARM = "alert_bms_alarm_enabled"
    const val GLOBAL_SOC = "alert_global_soc_enabled"
    const val GLOBAL_SOC_THRESHOLD = "alert_global_soc_below"
    const val BMS_SOC = "alert_bms_soc_enabled"
    const val BMS_SOC_THRESHOLD = "alert_bms_soc_below"
    const val CELL_HIGH = "alert_cell_high_enabled"
    const val CELL_HIGH_THRESHOLD = "alert_cell_high_above_v"
    const val TEMP_HIGH = "alert_temp_high_enabled"
    const val TEMP_HIGH_THRESHOLD = "alert_temp_high_above_c"

    /** French decimal comma accepted; reject malformed and non-finite numbers. */
    fun number(value: String): Double? =
        value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    fun soc(value: String): Int? = number(value)?.takeIf {
        it in 1.0..100.0 && it % 1.0 == 0.0
    }?.toInt()
    fun cell(value: String): Double? = number(value)?.takeIf { it in 2.0..4.5 }
    fun temperature(value: String): Double? = number(value)?.takeIf { it in 0.0..120.0 }
}
