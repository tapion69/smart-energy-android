# Experimental ESP32-S3 firmware 0.3.7-test-ha-bridge

Branch: `test/esp32-ha-bridge`. Android `dev` and production firmware remain unchanged.

## Cache-only bridge

`GET /api/v1/bridge/snapshot` exposes 20 latest CRC-validated UART replies by named whitelisted command with sequence number and age. This endpoint **does not** issue UART requests; the existing `polling_task` is the sole inverter UART master. This is used by the complete 322-node Smart Voltronic flow (experimental 340-node flow, 15 additional nodes at add-on boot). Original Android read APIs remain unchanged.

`GET /api/v1/info` advertises the bridge's `esp-cache-v1` and `verified_candidate_formats_v1` capabilities. `/api/v1/telemetry` includes cache freshness markers. All GET endpoints require the existing setup code and never change inverter settings.

## Safe learning

Existing parameter write endpoint `POST /api/v1/parameters/change` now optionally accepts `candidate`. The firmware validates that the command is a permitted serialization of the **same semantic parameter**. Only alternate serializations of `max_ac_charging_current_a` (`MUCHGC`) or `max_charging_current_a` (`MNCHGC`) are supported. Generic raw UART commands are never accepted. `PBCV` as an alternative bulk voltage command is forbidden.

The write transaction runs on the original UART poller with required authorization, explicit confirmation, fresh pre-read and old-value comparison, strict bounds, one SET, and fresh readback. An explicit UART NAK returns `set_rejected_by_nak` and permits the caller to consider another validated same-setting candidate. Timeouts, uncertain ACK, HTTP failures and unsuccessful readbacks must **never** initiate automatic retries.

## Unverified

The build workflow contains protocol host-C and semantic write tests and a separate ESP-IDF ESP32-S3 build; results were not accessible during this session. There was no real firmware flash, Android concurrency validation or physical RS232 test. The full Node-RED's `MODBUS_MPPT2` fallback and certain LOAD2 settings are not supported by the present firmware and require separate verified work.

Do not deploy these experimental binaries in a production setup before hardware validation.
