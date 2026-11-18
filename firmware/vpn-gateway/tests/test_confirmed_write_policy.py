"""Regression for one-click confirmed remote writes, without physical USB opt-in.

Static checks complement ESP-IDF builds and the existing C allowlist tests.
No real electrical SET is sent by this test.
"""
from pathlib import Path

root=Path(__file__).resolve().parents[1]
code=(root/"main"/"main.c").read_text()
handler=code.split("static esp_err_t relay_parameter_control(httpd_req_t *req) {",1)[1].split("\nstatic ",1)[0]
health=code.split("static esp_err_t health_handler(httpd_req_t *req) {",1)[1].split("\nstatic ",1)[0]
assert 'GATEWAY_VERSION "0.3.13-dev-vpn-s3-ota"' in code
assert "remote_parameter_writes_supported" in health
assert "remote_write_confirmation_required" in health
assert "remote_parameter_writes_opt_in" in health  # old APK compatibility
assert 'if(!personal || !personal->remote_writes_enabled)' not in handler
assert "remote_writes_disabled_usb_opt_in_required" not in handler
for required in (
    "request_is_private_vpn(req)", "authorized(req)",
    "X-Remote-Write-Confirmed", 'strcmp(confirmed,"yes")!=0',
    "safe_write_payload(route.kind,body)",
    "parameter_write_cooldown", "get_target(selection,&target)",
    'esp_http_client_set_header(client,"X-Setup-Code",target.access_code)',
    'esp_http_client_set_header(client,"X-Write-Confirmed","yes")',
):
    assert required in handler, required
print("VPN no-USB-activation writes: authenticated+confirmed allowlists enforced")
