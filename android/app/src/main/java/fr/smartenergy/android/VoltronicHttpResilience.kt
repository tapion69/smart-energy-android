package fr.smartenergy.android

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Voltronic HTTP only: bounded recovery without touching JK transport. */
internal object VoltronicHttpResilience {
    const val OFFLINE_AFTER=3
    const val MAX_SAMPLE_AGE_MS=22000L

    fun recentOnFailure(failures:Int,sampleAtMs:Long,qpigsAgeMs:Long,nowMs:Long):Boolean {
        if(failures<1 || failures>=OFFLINE_AFTER ||
            sampleAtMs<=0 || qpigsAgeMs<0 || nowMs<sampleAtMs)return false
        return nowMs-sampleAtMs+qpigsAgeMs<=MAX_SAMPLE_AGE_MS
    }
    fun quickRetry(failures:Int):Boolean=failures in 1 until OFFLINE_AFTER

    // No exception messages: they may contain LAN addresses or credentials.
    fun errorKind(ex:Throwable):String=when(ex) {
        is VoltronicVpnHttpException -> when(ex.statusCode) {
            503 -> "VPN_INV_NON_CONFIGURE_503"
            502 -> "VPN_ESP_VOLTRONIC_INJOIGNABLE_502"
            401 -> "VPN_TOKEN_INCORRECT_401"
            403 -> "VPN_ACCES_REFUSE_403"
            404 -> "VPN_FIRMWARE_SANS_ROUTE_VOLTRONIC_404"
            else -> "VPN_REPONSE_HTTP_${ex.statusCode}"
        }
        is SocketTimeoutException -> "DELAI_HTTP"
        is ConnectException -> "CONNEXION_REFUSEE"
        is SocketException -> "SOCKET_RESEAU"
        is UnknownHostException -> "ADRESSE_INJOIGNABLE"
        is SecurityException -> "ACCES_RESEAU_REFUSE"
        is IllegalArgumentException -> "REPONSE_HTTP_INVALIDE"
        else -> "REQUETE_HTTP_ECHOUEE"
    }
}
