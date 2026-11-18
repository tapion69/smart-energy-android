"""Source regression guards for staged Voltronic VPN UI updates.
This checks structure; real network latency still requires hardware testing.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
src = (root / "android/app/src/main/java/fr/smartenergy/android/VoltronicFleet.kt").read_text(encoding="utf-8")
html = (root / "android/app/src/main/assets/energy_dashboard_v3.html").read_text(encoding="utf-8")
activity = (root / "android/app/src/main/java/fr/smartenergy/android/MainActivity.kt").read_text(encoding="utf-8")

assert "if(busy || (lastPoll>0L && now-lastPoll<5000L))return" in src
assert "VoltronicVpnReadOnlyClient.read(c.slot,false,privateGateway)" in src
assert 'VoltronicVpnReadOnlyClient.read(config.slot,true,gateway)' in src
assert "val requestStartedAt=SystemClock.elapsedRealtime()" in src
assert 'next.put("httpRoundTripMs"' in src
assert "val slotIndex=c.slot-1" in src
assert "states[slotIndex]=next" in src
assert "mainHandler.removeCallbacks(partialRender)" in src
assert "mainHandler.postDelayed(partialRender,120L)" in src
assert src.index("states[slotIndex]=next") < src.index("busy=false", src.index("val slotIndex=c.slot-1"))
assert "states[i]=results[i]" not in src
assert "'httpRoundTripMs','Temps réponse HTTP (dernier appel)'" in html
assert "private val inverterEnergyWorker=Executors.newSingleThreadExecutor()" in activity
assert 'voltronicFleet.poll(now,voltronicWifiNetwork())' in activity
print("PASS: progressive inverter publishing, 120ms coalescing, round-trip diagnostics, existing polling cadence")
