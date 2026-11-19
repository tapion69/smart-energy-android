"""APK regression guard: restore Voltronic to verified 0.3.65 behavior.

The previous 0.3.66 experiment used concurrent VPN relay requests and
coalesced WebView updates. Both are backed out entirely for this recovery.
JK-only speed improvements remain enabled and independent.
"""
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[2]
app = (root / "android/app/src/main/java/fr/smartenergy/android/MainActivity.kt").read_text(encoding="utf-8")
fleet_path = root / "android/app/src/main/java/fr/smartenergy/android/VoltronicFleet.kt"
web_path = root / "android/app/src/main/java/fr/smartenergy/android/EnergyDashboardV3.kt"

def git_blob_sha(path: Path) -> str:
    data=path.read_bytes()
    return hashlib.sha1(b"blob "+str(len(data)).encode("ascii")+bytes([0])+data).hexdigest()

# Exact Git blob contents of the original 0.3.65-dev implementation.
assert git_blob_sha(fleet_path) == "cc9a6e0af4a22eba1e45b5ae3de163bab02e592d"
assert git_blob_sha(web_path) == "43d788d8d624ab0cf7f6bb9e9acf5f252b26b351"

assert "private val inverterWorker=Executors.newSingleThreadExecutor()" in app
assert "private val liveTelemetryWorker=Executors.newSingleThreadExecutor()" in app
assert "liveTelemetryWorker.execute {" in app
assert "liveTelemetryWorker.shutdownNow()" in app
assert "lastJkHttpRoundTripMs=roundTrip" in app
assert "requestRoute==selectedRoute() && lanVerified" in app
assert 'val fastRead=method=="GET"' in app
assert "conn.readTimeout = if(fastRead) 7000" in app
assert "remoteRetryFailures=(remoteRetryFailures+1).coerceAtMost(4)" in app
vpn=app[app.index("private fun connectGatewayRemote()"):app.index("private fun beginBootPairing()")]
assert vpn.index("if (!matchesPairing)") < vpn.index("lanVerified=true")
assert vpn.index("showTelemetry(telemetry,newRead=true)") < vpn.index("val canWriteRemote=")
assert "if(!connectionVerified)" in vpn

print("PASS: exactly restored inverter 0.3.65 HTTP and WebView code, original ordered worker, JK-only optimizations retained")
