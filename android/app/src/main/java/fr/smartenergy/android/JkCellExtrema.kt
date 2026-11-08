package fr.smartenergy.android

/** Deterministic cell-number selection, matching the JK Bluetooth module's
 * readings.index(min(readings)) / readings.index(max(readings)) behavior:
 * one lowest and one highest cell even if multiple voltages are equal.
 * Prefer the explicit numbers supplied by firmware 0.2.2+, after validating
 * that they match the extrema in the same frame. Older firmware falls back
 * to the first cell at each extremum. This never invents new readings.
 */
internal object JkCellExtrema {
    data class Cell(val number: Int, val millivolts: Int)
    data class Result(val lowest: Int?, val highest: Int?)

    fun select(cells: List<Cell>, reportedLowest: Int? = null,
               reportedHighest: Int? = null): Result {
        val valid = cells.filter { it.number in 1..32 && it.millivolts in 1500..4500 }
            .distinctBy { it.number }
        if (valid.size < 2) return Result(null, null)
        val low = valid.minByOrNull { it.millivolts } ?: return Result(null, null)
        val high = valid.maxByOrNull { it.millivolts } ?: return Result(null, null)
        // Equal readings do not meaningfully identify a lowest vs highest cell.
        if (low.millivolts == high.millivolts) return Result(null, null)
        val confirmedMin = valid.find { it.number == reportedLowest &&
            it.millivolts == low.millivolts }?.number ?: low.number
        val confirmedMax = valid.find { it.number == reportedHighest &&
            it.millivolts == high.millivolts }?.number ?: high.number
        return Result(confirmedMin, confirmedMax)
    }
}
