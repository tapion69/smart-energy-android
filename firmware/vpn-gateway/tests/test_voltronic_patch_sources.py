"""Check source invariants of USB-only NVS merge (not a hardware test)."""
from pathlib import Path
p=Path(__file__).resolve().parents[1]/"main"/"vpn_provision.c"
s=p.read_text()
for required in (
    "SEVPN_ADD_VOLTRONIC ", "patch_voltronic_only(",
    "nvs_get_str(handle,CFG_KEY,stored,&stored_len)",
    "parse_config(stored,&previous)",
    "strcmp(previous.tailscale_key,checked.tailscale_key)",
    "strcmp(previous.gateway_token,checked.gateway_token)",
    "strcmp(previous.wifi_password,checked.wifi_password)",
    "strcmp(previous.jk_ip[i],checked.jk_ip[i])",
    "strcmp(previous.jk_code[i],checked.jk_code[i])",
    "success=save_config(merged)"
):
    assert required in s, "Missing protective invariant: "+required
assert s.index('SEVPN_ADD_VOLTRONIC ') > s.index('static void provisioning_loop')
print("Voltronic-only USB patch checks preserve JK, Wi-Fi and VPN NVS fields")
