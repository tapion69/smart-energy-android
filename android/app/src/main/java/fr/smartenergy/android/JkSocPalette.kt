package fr.smartenergy.android

/** SOC colour rules shared by the global and each individual JK ring. */
object JkSocPalette {
    const val LOW = 0xFFFF6C76.toInt()
    const val MEDIUM = 0xFFFFB562.toInt()
    const val HIGH = 0xFF3BE4AD.toInt()
    const val UNKNOWN = 0xFF637789.toInt()

    fun color(soc: Int?): Int = when {
        soc == null || soc !in 0..100 -> UNKNOWN
        soc < 30 -> LOW
        soc < 60 -> MEDIUM
        else -> HIGH
    }
}
