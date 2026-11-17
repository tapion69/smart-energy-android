# Smart Energy — Deye ESP32-S3 (premier firmware de test)

**Experimental / read-only; hardware NOT yet validated.**

Target: ESP32-S3 N16R8 (16MB flash, 8MB PSRAM), CH343 or native USB serial provisioning. Wi-Fi connects to a local Solarman V5 logger using its serial and port 8899. ESP32 announces itself to the existing authenticated VPN gateway as `family=inverter`, product `smart-energy-deye-s3`, using the same 3 named inverter slots as Voltronic.

**First flash**: a full factory image creates the dual-OTA partition table. After provisioning over USB, the dongle IP, dongle TCP port, Modbus slave ID, dongle serial and inverter serial are stored in NVS with Wi-Fi, reader name and reader code. Only APP-ONLY updates to an already matching partition layout are permitted afterwards. Never flash this firmware onto an existing JK, Voltronic or VPN reader.

**Initial scope**: FC03 Solarman V5 framing and CRC validation; model identification register 0 before decoding; single-phase hybrid and three-phase LOW-VOLTAGE measurement maps; HV type codes 0x0600/0x0601 are detected but intentionally not decoded until a verified HV register map is available; read-only catalogue of candidate settings; local authenticated config read/update; authenticated HMAC name-discovery; authenticated direct HTTPS OTA request with fixed GitHub update manifest and SHA256 verification (not tunneled through VPN). **No Modbus FC16 writes enabled** until physical model-specific values are validated; the VPN is already preauthorised for the future semantic write API.

API on ESP32 :18081: GET `/api/v1/info`, `/api/v1/telemetry`, `/api/v1/details`, `/api/v1/device-config`, `/api/v1/ota/status`; POST `/api/v1/device-config`, `/api/v1/parameters/change` (currently 403), `/api/v1/ota/request`. All require `X-Setup-Code`, POST additionally `X-Write-Confirmed: yes` and `confirmed:true`.

### Research sources

- Solarman V5 wire format: https://pysolarmanv5.readthedocs.io/en/stable/solarmanv5_protocol.html
- Deye single/three-phase register families: https://modbusmanager.com/deye-sunsynk-growatt-modbus.html
- Deye single-phase hybrid register reference and TOU controls: https://github.com/RichardAntalik/deye-modbus-reference
- Deye three-phase hybrid parameters: https://github.com/Developer089/deye-modbus-ha
- Sunsynk Solarman dongle IP/port/serial provisioning: https://kellerza.github.io/sunsynk/reference/multi-options

These maps differ by exact model and firmware. Confirm measured SOC, voltage and PV output before trusting displayed values. Do not modify grid protection, battery limits or TOU registers in initial tests.

### Implementation status (first functional read-only firmware)

- Model auto-detection reads holding register 0. Supported interpreted maps: `0x0300` (single-phase), `0x0500` (three-phase LV). HV 0x0600/0x0601: detected as unsupported map, never displayed using incorrect LV scales.
- Telemetry provides `measurements[]` and read-only `parameters[]` as {key,register,value,writable:false}; a null value means the register has not been read successfully. Candidate parameter names are experimental and require confirmation for each firmware/model.
- Solarman FC03 requests are validated with sequence/logger serial, Modbus slave, RTU CRC, outer checksum, and bounded response size. No arbitrary Modbus reads or writes can be submitted by an HTTP client.
- Settings can be set from local APK or the existing VPN Deye slot and are persisted to ESP32 NVS, then read back. The APK does not need to know the dongle address for VPN discovery; the dongle address belongs to the reader.
- OTA command is routed by VPN, but image transfer is always initiated by the reader on home Wi-Fi. The publication feed is unavailable initially. Real OTA compatibility and GitHub CDN redirect handling MUST be tested with a subsequent signed release before remote-only deployment.

This firmware has passed **build and code-level tests only**, not hardware tests; no guarantee of specific field readings or of real OTA on a customer's Deye.
