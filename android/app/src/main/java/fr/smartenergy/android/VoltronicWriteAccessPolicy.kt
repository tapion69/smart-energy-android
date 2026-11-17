package fr.smartenergy.android

import org.json.JSONObject

/** Capability negotiation for authenticated, explicitly confirmed edits.
 * VPN health read_only=true describes default telemetry, not semantic SETs.
 * Current VPN exposes remote_parameter_writes_supported; old USB-gated
 * gateways remain locked if their legacy opt-in was not enabled. */
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
        // Latest firmware removes the second USB unlock. Readers still
        // verify old/current values and acknowledge a confirmed change.
        if(gatewayHealth.has("remote_parameter_writes_supported")) {
            if(!gatewayHealth.optBoolean("remote_parameter_writes_supported",false))
                return Decision(false,"VPN ($vpnVersion) : la passerelle n'annonce "+
                    "pas la prise en charge des modifications confirmées. "+
                    "Aucune commande envoyée.")
            if(!gatewayHealth.optBoolean("remote_write_confirmation_required",false))
                return Decision(false,"VPN ($vpnVersion) : la confirmation "+
                    "obligatoire n'est pas annoncée. Aucune commande envoyée.")
            return Decision(true,"")
        }
        // Older firmware still enforces its stored physical USB opt-in.
        if(!gatewayHealth.optBoolean("remote_parameter_writes_opt_in",false))
            return Decision(false,"Passerelle VPN ($vpnVersion) : ancien firmware "+
                "avec autorisation USB désactivée. Installe le firmware VPN "+
                "0.3.12 (ou plus récent) en APP-ONLY pour supprimer cette "+
                "activation supplémentaire. Aucune commande envoyée.")
        return Decision(true,"")
    }
}
