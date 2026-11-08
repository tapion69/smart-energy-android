package fr.smartenergy.android

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.min

/** Native UI (no image mocks): actual JK readings only, in-place updates. */
class BatteryDashboardCard(context: Context) : LinearLayout(context) {
    private val ink = Color.rgb(239, 246, 255)
    private val muted = Color.rgb(165, 183, 202)
    private val accent = Color.rgb(59, 228, 173)
    private val panel = Color.rgb(22, 35, 53)
    private val track = Color.rgb(48, 66, 84)
    private val cellLow = Color.rgb(255, 105, 115)
    private val cellHigh = Color.rgb(95, 170, 255)
    private val alarmColor = Color.rgb(255, 182, 98)

    private val deviceName = txt("Batterie JK", 17f, true, accent)
    private val deviceAddress = txt("", 11f, false, muted)
    private val connection = txt("En attente du JK", 12f, true, muted)
    private val age = txt("Aucune mesure récente", 12f, false, muted)
    private val error = txt("", 13f, false, muted)
    private val ring = SocRing(context)
    private val voltage = txt("— V", 20f, true)
    private val current = txt("— A", 20f, true)
    private val power = txt("— W", 20f, true)
    private val cellStats = txt("En attente des cellules", 12f, false, muted)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val t1 = txt("—", 14f, true)
    private val t2 = txt("—", 14f, true)
    private val tMos = txt("—", 14f, true)
    private val t3 = txt("—", 14f, true)
    private val t4 = txt("—", 14f, true)
    private val t5 = txt("—", 14f, true)
    private val alarmSummary = txt("En attente", 13f, true, muted)
    private val alarmDetails = LinearLayout(context).apply { orientation = VERTICAL }
    private var lastAlarmSignature: String? = null
    private val cycles = txt("—", 22f, true)
    private val contentArea = LinearLayout(context).apply { orientation = VERTICAL }
    private val bars = ArrayList<CellItem>()

    init {
        orientation = VERTICAL
        setPadding(0, d(8), 0, d(18))
        addView(deviceName)
        addView(deviceAddress, LayoutParams(-1, -2).apply { topMargin = d(3) })
        val connectionRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        connectionRow.addView(connection, LayoutParams(0, -2, 1f))
        connectionRow.addView(age)
        addView(connectionRow, LayoutParams(-1, -2).apply { topMargin = d(10); bottomMargin = d(12) })

        val main = card()
        val mainRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mainRow.addView(ring, LayoutParams(d(137), d(148)))
        val metrics = LinearLayout(context).apply { orientation = VERTICAL; setPadding(d(10), 0, 0, 0) }
        metrics.addView(metric("Tension", voltage))
        metrics.addView(divider())
        metrics.addView(metric("Courant", current))
        metrics.addView(divider())
        metrics.addView(metric("Puissance", power))
        mainRow.addView(metrics, LayoutParams(0, -2, 1f))
        main.addView(mainRow)
        contentArea.addView(main, LayoutParams(-1, -2).apply { bottomMargin = d(12) })

        val cells = card()
        cells.addView(txt("Cellules", 18f, true))
        cells.addView(cellStats, LayoutParams(-1, -2).apply { topMargin = d(8) })
        val legend = LinearLayout(context).apply { orientation = HORIZONTAL }
        legend.addView(txt("● Minimum", 11f, true, cellLow))
        legend.addView(txt("● Maximum", 11f, true, cellHigh),
            LayoutParams(-2, -2).apply { leftMargin = d(14) })
        cells.addView(legend, LayoutParams(-1, -2).apply { topMargin = d(7) })
        cells.addView(divider(), LayoutParams(-1, d(1)).apply {
            topMargin = d(10); bottomMargin = d(10)
        })
        cells.addView(grid)
        contentArea.addView(cells, LayoutParams(-1, -2).apply { bottomMargin = d(12) })

        // Full-width temperatures prevent the narrow columns clipping on phones.
        val temperatures = card()
        temperatures.addView(txt("Températures · toutes les sondes", 17f, true))
        temperatures.addView(txt("Une sonde absente reste « — », jamais 0 °C.", 11f, false, muted),
            LayoutParams(-1, -2).apply { topMargin = d(3); bottomMargin = d(7) })
        fun addTemperaturePair(a: String, valueA: TextView, b: String, valueB: TextView) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.addView(temperatureRow(a, valueA), LayoutParams(0, -2, 1f).apply {
                rightMargin = d(7)
            })
            row.addView(temperatureRow(b, valueB), LayoutParams(0, -2, 1f).apply {
                leftMargin = d(7)
            })
            temperatures.addView(row)
        }
        addTemperaturePair("Sonde 1", t1, "Sonde 2", t2)
        addTemperaturePair("Sonde 3", t3, "Sonde 4", t4)
        addTemperaturePair("Sonde 5", t5, "MOS", tMos)
        contentArea.addView(temperatures, LayoutParams(-1, -2).apply { bottomMargin = d(12) })

        val alarms = card()
        alarms.addView(txt("Alarmes BMS", 17f, true))
        alarms.addView(alarmSummary, LayoutParams(-1, -2).apply { topMargin = d(8) })
        alarms.addView(alarmDetails)
        alarms.addView(txt("Lecture informative : ne remplace pas les protections du BMS.",
            11f, false, muted), LayoutParams(-1, -2).apply { topMargin = d(8) })
        contentArea.addView(alarms, LayoutParams(-1, -2).apply { bottomMargin = d(12) })

        val cycleCard = card()
        val cycleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        cycleRow.addView(txt("Cycles", 15f, true), LayoutParams(0, -2, 1f))
        cycleRow.addView(cycles)
        cycleCard.addView(cycleRow)
        cycleCard.addView(txt("Données réelles · lecture seule", 11f, false, muted),
            LayoutParams(-1, -2).apply { topMargin = d(5) })
        contentArea.addView(cycleCard)
        addView(contentArea)
        addView(error, LayoutParams(-1, -2).apply { topMargin = d(8) })
        contentArea.visibility = GONE
        error.visibility = GONE
    }

    fun bind(index: Int, device: JSONObject) {
        deviceName.text = "JK " + (index + 1)
        deviceAddress.text = device.optString("mac")
        val isConnected = device.optBoolean("ble_connected", false)
        connection.text = if (isConnected) "● BLE connecté" else "● BLE déconnecté"
        connection.setTextColor(if (isConnected) accent else muted)
        if (!device.optBoolean("available", false)) {
            contentArea.visibility = GONE
            error.visibility = VISIBLE
            age.text = "Mesure indisponible"
            error.text = when (device.optString("state")) {
                "waiting_first_poll" -> "En attente de la première lecture"
                "searching_ble" -> "Recherche Bluetooth en cours"
                "connecting_ble" -> "Connexion Bluetooth en cours"
                "ble_connect_failed" -> "Connexion Bluetooth expirée"
                "ble_disconnected" -> "Liaison perdue, reconnexion automatique"
                "ble_not_seen" -> "JK hors de portée Bluetooth"
                "scan_requested" -> "Scan BLE demandé, reprise ensuite"
                "rotating_multi_bms" -> "Lecture des autres JK"
                "waiting_frames" -> "En attente de trames JK"
                "unknown_jk_layout" -> "Trames reçues mais données non reconnues"
                "stale" -> "La dernière mesure est trop ancienne"
                else -> "En attente de mesures valides"
            }
            return
        }
        val values = device.optJSONObject("values") ?: return
        contentArea.visibility = VISIBLE
        error.visibility = GONE
        age.text = "Mesure : " + device.optInt("age_seconds") + " s"
        ring.soc = values.optInt("soc_pct").coerceIn(0, 100)
        voltage.text = num(values, "voltage_v", 2) + " V"
        current.text = num(values, "current_a", 2) + " A"
        power.text = num(values, "power_w", 1) + " W"
        val count = values.optInt("cell_count")
        cellStats.text = count.toString() + " cellules  •  Min " +
            num(values, "cell_min_v", 3) + " V  •  Max " +
            num(values, "cell_max_v", 3) + " V\nÉcart : " +
            values.optInt("cell_delta_mv") + " mV"
        cycles.text = values.optInt("cycles").toString()
        t1.text = temperature(values, "temp1_c")
        t2.text = temperature(values, "temp2_c")
        t3.text = temperature(values, "temp3_c")
        t4.text = temperature(values, "temp4_c")
        t5.text = temperature(values, "temp5_c")
        tMos.text = temperature(values, "mos_temp_c")
        bindAlarms(values)
        val cells = values.optJSONArray("cells")
        val size = cells?.length() ?: 0
        if (size != bars.size) buildBars(size)
        val cellMeasurements = (0 until size).mapNotNull { i ->
            val cell = cells?.optJSONObject(i) ?: return@mapNotNull null
            val v = cell.optDouble("voltage_v", Double.NaN)
            val number = cell.optInt("number", -1)
            if (!v.isFinite() || v !in 1.5..4.5 || number !in 1..32) null
            else JkCellExtrema.Cell(number, kotlin.math.round(v * 1000.0).toInt())
        }
        val extrema = JkCellExtrema.select(
            cellMeasurements,
            values.optInt("cell_min_number", -1).takeIf { it in 1..32 },
            values.optInt("cell_max_number", -1).takeIf { it in 1..32 }
        )
        for (i in 0 until size) {
            val cell = cells?.optJSONObject(i) ?: continue
            val v = cell.optDouble("voltage_v", Double.NaN)
            val role = when (cell.optInt("number", -1)) {
                extrema.lowest -> if (extrema.lowest != null) -1 else 0
                extrema.highest -> if (extrema.highest != null) 1 else 0
                else -> 0
            }
            bars[i].update(cell.optInt("number"), v, role)
        }
    }

    private fun buildBars(size: Int) {
        grid.removeAllViews()
        bars.clear()
        // Two columns: left C1..C8 and right C9..C16 for a 16S pack.
        val half = (size + 1) / 2
        for (row in 0 until half) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (column in 0..1) {
                val itemIndex = row + half * column
                if (itemIndex >= size) continue
                val bar = CellItem(context)
                // Append in input index order to keep the bind indices stable.
                while (bars.size <= itemIndex) bars.add(bar)
                bars[itemIndex] = bar
                line.addView(bar.view, LayoutParams(0, -2, 1f).apply {
                    leftMargin = if (column == 1) d(5) else 0
                    rightMargin = if (column == 0) d(5) else 0
                })
            }
            grid.addView(line, LayoutParams(-1, -2).apply { bottomMargin = d(7) })
        }
    }

    private inner class CellItem(ctx: Context) {
        val view = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        private val label = txt("", 11f, true)
        private val value = txt("", 11f)
        private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(track)
        }
        private var currentRole = 0
        private var currentColor = accent
        private var started = false
        private var colorAnimation: ValueAnimator? = null
        init {
            view.addView(label, LayoutParams(d(27), -2))
            view.addView(bar, LayoutParams(0, d(7), 1f))
            view.addView(value, LayoutParams(-2, -2).apply { leftMargin = d(5) })
        }
        fun update(number: Int, v: Double, role: Int) {
            label.text = "C" + number
            value.text = if (v.isFinite()) String.format(Locale.FRANCE, "%.3f", v) else "—"
            view.contentDescription = "Cellule $number : " +
                (if (v.isFinite()) String.format(Locale.FRANCE, "%.3f volts", v) else "indisponible") +
                when (role) { -1 -> ", tension minimale"; 1 -> ", tension maximale"; else -> "" }
            val nextProgress = if (v.isFinite())
                (((v - 2.5) / 1.2).coerceIn(0.0, 1.0) * 1000).toInt() else 0
            bar.setProgress(nextProgress, started && ValueAnimator.areAnimatorsEnabled())
            if (started && currentRole == role) return
            val target = when (role) {
                -1 -> cellLow
                1 -> cellHigh
                else -> accent
            }
            colorAnimation?.cancel()
            if (started && ValueAnimator.areAnimatorsEnabled()) {
                colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, target).apply {
                    duration = 550L
                    addUpdateListener {
                        val color = it.animatedValue as Int
                        bar.progressTintList = ColorStateList.valueOf(color)
                        label.setTextColor(if (role == 0) ink else color)
                        value.setTextColor(if (role == 0) ink else color)
                    }
                    start()
                }
            } else {
                bar.progressTintList = ColorStateList.valueOf(target)
                label.setTextColor(if (role == 0) ink else target)
                value.setTextColor(if (role == 0) ink else target)
            }
            currentRole = role
            currentColor = target
            started = true
        }
    }

    private fun bindAlarms(values: JSONObject) {
        val hasMask = values.has("error_mask") && !values.isNull("error_mask")
        val rawMask = values.optLong("error_mask", 0L)
        val warnings = if (hasMask) JkAlarmLabels.activeBits(rawMask) else emptyList()
        val signature = if (!hasMask) "missing" else warnings.joinToString(",")
        if (signature == lastAlarmSignature) return
        lastAlarmSignature = signature
        alarmDetails.removeAllViews()
        when {
            !hasMask -> {
                alarmSummary.text = "État des alarmes indisponible"
                alarmSummary.setTextColor(muted)
            }
            warnings.isEmpty() -> {
                alarmSummary.text = "● Aucune alarme active"
                alarmSummary.setTextColor(accent)
            }
            else -> {
                alarmSummary.text = "⚠ " + warnings.size + " alarme(s) active(s)"
                alarmSummary.setTextColor(alarmColor)
                for (bit in warnings) {
                    alarmDetails.addView(txt("• " + JkAlarmLabels.label(bit),
                        12f, true, alarmColor), LayoutParams(-1, -2).apply { topMargin = d(6) })
                }
            }
        }
    }

    private fun metric(title: String, value: TextView) = LinearLayout(context).apply {
        orientation = VERTICAL; setPadding(0, d(5), 0, d(5))
        addView(txt(title, 11f, false, muted))
        addView(value)
    }
    private fun temperatureRow(name: String, number: TextView) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, d(7), 0, d(3))
        addView(txt(name, 12f, false, muted), LayoutParams(0, -2, 1f))
        addView(number)
    }
    private fun card() = LinearLayout(context).apply {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(panel); cornerRadius = d(16).toFloat()
            setStroke(d(1), Color.rgb(52, 74, 97))
        }
        setPadding(d(13), d(13), d(13), d(13))
    }
    private fun divider() = View(context).apply {
        setBackgroundColor(Color.rgb(52, 71, 89))
        layoutParams = LayoutParams(-1, d(1))
    }
    private fun txt(value: String, size: Float, bold: Boolean = false, color: Int = ink) =
        TextView(context).apply {
            text = value; textSize = size
            setTextColor(color); includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    private fun num(json: JSONObject, key: String, digits: Int): String =
        if (json.has(key) && !json.isNull(key))
            String.format(Locale.FRANCE, "%." + digits + "f", json.optDouble(key))
        else "—"
    private fun temperature(json: JSONObject, key: String): String =
        if (json.has(key) && !json.isNull(key)) num(json, key, 1) + " °C" else "—"
    private fun d(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    private inner class SocRing(ctx: Context) : View(ctx) {
        var soc: Int = 0
            set(value) { field = value; invalidate() }
        private val arc = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) * .39f
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(13).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = track
            canvas.drawArc(arc, -90f, 360f, false, paint)
            paint.color = accent
            canvas.drawArc(arc, -90f, 360f * soc / 100f, false, paint)
            paint.style = Paint.Style.FILL
            paint.color = ink
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = d(34).toFloat()
            canvas.drawText(soc.toString() + " %", cx, cy + d(1), paint)
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = d(11).toFloat()
            paint.color = muted
            canvas.drawText("État de charge", cx, cy + d(22), paint)
        }
    }
}
