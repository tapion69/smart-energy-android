package fr.smartenergy.android

/** Routing only: no change to the existing JK dashboard or its screens. */
object EnergyDashboardSelection {
    fun showEnergy(hasInverter:Boolean,hasConfiguredJk:Boolean):Boolean =
        hasInverter || !hasConfiguredJk

    /** Exactly one home surface: native JK when JK is the only configured source. */
    fun showBatteryHome(hasInverter:Boolean,hasConfiguredJk:Boolean):Boolean =
        !hasInverter && hasConfiguredJk
}
