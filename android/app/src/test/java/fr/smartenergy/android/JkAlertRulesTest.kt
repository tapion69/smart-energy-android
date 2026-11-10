package fr.smartenergy.android

import org.junit.Assert.*
import org.junit.Test

class JkAlertRulesTest {
    private fun bms(
        number:Int, soc:Int=55, cap:Double=100.0,
        highest:Double=3.310, temp:Double=22.0, alarm:Boolean=false
    ) = JkBatteryReading(
        "reader-${if(number<=2) 1 else 2}:BMS-$number", "Batt-$number",soc,cap,
        listOf(JkCellReading(1,3.20),JkCellReading(7,highest)),
        listOf(JkProbeReading("Sonde 1",temp),JkProbeReading("MOS",21.0)),
        alarm, true
    )

    @Test fun detectsThresholdsOnAllFourBmsAcrossTwoReaders() {
        val data=listOf(bms(1),bms(2,soc=17),bms(3,highest=3.510),
            bms(4,temp=55.0))
        val rules=JkAlertThresholds(globalSocEnabled=true,globalSocBelow=50,
            bmsSocEnabled=true,bmsSocBelow=20,
            cellHighEnabled=true,cellAboveV=3.500,
            tempHighEnabled=true,tempAboveC=50.0)
        val result=JkAlertRules.evaluate(data,4,rules)
        assertTrue(result.active.any { it.key=="soc:reader-1:BMS-2" &&
            it.message.contains("Batt-2") })
        assertTrue(result.active.any { it.key=="cell:reader-2:BMS-3" &&
            it.message.contains("Cellule 7") && it.message.contains("3,510 V") })
        assertTrue(result.active.any { it.key=="temp:reader-2:BMS-4" &&
            it.message.contains("Batt-4") && it.message.contains("55,0") })
        assertTrue(result.active.any { it.key=="soc-global" &&
            it.message.contains("SOC global") })
    }

    @Test fun globalSocRequiresAllConfiguredFreshReadings() {
        val readers=listOf(bms(1,soc=10),bms(2,soc=10),bms(3,soc=10))
        assertNull(JkAlertRules.globalSoc(readers,4))
        assertNull(JkAlertRules.globalSoc(readers+ bms(4).copy(fresh=false),4))
        assertNull(JkAlertRules.globalSoc(readers+readers.first(),4))
    }

    @Test fun globalSocWeightedByCapacityIfAvailableOtherwiseAverage() {
        val data=listOf(bms(1,soc=10,cap=300.0),bms(2,soc=70,cap=100.0))
        assertEquals(25,JkAlertRules.globalSoc(data,2))
        assertEquals(40,JkAlertRules.globalSoc(data.map { it.copy(capacityAh=null) },2))
    }

    @Test fun thresholdsAreStrictAndDisabledByDefault() {
        val data=listOf(bms(1,soc=20,highest=3.500,temp=50.0))
        assertTrue(JkAlertRules.evaluate(data,1,JkAlertThresholds()).active.isEmpty())
        val enabled=JkAlertThresholds(bmsSocEnabled=true,bmsSocBelow=20,
            cellHighEnabled=true,cellAboveV=3.500,
            tempHighEnabled=true,tempAboveC=50.0)
        assertTrue(JkAlertRules.evaluate(data,1,enabled).active.isEmpty())
        assertEquals(3,JkAlertRules.evaluate(
            listOf(bms(1,soc=19,highest=3.501,temp=50.1)),1,enabled).active.size)
    }

    @Test fun offlineSamplesCannotCreateNewAlarms() {
        val reading=bms(3,soc=10,highest=3.8,temp=80.0,alarm=true).copy(fresh=false)
        val enabled=JkAlertThresholds(globalSocEnabled=true,globalSocBelow=50,
            bmsSocEnabled=true,bmsSocBelow=20,cellHighEnabled=true,
            tempHighEnabled=true)
        val result=JkAlertRules.evaluate(listOf(reading),1,enabled)
        assertTrue(result.active.isEmpty())
        assertTrue(result.evaluatedKeys.isEmpty())
    }

    @Test fun noFalseTemperatureFromUnavailableSentinel() {
        val data=listOf(bms(1).copy(probes=listOf(JkProbeReading("Sonde 1",-2000.0))))
        assertTrue(JkAlertRules.evaluate(data,1,
            JkAlertThresholds(tempHighEnabled=true,tempAboveC=40.0)).active.isEmpty())
    }

    @Test fun parsesFrenchDecimalInputsAndRejectsInvalidLimits() {
        assertEquals(3.5,JkAlertPreferences.cell("3,500")!!,0.00001)
        assertNull(JkAlertPreferences.cell("0"))
        assertEquals(50.0,JkAlertPreferences.temperature("50,0")!!,0.001)
        assertNull(JkAlertPreferences.temperature("NaN"))
        assertNull(JkAlertPreferences.soc("110"))
    }
}
