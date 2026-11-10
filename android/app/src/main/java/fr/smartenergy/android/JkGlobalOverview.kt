package fr.smartenergy.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Multi-reader overview. No mocked values: every number comes from fresh type-02 BLE data.
 * SOC is capacity-weighted only when all online BMS have verified capacities.
 * Voltage is averaged; current and power are SUMS for independent parallel JK packs.
 * 'Aucune' for alarms requires valid, fresh measurements from every configured BMS.
 */
class JkGlobalOverview(private val ctx: Context) : LinearLayout(ctx) {
    private val bg = Color.rgb(8, 19, 32)
    private val panelColor = Color.rgb(22, 35, 53)
    private val normal = Color.rgb(239, 246, 255)
    private val muted = Color.rgb(165, 183, 202)
    private val mint = Color.rgb(59, 228, 173)
    private val red = Color.rgb(255, 108, 118)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + .5f).toInt()
    private fun text(value: String, size: Float = 14f, color: Int = normal, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
    private fun panel(): LinearLayout = LinearLayout(ctx).apply {
        orientation = VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(panelColor); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(51, 73, 94))
        }
    }
    private class SocRing(ctx: Context) : View(ctx) {
        var soc: Int? = null
            set(value) { field = value; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(44, 66, 82); strokeWidth = 11f; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(59, 228, 173); strokeWidth = 11f; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(239, 246, 255); textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx=width/2f;val cy=height/2f
            val r=kotlin.math.min(width,height)*0.35f
            val rect=RectF(cx-r,cy-r,cx+r,cy+r)
            canvas.drawArc(rect, -90f, 360f, false, track)
            soc?.let { canvas.drawArc(rect,-90f,it*3.6f,false,bar) }
            ink.textSize = r*0.48f
            canvas.drawText(soc?.let { "$it%" } ?: "—",cx,cy+ink.textSize*.22f,ink)
            ink.textSize = r*.19f
            canvas.drawText("SOC GLOBAL",cx,cy+r*.5f,ink)
        }
    }
    private fun metric(label: String, value: String, warning: Boolean = false,
                       source:String="") = LinearLayout(ctx).apply {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.rgb(29, 45, 65)); cornerRadius=dp(10).toFloat()
        }
        setPadding(dp(9),dp(9),dp(9),dp(9))
        addView(text(label,11f,muted))
        addView(text(value,16f,if(warning) red else normal,true))
        if(source.isNotBlank())addView(text(source,11f,muted))
    }

    init { orientation = VERTICAL }
    fun bind(devices: JSONArray, selectBms: (String)->Unit,
             bmsName:(String,Int)->String = {_,index -> "BMS ${index+1}"}) {
        removeAllViews()
        val entries=(0 until devices.length()).mapNotNull { devices.optJSONObject(it) }
        val live=entries.filter { it.optBoolean("available") && it.optJSONObject("values") != null }
        val allLive=live.size == entries.size && entries.isNotEmpty()
        val vals=live.mapNotNull { it.optJSONObject("values") }
        val haveCap=allLive && vals.isNotEmpty() && vals.all { it.optDouble("nominal_capacity_ah",0.0)>0.0 }
        val soc=if(!allLive) null else if(haveCap) {
            val total=vals.sumOf{it.optDouble("nominal_capacity_ah")}
            (vals.sumOf{it.optDouble("soc_pct")*it.optDouble("nominal_capacity_ah")}/total)
                .toInt().coerceIn(0,100)
        } else if(vals.all { it.has("soc_pct") })
            vals.map{it.optInt("soc_pct")}.average().toInt().coerceIn(0,100)
        else null
        fun average(key:String,unit:String,dec:Int):String {
            if(vals.isEmpty() || vals.any {!it.has(key)})return "—"
            return String.format(Locale.FRANCE,"%." + dec + "f %s",
                vals.sumOf{it.optDouble(key)}/vals.size,unit)
        }
        fun total(key: String, unit: String, dec: Int): String {
            val values = vals.map { item ->
                if (!item.has(key) || item.isNull(key)) Double.NaN
                else item.optDouble(key, Double.NaN)
            }
            val totalValue = JkAggregateMath.sumWhenComplete(values, entries.size)
                ?: return "—"
            return String.format(Locale.FRANCE, "%." + dec + "f %s", totalValue, unit)
        }
        // The hottest valid temperature can come from ANY of the three ESP32
        // readers, not just the currently selected detail page. Include the
        // MOS sensor and all available battery probes, but no stale/offline BMS.
        val temperatureKeys=listOf("temp1_c","temp2_c","temp3_c",
            "temp4_c","temp5_c","mos_temp_c")
        val temperatures=live.flatMap { bms ->
            val values=bms.optJSONObject("values") ?: return@flatMap emptyList<JkTemperatureSample>()
            val mac=bms.optString("mac")
            val name=bmsName(mac,entries.indexOf(bms))
            temperatureKeys.mapNotNull { key ->
                if(!values.has(key) || values.isNull(key))null else
                    JkTemperatureSample(name,values.optDouble(key,Double.NaN))
            }
        }
        val hottest=JkTemperatureSummary.hottest(temperatures)
        val hottestValue=hottest?.let {
            String.format(Locale.FRANCE,"%.1f °C",it.celsius)
        } ?: "—"
        val hottestSource=hottest?.let {
            it.label + if(allLive) "" else " · BMS en ligne seulement"
        } ?: ""
        val alarmed=entries.filter {
            it.optBoolean("available") && it.optJSONObject("values")?.optBoolean("alarm_active") == true
        }
        val alarms=when {
            alarmed.isNotEmpty() -> alarmed.size.toString()+" BMS"
            !allLive -> "À vérifier"
            else -> "Aucune"
        }
        val board=panel()
        board.addView(text("Vue globale · ${live.size}/${entries.size} BMS en ligne",16f,normal,true))
        val line=LinearLayout(ctx).apply { orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        val ring=SocRing(ctx).apply { this.soc = soc }
        line.addView(ring, LayoutParams(dp(144),dp(165)))
        val metrics=LinearLayout(ctx).apply { orientation=VERTICAL }
        for ((label,value,warn) in listOf(
            Triple("Tension",average("voltage_v","V",2),false),
            Triple("Puissance totale",total("power_w","W",0),false),
            Triple("Courant total",total("current_a","A",1),false),
            Triple("Alarmes",alarms,alarmed.isNotEmpty())
        )) {
            metrics.addView(metric(label,value,warn),LayoutParams(-1,-2).apply{bottomMargin=dp(5)})
        }
        metrics.addView(metric(
            "Température max", hottestValue,
            hottest?.celsius?.let { it>=55.0 } == true, hottestSource
        ),LayoutParams(-1,-2).apply { bottomMargin=dp(5) })
        line.addView(metrics,LayoutParams(0,-2,1f))
        board.addView(line)
        board.addView(text(if(allLive) "Données fraîches · ${if(haveCap) "SOC pondéré" else "SOC moyen"} · tension moyenne · A/W additionnés."
          else "Mesures incomplètes : un ou plusieurs BMS sont hors ligne.",11f,muted))
        if(alarmed.isNotEmpty()){
            for(bms in alarmed){
                val index=entries.indexOf(bms)+1
                val mac=bms.optString("mac")
                val jump=text("⚠ ${bmsName(mac,index-1)} : alarme active · voir la batterie",13f,red,true)
                jump.setPadding(0,dp(6),0,dp(6))
                jump.setOnClickListener{selectBms(mac)}
                board.addView(jump)
            }
        }
        addView(board,LayoutParams(-1,-2))
        entries.forEachIndexed { index, bms ->
            val mac=bms.optString("mac")
            val v=bms.optJSONObject("values")
            val card=panel()
            card.addView(text(bmsName(mac,index)+" · "+ if(bms.optBoolean("available"))"En ligne" else "Hors ligne",
                15f,if(bms.optBoolean("available"))mint else muted,true))
            val summary=if(bms.optBoolean("available") && v!=null)
                "${v.optInt("soc_pct")}%  ·  ${String.format(Locale.FRANCE,"%.2f",v.optDouble("voltage_v"))} V" +
                  "  ·  ${String.format(Locale.FRANCE,"%.0f",v.optDouble("power_w"))} W" else "Mesures indisponibles"
            card.addView(text(summary,14f))
            val alarm=when{
                !bms.optBoolean("available")->"Alarmes inconnues (hors ligne)"
                v?.optBoolean("alarm_active")==true->"⚠ Une ou plusieurs alarmes"
                else -> "Aucune alarme"
            }
            card.addView(text(alarm,12f,if(v?.optBoolean("alarm_active")==true)red else muted))
            card.setOnClickListener{selectBms(mac)}
            addView(card,LayoutParams(-1,-2).apply{topMargin=dp(10)})
        }
    }
}
