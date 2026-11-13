package fr.smartenergy.android

/** Routing only: no change to the existing JK dashboard or its screens. */
object EnergyDashboardSelection {
    fun showEnergy(hasInverter:Boolean,hasConfiguredJk:Boolean):Boolean =
        hasInverter || !hasConfiguredJk
}
