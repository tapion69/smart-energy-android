package fr.smartenergy.android

/** Highest valid probe reading among live batteries. Never uses offline samples. */
data class JkTemperatureSample(val label:String,val celsius:Double)

object JkTemperatureSummary {
    // Supported JK temperature probes; values must be finite and plausible.
    fun hottest(samples:List<JkTemperatureSample>):JkTemperatureSample? =
        samples.filter { it.celsius.isFinite() && it.celsius in -40.0..125.0 }
            .maxByOrNull { it.celsius }
}
