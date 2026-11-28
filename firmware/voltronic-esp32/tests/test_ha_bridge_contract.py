#!/usr/bin/env python3
"""Static firmware bridge contract checks; supplementary to native C tests."""
import re
import unittest
from pathlib import Path

SRC = (Path(__file__).resolve().parent.parent / "main" / "main.c").read_text(encoding="utf-8")
PROTO = (Path(__file__).resolve().parent.parent / "main" / "voltronic_protocol.h").read_text(encoding="utf-8")
EDIT = (Path(__file__).resolve().parent.parent / "main" / "voltronic_edit.h").read_text(encoding="utf-8")

class BridgeContract(unittest.TestCase):
    def test_branch_identification(self):
        self.assertIn('#define VT_VERSION "0.3.10-test-pop-pcp"', SRC)
        self.assertIn('"ha_bridge_protocol","esp-cache-v1"', SRC)

    def test_single_owner_contract(self):
        self.assertIn('"ha_bridge_single_uart_owner",true', SRC)
        self.assertIn('static void polling_task(void *unused)', SRC)
        self.assertIn('if(process_pending_parameter_edit())continue;', SRC)
        self.assertEqual(SRC.count("uart_write_bytes(VT_UART,"), 1)  # UART0 USB provisioning is separate

    def test_cache_read_contract(self):
        self.assertIn('"ha_bridge_http_get_is_cache_only",true', SRC)
        self.assertIn('"http_read_was_cached",true', SRC)
        self.assertIn('"qpigs_sample_monotonic_ms"', SRC)
        self.assertIn('bool live=snap.have_qpigs && age<=10000;', SRC)
        for endpoint in ('/api/v1/info','/api/v1/telemetry','/api/v1/details','/api/v1/diagnostics'):
            self.assertIn(endpoint, SRC)

    def test_no_raw_http_command_endpoint(self):
        self.assertIn('"ha_bridge_raw_uart_commands_supported",false', SRC)
        self.assertNotIn('"/api/v1/commands"', SRC)
        self.assertNotIn('"/api/v1/raw-command"', SRC)
        self.assertIn('vt_read_command', PROTO)
        self.assertIn('if(!vt_read_command(cmd))return 0;', PROTO)

    def test_semantic_writes_are_whitelisted_and_verified(self):
        self.assertIn('"/api/v1/parameters/change"', SRC)
        self.assertIn('"X-Write-Confirmed"', SRC)
        self.assertIn('strcmp(confirmed,"yes")!=0', SRC)
        self.assertIn('vt_edit_find(key->valuestring)', SRC)
        self.assertIn('if(!vt_edit_allowed(spec,current,request->new_value,nominal))', SRC)
        self.assertIn('if(fabs(check[spec->index]-request->new_value)>spec->tolerance)', SRC)
        self.assertIn('static const vt_edit_spec_t VT_EDIT_SPECS[]', EDIT)
        self.assertIn('vt_edit_candidate_allowed', EDIT)
        self.assertIn('vt_edit_candidate_allowed(spec,value->valuedouble,candidate->valuestring)', SRC)
        self.assertIn('request->candidate', SRC)
        self.assertIn('ha_bridge_learning_mode', SRC)

        self.assertIn('One validated candidate per confirmed transaction.', SRC)

    def test_native_reply_cache_is_authenticated_and_read_only(self):
        self.assertIn('#define VT_BRIDGE_CACHE 20', SRC)
        self.assertIn('static esp_err_t bridge_snapshot_handler', SRC)
        self.assertIn('if(!authorized(r))return deny(r);', SRC)
        self.assertIn('"/api/v1/bridge/snapshot"', SRC)
        self.assertIn('conf.max_uri_handlers=8;', SRC)
        self.assertIn('cJSON_AddBoolToObject(doc,"cache_only",true)', SRC)
        self.assertIn('vt_bridge_record_t record;', SRC)
        self.assertIn('bridge_store_reply((size_t)(job-jobs),cmd,&rx,end)', SRC)
        start=SRC.index('static esp_err_t bridge_snapshot_handler')
        end=SRC.index('static esp_err_t diagnostics_handler',start)
        bridge=SRC[start:end]
        self.assertNotIn('uart_write_bytes(',bridge)
        self.assertNotIn('exchange(',bridge)
        self.assertNotIn('wifi_password',bridge)
        self.assertNotIn('read_code',bridge)

    def test_full_flow_extra_diagnostics_and_cache_boundaries(self):
        self.assertIn('#define VT_JOBS 20',SRC)
        self.assertIn('{"QBMS",3600000',SRC)
        self.assertIn('{"Q1",3600000',SRC)
        self.assertIn('"QBMS","Q1"',PROTO)
        self.assertIn('strcmp(cmd,"QPIGS")==0',SRC)
        self.assertIn('bool online=snap.have_qpigs',SRC)
        self.assertIn('"uart_stats"',SRC)
        self.assertIn('"max_seq"',SRC)
        self.assertIn('"age_ms"',SRC)

    def test_approved_semantic_m_prefix_and_verified_replay(self):
        self.assertIn("command[0]=='M'",SRC)
        self.assertIn('bridge_store_reply(j,spec->query,&after,verified_sample_ms)',SRC)
        self.assertIn('vt_edit_find(key->valuestring)',SRC)
        self.assertNotIn('"/api/v1/raw-command"',SRC)

    def test_wifi_codes_not_exported_with_telemetry(self):
        start = SRC.index('static esp_err_t telemetry_handler(')
        end = SRC.index('static esp_err_t details_handler(')
        block = SRC[start:end]
        self.assertNotIn('wifi_password', block)
        self.assertNotIn('read_code', block)
        self.assertNotIn('"X-Setup-Code"', block)

if __name__ == "__main__":
    unittest.main(verbosity=2)
