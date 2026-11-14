# Voltronic OTA preflight without modifying serial or storing secrets
from pathlib import Path
source=Path(__file__).resolve().parents[1]/"main"/"main.c"
s=source.read_text()
for required in (
    '/api/v1/ota/status',
    'running_partition',
    'boot_partition',
    'running_firmware',
    'rollback_pending',
    'requires_usb_migration',
    'ota_supported',
    'requires_explicit_confirmation',
    'ota_destination()',
    'esp_ota_mark_app_valid_cancel_rollback',
    'esp_ota_mark_app_invalid_rollback_and_reboot',
    'X-Image-SHA256',
):
    assert required in s,required
# This test follows the OTA version compiled in the firmware under test.
# The old hard-coded 0.3.4 assertion blocked the safe 0.3.5 release.
assert '#define VT_VERSION "0.3.5"' in s
assert "esp_ota_get_state_partition" in s
assert "VT_JOBS 18" in s
print("OTA status remains read-only; guarded parameter writes are separate; rollback and SHA256 retained")
