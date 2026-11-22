#!/usr/bin/env python3
"""Static safety contract. No serial hardware or network required."""
from pathlib import Path
p=Path("firmware/voltronic-esp32/main/main.c").read_text()
assert '#define VT_VERSION "0.3.9-test-priority-write"' in p
assert "httpd_req_async_handler_begin(r,&async_request)" in p
assert "httpd_req_async_handler_complete(request)" in p
assert 'xTaskCreate(edit_parameter_worker,"voltronic_edit_reply"' in p
assert "edit_request.waiter_active=true" in p
assert "edit_request.waiter_active=false" in p
assert "xTaskNotifyGive(uart_poll_handle)" in p
assert "ulTaskNotifyTake(pdTRUE,pdMS_TO_TICKS(120))" in p
assert "if(process_pending_parameter_edit())continue;" in p
assert "vt_exchange_t before=exchange(spec->query);" in p
assert "vt_exchange_t reply=exchange_core(command,true);" in p
assert "vt_exchange_t after=exchange(spec->query);" in p
assert "if(reply.nak){edit_result(result,\"set_rejected_by_nak\");return;}" in p
assert "set_not_acknowledged_state_unknown" in p
assert "already_at_requested_value" in p
assert "state_uncertain_no_automatic_retry" in p
assert "previous_uart_edit_unresolved" in p
assert "changed_since_preview" not in p
assert p.index("vt_exchange_t before=exchange(spec->query);") < p.index("vt_exchange_t reply=exchange_core(command,true);") < p.index("vt_exchange_t after=exchange(spec->query);")
print("PASS: asynchronous status, native UART priority, fresh readback and idempotent writes")
