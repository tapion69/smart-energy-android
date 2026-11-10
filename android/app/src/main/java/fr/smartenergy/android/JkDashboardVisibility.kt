package fr.smartenergy.android

/** The fleet is displayed on Accueil only, even with one paired BMS. */
object JkDashboardVisibility {
    fun showFleet(selectedBmsMac:String):Boolean = selectedBmsMac.isBlank()
}
