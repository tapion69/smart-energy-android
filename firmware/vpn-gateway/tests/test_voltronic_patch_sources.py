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
    "if(!save_config(merged))goto finish"
):
    assert required in s, "Missing protective invariant: "+required
assert s.index('SEVPN_ADD_VOLTRONIC ') > s.index('static void provisioning_loop')
print("Voltronic-only USB patch checks preserve JK, Wi-Fi and VPN NVS fields")

for required in (
    "SEVPN_SET_JK ", "patch_jk_only(",
    "return patch_reader_only(raw_patch,true)",
    "return patch_reader_only(raw_patch,false)",
    "previous.remote_writes_enabled!=checked.remote_writes_enabled",
    "strcmp(previous.voltronic_ip[i],checked.voltronic_ip[i])",
    "strcmp(previous.jk_ip[i],checked.jk_ip[i])",
    "success=load_config(&persisted)",
    "memcmp(&persisted,&checked,sizeof(checked))==0",
    '"SEVPN_ERR_NVS"',
):
    assert required in s, "Missing single-JK slot USB safety: "+required
