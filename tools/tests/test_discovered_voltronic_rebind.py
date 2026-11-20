"""Protect name-based Voltronic local rebinding and the existing JK slots.

A previously paired inverter can be rediscovered by mDNS without a manually
entered IP, but an unrecognized device must never overwrite INV1 or INV2.
"""
from pathlib import Path
src=(Path(__file__).resolve().parents[2]/
     "android/app/src/main/java/fr/smartenergy/android/MainActivity.kt").read_text()
pair=src[src.index("private fun promptReaderCode(reader:LocalReader)"):
         src.index("private fun assignedSetupSlots()")]
assert "val matches=(1..3).filter { slot ->" in pair
assert "voltronicFleet.configured(slot)" in pair
assert 'prefs.getString("inverter_${slot}_name","")' in pair
assert "val slot=if(matches.size==1)matches[0]" in pair
assert "else if(matches.isEmpty())(1..3).firstOrNull" in pair
assert 'info.optString("device_name").trim()!=reader.name.trim()' in pair
assert 'Regex("^se-voltronic-[a-fA-F0-9]{12}$")' in pair
assert 'prefs.getString("inverter_${slot}_device_id","")' in pair
assert "known.equals(identity,true)" in pair
assert 'putString("inverter_1_device_id",identity)' in pair
assert 'putString("inverter_"+slot+"_device_id",identity)' in pair
assert '" · adresse locale retrouvée automatiquement."' in pair
assert 'if(result.startsWith("Onduleur "))voltronicFleet.changed()' in pair
assert "switchReaderSlot(slot,autoConnect=false)" in pair # JK unchanged
print("PASS: verified Voltronic rebind by name and device ID; JK untouched")
