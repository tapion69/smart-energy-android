package fr.smartenergy.android

/**
 * Read-only JK02 bitmask labels.
 * Bit 4 is reserved/unused in the known JK02 layout and is ignored.
 * Unknown set bits are displayed, never silently treated as "no alarm".
 */
internal object JkAlarmLabels {
    val known = mapOf(
        0 to "Résistance fil", 1 to "Surchauffe MOS",
        2 to "Nombre de cellules incorrect",
        5 to "Surtension batterie", 6 to "Surintensité charge",
        7 to "Court-circuit charge", 8 to "Température charge haute",
        9 to "Température charge basse", 10 to "Communication interne",
        11 to "Sous-tension cellule", 12 to "Sous-tension batterie",
        13 to "Surintensité décharge", 14 to "Court-circuit décharge",
        15 to "Température décharge haute", 16 to "MOS charge anormal",
        17 to "MOS décharge anormal", 18 to "GPS déconnecté",
        19 to "Changement de mot de passe", 20 to "Échec activation décharge",
        21 to "Surchauffe batterie", 22 to "Anomalie sonde",
        23 to "Anomalie module PL", 24 to "Échec réarmement court-circuit",
        25 to "Surintensité décharge II", 26 to "Surintensité décharge III",
        27 to "Température décharge basse", 28 to "Verrouillage GPS"
    )

    fun activeBits(rawMask: Long): List<Int> =
        (0..31).filter { it != 4 && (rawMask and (1L shl it)) != 0L }

    fun label(bit: Int): String =
        known[bit] ?: "Alarme JK non identifiée (bit $bit)"
}
