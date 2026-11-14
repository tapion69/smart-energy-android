# ESP32-S3 → Smart Voltronic Home Assistant bridge (test branch only)

This branch identifies itself with firmware version `0.3.6-test-ha-bridge`.

The `/api/v1/info` endpoint now advertises `ha_bridge_protocol=esp-cache-v1`, `ha_bridge_single_uart_owner=true`, `ha_bridge_http_get_is_cache_only=true`, and `ha_bridge_raw_uart_commands_supported=false`. This is an **additive** contract on top of the existing Android API: `/api/v1/info`, `/api/v1/telemetry`, `/api/v1/details`, `/api/v1/diagnostics`, OTA status and a guarded semantic parameter-change route.

**Two simultaneous clients** can GET cached telemetry without doubling RS232 polling. Only `polling_task` uses the UART for scheduled reads and confirmed parameter edits. The test branch adds the monotonic `qpigs_sample_monotonic_ms` and `http_read_was_cached` markers for diagnostics.

The firmware retains its existing `X-Setup-Code` authentication. A parameter change requires `POST /api/v1/parameters/change`, `X-Write-Confirmed: yes`, `confirmed:true`, a supported semantic parameter and exact expected old value. It is rejected if the inverter cannot be identified or the fresh pre-read, value validation, ACK or fresh verification fails. There is **no arbitrary raw UART command endpoint**.

Legacy alternate-command learning is deliberately not enabled on the ESP until device-specific semantics can be proven safely.

This branch has not been flashed to a real ESP32, and GitHub CI status should be checked before hardware testing. Never flash the normal VPN, JK BMS or production Voltronic ESP32 from this experiment.
