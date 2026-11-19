"""Static non-regression guards for the optional APK-only latency experiment.

These cannot prove real VPN RTT; actual performance must be measured on a phone.
They enforce the unchanged BMS firmware/protocol contract and app concurrency.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
app = (root / "android/app/src/main/java/fr/smartenergy/android/MainActivity.kt").read_text(encoding="utf-8")
fleet = (root / "android/app/src/main/java/fr/smartenergy/android/VoltronicFleet.kt").read_text(encoding="utf-8")
web = (root / "android/app/src/main/java/fr/smartenergy/android/EnergyDashboardV3.kt").read_text(encoding="utf-8")

assert "private val liveTelemetryWorker=Executors.newSingleThreadExecutor()" in app
assert "liveTelemetryWorker.execute {" in app
assert "liveTelemetryWorker.shutdownNow()" in app
assert "private val inverterWorker=Executors.newFixedThreadPool(2)" in app
assert "lastJkHttpRoundTripMs=roundTrip" in app
assert 'requestRoute==selectedRoute() && lanVerified' in app
assert "val fastRead=method==\"GET\"" in app
assert "conn.readTimeout = if(fastRead) 7000" in app
assert "remoteRetryFailures=(remoteRetryFailures+1).coerceAtMost(4)" in app

vpn = app[app.index("private fun connectGatewayRemote()"):app.index("private fun beginBootPairing()")]
assert vpn.index("if (!matchesPairing)") < vpn.index("lanVerified=true") 
assert vpn.index("showTelemetry(telemetry,newRead=true)") < vpn.index("val canWriteRemote=")
assert "if(!connectionVerified)" in vpn

assert "import java.util.concurrent.atomic.AtomicInteger" in fleet
assert "import java.util.concurrent.atomic.AtomicBoolean" in fleet
assert "val remaining=AtomicInteger(configs.size)" in fleet
assert "for(c in configs) {\n            worker.execute {" in fleet
assert "if(remaining.decrementAndGet()==0)" in fleet
assert "needsShortRetry.set(true)" in fleet
assert "if(busy || (lastPoll>0L && now-lastPoll<5000L))return" in fleet
assert "states[slotIndex]=next" in fleet
assert "mainHandler.postDelayed(partialRender,120L)" in fleet

assert "private val publishTask=Runnable" in web
assert "postDelayed(publishTask,120L)" in web
assert "SystemClock.elapsedRealtime()-lastFitAtMs>=5000L" in web
assert "onStyleChanged" in web and "onNavigate" in web

print("PASS: independent JK GETs, bounded inverter concurrency, early VPN sample, retry backoff, WebView coalescing")
