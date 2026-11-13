package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class EnergyDashboardSelectionTest {
    @Test fun newInstallGetsEnergyOnboarding() {
        assertTrue(EnergyDashboardSelection.showEnergy(false,false))
    }
    @Test fun jkOnlyKeepsOriginalDashboard() {
        assertFalse(EnergyDashboardSelection.showEnergy(false,true))
    }
    @Test fun configuredInverterGetsEnergy() {
        assertTrue(EnergyDashboardSelection.showEnergy(true,false))
        assertTrue(EnergyDashboardSelection.showEnergy(true,true))
    }
}
