package fr.smartenergy.android

/**
 * Human-readable, fail-closed reasons from the ESP32 pilot write guard.
 * This never authorizes an edit; it only explains why the ESP32 refused it.
 */
object JkWriteHelp {
    fun reason(key: String): String = when (key) {
        "bms_not_live" -> "BMS déconnecté ou mesures trop anciennes"
        "bms_alarm_active" -> "Alarme BMS active : écriture interdite"
        "unverified_bms_identity" ->
            "Identité BMS non confirmée (trame JK 0x03) : écriture bloquée"
        "stale_or_missing_settings" ->
            "Réglages datant de plus de 60 s : relancer la lecture des paramètres"
        "parameter_not_readable" -> "Paramètre absent de la trame 0x01 validée"
        "stale_expected_value" ->
            "Valeur affichée différente de la valeur actuelle : rafraîchir les paramètres"
        "write_already_pending" -> "Une vérification de modification est déjà en cours"
        "write_rate_limit_15s" -> "Attendre 15 secondes avant une nouvelle modification"
        "bms_not_configured" -> "BMS absent de la sélection"
        "unsupported_or_invalid_parameter", "parameter_not_allowlisted" ->
            "Paramètre non autorisé ou valeur hors des limites"
        "write_permission_expired_or_not_armed" ->
            "Confirmation de modification expirée : recommencer après vérification"
        "critical_protection_confirmation_required" ->
            "Confirmation spéciale requise pour modifier une protection du BMS"
        "safety_relationship_failed" ->
            "Nouvelle valeur incompatible avec une autre limite de protection du BMS"
        "already_at_target" ->
            "La valeur demandée est déjà enregistrée sur ce BMS"
        "send_failed" -> "La commande BLE n'a pas été transmise"
        "timeout_unverified" -> "Aucune confirmation de la valeur par le BMS"
        "mismatch" -> "Le BMS renvoie une valeur différente de celle demandée"
        "link_lost_unverified" -> "Liaison BLE perdue avant la vérification"
        else -> if (key.isBlank()) "Motif non communiqué" else key
    }
}
