package fr.smartenergy.android

import org.json.JSONObject

/** Read-only capability negotiation. Never guesses that a device is writable.
 * The VPN /health read_only=true flag is for default telemetry and MUST NOT
 * override the separate explicit remote_parameter_writes_opt_in flag. */
internal object VoltronicWriteAccessPolicy {
    data class Decision(val editable:Boolean, val reason:String)

    fun decide(
        readerInfo:JSONObject,
        gatewayHealth:JSONObject?=null,
        gatewayError:String?=null,
        viaVpn:Boolean=false
    ):Decision {
        val version=readerInfo.optString("version","inconnue").take(48)
        if(readerInfo.optString("product")!="smart-energy-voltronic-s3" ||
           readerInfo.optString("chip")!="esp32s3")
            return Decision(false,"Lecteur Voltronic non reconnu. Aucune commande envoyée.")
        if(!readerInfo.optBoolean("parameter_edit_supported",false) ||
           readerInfo.optString("parameter_edit_protocol")!="verified-write-v1")
            return Decision(false,"Firmware Voltronic ($version) : protocole d'écriture "+
                "vérifiée non annoncé. Mettre à jour le lecteur Voltronic, pas "+
                "modifier le VPN. Aucune commande envoyée.")
        if(!viaVpn)return Decision(true,"")
        if(gatewayError!=null)
            return Decision(false,"État des autorisations VPN non vérifiable : "+
                gatewayError.take(120)+". Réessayer la lecture du statut VPN ; "+
                "aucune commande envoyée.")
        if(gatewayHealth==null || gatewayHealth.optString("product")!=
           "smart-energy-vpn-gateway" ||
           !gatewayHealth.optBoolean("vpn_only") ||
           !gatewayHealth.optBoolean("vpn_connected"))
            return Decision(false,"Réponse /health VPN absente ou identité invalide. "+
                "Aucune commande envoyée.")
        val vpnVersion=gatewayHealth.optString("version","inconnue").take(48)
        if(!gatewayHealth.has("remote_parameter_writes_opt_in"))
            return Decision(false,"Passerelle VPN ($vpnVersion) : l'autorisation "+
                "remote_parameter_writes_opt_in n'est pas publiée. "+
                "Firmware VPN trop ancien ou incompatible. Aucune commande envoyée.")
        if(!gatewayHealth.optBoolean("remote_parameter_writes_opt_in",false))
            return Decision(false,"Passerelle VPN ($vpnVersion) : les écritures distantes "+
                "sont désactivées dans sa configuration NVS "+
                "(remote_parameter_writes_opt_in=false). "+
                "Le firmware Voltronic supporte les écritures ; "+
                "c'est l'autorisation du VPN qui bloque. "+
                "Aucune commande envoyée.")
        return Decision(true,"")
    }
}
