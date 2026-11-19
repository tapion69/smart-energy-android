"""Source regression test: Android AlertDialog must show ESP selection rows.

Android AlertDialog.Builder.setMessage and setItems use the same content panel,
so a message hides the list and leaves only the cancel button visible.
"""
from pathlib import Path

src=(Path(__file__).resolve().parents[2]/
     "android/app/src/main/java/fr/smartenergy/android/MainActivity.kt").read_text(encoding="utf-8")
start=src.index('val choices=available.map {')
end=src.index('}.setNegativeButton("Plus tard",null).show()',start)
dialog=src[start:end]
list_start=dialog.index('.setTitle("OTA JK disponible')
list_end=dialog.index('.setItems(choices)',list_start)
assert '.setMessage(' not in dialog[list_start:list_end], "Message hides the selectable ESP reader list"
assert '.setItems(choices) { _,which ->' in dialog, "Missing ESP selection action"
assert 'val (reader,release)=available[which]' in dialog, "Selected ESP must map to actual OTA reader"
assert '.setTitle("CONFIRMER OTA JK · ESP${reader.slot}")' in dialog
assert '.setPositiveButton("Installer")' in dialog, "OTA must require explicit install confirmation"
assert 'JkLocalOta.install(this,release,reader.host' in dialog
assert '.setNegativeButton("Annuler",null)' in dialog
assert 'ESP${it.first.slot}' in dialog, "Reader labels must show the slot number"
assert src.count('val choices=available.map {')==1
print("PASS JK OTA: list visible, explicit ESP selection, confirmation before installation")
