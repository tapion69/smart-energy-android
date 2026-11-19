"""Source guards: opt-in LAN for inverters must not reroute JK or persist secrets.

Physical-network proof is the session's exact Android Network object, not
mere presence of Wi-Fi on a foreign network.
"""
from pathlib import Path
root=Path(__file__).resolve().parents[2]
a=(root/"android/app/src/main/java/fr/smartenergy/android/MainActivity.kt").read_text(encoding="utf-8")
html=(root/"android/app/src/main/assets/energy_dashboard_v3.html").read_text(encoding="utf-8")

assert "private var inverterLanSessionNetwork:android.net.Network?=null" in a
assert "private var inverterLanSessionEnabled=false" in a
assert "val active=wifi!=null && wifi==inverterLanSessionNetwork" in a
assert "if(inverterLocalSessionActive() ||" in a
assert "inverterLanSessionNetwork=network" in a
assert "inverterLanCheckbox.setOnCheckedChangeListener" in a
assert 'text="Utiliser le Wi-Fi local pour mes onduleurs pendant cette session"' in a
assert "configured.all { slot ->" in a
assert "SunSpecReadOnly.isPrivateIPv4(ip)" in a
assert "auxiliaryCode(codeKey).length in 8..32" in a
assert "voltronicFleet.changed()" in a
assert "selectedRoute()!=JkTransportPolicy.Route.GATEWAY_VPN" in a
assert "connection_preference" in a
assert "inverterLanSessionEnabled" not in a[a.index("private fun selectedRoute()"):a.index("private fun allowedTailnetIp(")]
assert "cible non résolue par le VPN (503)" in html
assert "rejet de cette requête par le contrôle du tunnel VPN (403)" in html
print("PASS: inverter local override explicit, network-scoped, nonpersistent, JK routing unchanged")
