"""Catch GUI-only runtime errors in VPN one-time OTA migration.

This protects the exact code path that opens the private NVS backup folder.
The migration must be available in the packaged Windows GUI without relying
on imports local to the unrelated JK or Voltronic tabs.
"""
import ast
import unittest
from pathlib import Path

source = (Path(__file__).resolve().parent / "smart_energy_flasher.py").read_text(encoding="utf-8")
module = ast.parse(source)

class VpnMigrationDialogTests(unittest.TestCase):
    def test_datetime_reachable_from_vpn_migration_dialog(self):
        globals_imported={
            a.name if a.asname is None else a.asname
            for stmt in module.body if isinstance(stmt,ast.Import)
            for a in stmt.names
        }
        globals_imported.update(
            a.asname or a.name for stmt in module.body if isinstance(stmt,ast.ImportFrom)
            for a in stmt.names
        )
        flasher=next(n for n in module.body if isinstance(n,ast.ClassDef) and n.name=="Flasher")
        method=next(n for n in flasher.body
                    if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef))
                    and n.name=="_migrate_vpn_to_ota")
        called=[n for n in ast.walk(method)
                if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute)
                and n.func.attr=="now" and isinstance(n.func.value,ast.Name)
                and n.func.value.id=="datetime"]
        self.assertEqual(len(called),1,"VPN migration must name a timestamped backup folder")
        local_import=any(isinstance(n,ast.ImportFrom) and n.module=="datetime"
                         and any(a.name=="datetime" for a in n.names)
                         for n in ast.walk(method))
        self.assertTrue("datetime" in globals_imported or local_import,
                        "VPN backup dialog references datetime.now() but datetime is not imported")
        self.assertIn("prepare_usb_migration",source)
        self.assertIn('command=self._migrate_vpn_to_ota',source)

if __name__=="__main__":
    unittest.main()
