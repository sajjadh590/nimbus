package app.nimbus.widget

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.*

/* ================= Prefs (incl. refresh interval) ================= */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("nimbus", Context.MODE_PRIVATE)
    var celsius get() = p.getBoolean("celsius", true); set(v) = p.edit().putBoolean("celsius", v).apply()
    var h24 get() = p.getBoolean("h24", true); set(v) = p.edit().putBoolean("h24", v).apply()
    var dynamic get() = p.getBoolean("dynamic", true); set(v) = p.edit().putBoolean("dynamic", v).apply()
    var city get() = p.getString("city", "Mashhad")!!; set(v) = p.edit().putString("city", v).apply()
    var lat get() = p.getFloat("lat", 36.26f); set(v) = p.edit().putFloat("lat", v).apply()
    var lon get() = p.getFloat("lon", 59.61f); set(v) = p.edit().putFloat("lon", v).apply()
    var intervalMin get() = p.getInt("intervalMin", 30); set(v) = p.edit().putInt("intervalMin", v).apply()
    var modules: Set<String>
        get() = p.getStringSet("modules", setOf("chips", "spark", "sunbar", "fiveday", "moon"))!!
        set(v) = p.edit().putStringSet("modules", v).apply()
    fun toggle(m: String, on: Boolean) { val s = modules.toMutableSet(); if (on) s.add(m) else s.remove(m); modules = s }
}

/* ================= Weather model + Open-Meteo repo ================= */
data class Day(val ts: Long, val code: Int, val max: Float, val min: Float)
data class Weather(
    val temp: Float, val feels: Float, val hum: Int, val wind: Float, val windDir: Float,
    val pressure: Float, val code: Int, val aqi: Int?, val uv: Float,
    val sunrise: Long, val sunset: Long,
    val hourly: List<Pair<Long, Float>>, val hourlyCode: List<Int>, val daily: List<Day>
) {
    val sunriseHour get() = hourOf(sunrise); val sunsetHour get() = hourOf(sunset)
    private fun hourOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
        .let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f }
}

object WeatherRepo {
    private val exec = Executors.newSingleThreadExecutor()
    private var mem: Weather? = null
    private val tfmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    private val dfmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun refresh(ctx: Context, cb: (Weather) -> Unit) {
        val cache = File(ctx.cacheDir, "weather.json")
        if (mem == null && cache.exists()) runCatching { mem = parse(cache.readText()) }
        mem?.let(cb)                                   // instant paint from cache
        exec.execute { runCatching {
            val p = Prefs(ctx)
            val json = get("https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code," +
                "wind_speed_10m,wind_direction_10m,surface_pressure" +
                "&hourly=temperature_2m,weather_code&daily=sunrise,sunset,uv_index_max," +
                "weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=6")
            val aqi = runCatching {
                JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${p.lat}" +
                    "&longitude=${p.lon}&current=us_aqi"))
                    .getJSONObject("current").optInt("us_aqi", -1).takeIf { it >= 0 }
            }.getOrNull()
            val w = parse(json, aqi)
            cache.writeText(json)                      // tiny JSON, eMMC-friendly
            mem = w; cb(w)
        } }
    }

    private fun get(u: String): String = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
    }.inputStream.bufferedReader().readText()

    fun parse(json: String, aqi: Int? = null): Weather {
        val r = JSONObject(json)
        val c = r.getJSONObject("current"); val h = r.getJSONObject("hourly"); val d = r.getJSONObject("daily")
        val now = System.currentTimeMillis() / 3600000
        val hTs = (0 until h.getJSONArray("time").length()).map { tfmt.parse(h.getJSONArray("time").getString(it))!!.time }
        val from = (hTs.indexOfFirst { it / 3600000 >= now }).coerceAtLeast(0)
        val daily = (0 until d.getJSONArray("time").length()).map {
            Day(dfmt.parse(d.getJSONArray("time").getString(it))!!.time,
                d.getJSONArray("weather_code").getInt(it),
                d.getJSONArray("temperature_2m_max").getDouble(it).toFloat(),
                d.getJSONArray("temperature_2m_min").getDouble(it).toFloat())
        }
        val n = min(10, hTs.size - from)
        return Weather(
            c.getDouble("temperature_2m").toFloat(), c.getDouble("apparent_temperature").toFloat(),
            c.getInt("relative_humidity_2m"), c.getDouble("wind_speed_10m").toFloat(),
            c.getDouble("wind_direction_10m").toFloat(), c.getDouble("surface_pressure").toFloat(),
            c.getInt("weather_code"), aqi, d.getJSONArray("uv_index_max").optDouble(0, 0.0).toFloat(),
            tfmt.parse(d.getJSONArray("sunrise").getString(0))!!.time,
            tfmt.parse(d.getJSONArray("sunset").getString(0))!!.time,
            (0 until n).map { hTs[from + it] to h.getJSONArray("temperature_2m").getDouble(from + it).toFloat() },
            (0 until n).map { h.getJSONArray("weather_code").getInt(from + it) }, daily)
    }
}

enum class Cond { CLEAR, PARTLY, CLOUDY, RAIN, STORM, SNOW, FOG }
fun cond(code: Int) = when (code) {
    0, 1 -> Cond.CLEAR; 2 -> Cond.PARTLY; 45, 48 -> Cond.FOG
    in 51..67, in 80..82 -> Cond.RAIN; in 71..77, 85, 86 -> Cond.SNOW
    in 95..99 -> Cond.STORM; else -> Cond.CLOUDY
}
fun condName(c: Cond) = when (c) {
    Cond.CLEAR -> "Clear Sky"; Cond.PARTLY -> "Partly Cloudy"; Cond.CLOUDY -> "Cloudy"
    Cond.RAIN -> "Rain"; Cond.STORM -> "Storm"; Cond.SNOW -> "Snow"; Cond.FOG -> "Fog"
}
fun accent(c: Cond) = when (c) {
    Cond.CLEAR, Cond.PARTLY -> 0xFFFFA028.toInt(); Cond.CLOUDY -> 0xFF93A3B4.toInt()
    Cond.RAIN -> 0xFF58A6FF.toInt(); Cond.STORM -> 0xFF9B8CFF.toInt()
    Cond.SNOW -> 0xFFCFE6FA.toInt(); Cond.FOG -> 0xFFB9C2CB.toInt()
}
fun moonPhaseName(): String {
    val syn = 29.53058867
    val ph = (((System.currentTimeMillis() - 947198400000.0) / 86400000.0) % syn + syn) % syn
    return when { ph < 1.85 -> "New Moon"; ph < 5.54 -> "Waxing Crescent"; ph < 9.23 -> "First Quarter"
        ph < 12.92 -> "Waxing Gibbous"; ph < 16.61 -> "Full Moon"; ph < 20.30 -> "Waning Gibbous"
        ph < 23.99 -> "Last Quarter"; ph < 27.68 -> "Waning Crescent"; else -> "New Moon" }
}

/* ================= Size solver ================= */
enum class SizeClass { GLANCE, STRIP, FLAGSHIP, PRO;
    companion object {
        fun from(o: Bundle): SizeClass {
            val w = cells(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250))
            val h = cells(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180))
            return when { w <= 2 && h <= 2 -> GLANCE; h <= 2 -> STRIP; w <= 4 && h <= 3 -> FLAGSHIP; else -> PRO }
        }
        private fun cells(dp: Int) = max(1, round((dp + 30f) / 70f).toInt())
    }
}

/* ================= Renderer (the design, drawn once per refresh) ================= */
object NimbusRenderer {
    private const val TEXT = 0xFFF7F9FB.toInt(); private const val TEXT2 = 0xFFA9B4C0.toInt()
    private const val TEXT3 = 0xFF8C97A4.toInt(); private const val HAIR = 0x1AFFFFFF
    private const val GLASS = 0x0DFFFFFF

    fun render(ctx: Context, w: Weather, p: Prefs, sc: SizeClass, wdp: Int, hdp: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val W = (wdp * d).toInt(); val H = (hdp * d).toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        fun dp(v: Float) = v * d
        val hour = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f }
        val night = hour < w.sunriseHour || hour >= w.sunsetHour
        val c = cond(w.code); val acc = accent(c)

        // card clip + dynamic base
        val r = dp(28f)
        cv.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), r, r, Path.Direction.CW) })
        val base = if (p.dynamic) skyColors(w, hour) else intArrayOf(0xFF0B0D10.toInt(), 0xFF12151A.toInt())
        cv.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), base, null, Shader.TileMode.CLAMP) })

        // ambient: stars / key light
        if (night) { val rnd = Random(42); repeat(50) {
            cv.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H * .7f, dp(.7f),
                Paint().apply { color = 0xFFFFFFFF.toInt(); alpha = 40 + rnd.nextInt(90) }) } }
        else cv.drawPaint(Paint().apply { shader = RadialGradient(W * .35f, -H * .2f, H.toFloat(), 0x26FFFFFF, 0, Shader.TileMode.CLAMP) })

        // horizon silhouette
        if (sc != SizeClass.GLANCE) cv.drawPath(Path().apply {
            moveTo(0f, H.toFloat()); lineTo(0f, H - dp(34f)); lineTo(W * .18f, H - dp(62f))
            lineTo(W * .34f, H - dp(40f)); lineTo(W * .52f, H - dp(70f)); lineTo(W * .7f, H - dp(38f))
            lineTo(W * .86f, H - dp(58f)); lineTo(W.toFloat(), H - dp(32f)); lineTo(W.toFloat(), H.toFloat()); close()
        }, Paint().apply { color = 0xCC07090C.toInt() })

        // hairline border
        cv.drawRoundRect(RectF(dp(1f), dp(1f), W - dp(1f), H - dp(1f)), r, r,
            Paint().apply { color = HAIR; style = Paint.Style.STROKE; strokeWidth = dp(1.2f) })

        val m = dp(18f)
        when (sc) {
            SizeClass.GLANCE -> {
                orb(cv, W * .72f, H * .3f, dp(16f), night, acc, d)
                cv.text(tempStr(w, p), m, H * .66f, dp(40f), TEXT, light = true)
                cv.text(condName(c), m, H * .66f + dp(20f), dp(11f), TEXT2)
            }
            SizeClass.STRIP -> {
                cv.text(p.city, m, dp(56f), dp(12f), TEXT2)
                cv.text(tempStr(w, p), W - m, dp(26f), dp(32f), TEXT, light = true, align = Paint.Align.RIGHT)
                cv.text("Feels ${tempStr(w, p, w.feels)}", W - m, dp(56f), dp(10f), TEXT3, align = Paint.Align.RIGHT)
                if ("spark" in p.modules) sparkline(cv, w, acc, m, H * .66f, W - 2 * m, dp(24f), d)
            }
            else -> { // FLAGSHIP + PRO
                cv.text(p.city, m, dp(80f), dp(14f), TEXT)
                cv.text(condName(c), m, dp(96f), dp(11f), TEXT2)
                if ("chips" in p.modules)
                    if (sc == SizeClass.PRO) chips(cv, w, W * .44f, dp(14f), W * .56f - m, dp(44f), d, 3)
                    else chips(cv, w, W * .44f, dp(14f), W * .56f - m, dp(46f), d, 4)
                cv.text(tempStr(w, p), m, H * .55f, dp(52f), TEXT, light = true)
                cv.text("Feels like ${tempStr(w, p, w.feels)}", m, H * .55f + dp(20f), dp(11f), TEXT2)
                orb(cv, W * .44f, H * .47f, dp(if (sc == SizeClass.PRO) 24f else 30f), night, acc, d)
                if ("spark" in p.modules) sparkline(cv, w, acc, W * .60f, H * .45f, W * .36f, dp(22f), d)
                if (sc == SizeClass.PRO && "fiveday" in p.modules) fiveDay(cv, w, p, m, H * .68f, W - 2 * m, d)
                if ("moon" in p.modules) {
                    cv.text(moonPhaseName(), W - m, H - dp(62f), dp(9f), TEXT3, align = Paint.Align.RIGHT) }
                if ("sunbar" in p.modules) sunBar(cv, w, m, H - dp(56f), W - 2 * m, dp(42f), d)
            }
        }
        return bmp
    }

    private fun skyColors(w: Weather, h: Float) = when {
        h < w.sunriseHour - 1.5f || h >= w.sunsetHour + 1.5f -> intArrayOf(0xFF05070C.toInt(), 0xFF0C1626.toInt())
        h < w.sunriseHour -> intArrayOf(0xFF101C33.toInt(), 0xFF6E4530.toInt())
        h < w.sunriseHour + 2 -> intArrayOf(0xFF223850.toInt(), 0xFFB07A4A.toInt())
        h < 17f -> intArrayOf(0xFF101820.toInt(), 0xFF1C2B3D.toInt())
        h < w.sunsetHour -> intArrayOf(0xFF141C2A.toInt(), 0xFF5A4034.toInt())
        else -> intArrayOf(0xFF1A2138.toInt(), 0xFF8A5040.toInt())
    }

    private fun tempStr(w: Weather, p: Prefs, v: Float = w.temp) =
        "${((if (p.celsius) v else v * 9f / 5f + 32f)).toInt()}°"

    private fun Canvas.text(t: String, x: Float, y: Float, size: Float, color: Int,
                            light: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        drawText(t, x, y, Paint().apply {
            isAntiAlias = true; textSize = size; this.color = color; textAlign = align
            typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif-medium", Typeface.NORMAL) })

    private fun orb(cv: Canvas, x: Float, y: Float, r: Float, night: Boolean, acc: Int, d: Float) {
        cv.drawCircle(x, y, r * 2.4f, Paint().apply {
            shader = RadialGradient(x, y, r * 2.4f, if (night) 0x33BFD3E6 else 0x59FFA028, 0, Shader.TileMode.CLAMP) })
        if (!night) cv.drawCircle(x, y, r, Paint().apply {
            shader = RadialGradient(x - r * .3f, y - r * .3f, r * 1.6f, 0xFFFFD98A.toInt(), acc, Shader.TileMode.CLAMP) })
        else {
            val l = cv.saveLayer(x - r * 2, y - r * 2, x + r * 2, y + r * 2, null)
            cv.drawCircle(x, y, r, Paint().apply { color = 0xFFE8ECF2.toInt() })
            cv.drawCircle(x + r * .45f, y - r * .2f, r * .9f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            cv.restoreToCount(l)
        }
    }

    private fun chips(cv: Canvas, w: Weather, x0: Float, y: Float, totalW: Float, h: Float, d: Float, perRow: Int) {
        val aqiQ = when { (w.aqi ?: 0) <= 50 -> "Good"; (w.aqi ?: 0) <= 100 -> "Moderate"; else -> "Unhealthy" }
        val uvQ = when { w.uv < 3 -> "Low"; w.uv < 6 -> "Moderate"; w.uv < 8 -> "High"; else -> "Very High" }
        val items = listOf(
            Triple("AQI", (w.aqi ?: 51).toString(), aqiQ), Triple("UV Index", w.uv.toInt().toString(), uvQ),
            Triple("Humidity", "${w.hum}%", if (w.hum < 30) "Dry" else "Normal"),
            Triple("Wind", compass(w.windDir), "${w.wind.toInt()} km/h"),
            Triple("Pressure", w.pressure.toInt().toString(), "hPa"), Triple("Rain", "20%", "chance"))
        val gap = 6f * d
        items.forEachIndexed { i, it ->
            val cw = (totalW - gap * (perRow - 1)) / perRow
            val x = x0 + (i % perRow) * (cw + gap); val yy = y + (i / perRow) * (h + 6f * d)
            cv.drawRoundRect(RectF(x, yy, x + cw, yy + h), 12f * d, 12f * d, Paint().apply { color = GLASS })
            cv.drawRoundRect(RectF(x, yy, x + cw, yy + h), 12f * d, 12f * d,
                Paint().apply { color = HAIR; style = Paint.Style.STROKE; strokeWidth = d })
            cv.text(it.first, x + 8f * d, yy + h * .34f, 8f * d, TEXT3)
            cv.text(it.second, x + 8f * d, yy + h * .66f, 10.5f * d, TEXT)
            cv.text(it.third, x + 8f * d, yy + h * .94f, 7.5f * d, TEXT3)
        }
    }
    private fun compass(deg: Float) = listOf("N","NE","E","SE","S","SW","W","NW")[(deg / 45f + .5f).toInt() % 8]

    private fun sparkline(cv: Canvas, w: Weather, acc: Int, x: Float, yMid: Float, wdt: Float, amp: Float, d: Float) {
        if (w.hourly.size < 2) return
        val lo = w.hourly.minOf { it.second }; val span = max(1f, w.hourly.maxOf { it.second } - lo)
        val pts = w.hourly.mapIndexed { i, (_, t) ->
            PointF(x + i * wdt / (w.hourly.size - 1), yMid + amp / 2 - (t - lo) * amp / span) }
        cv.drawPath(Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size - 1) quadraticTo(pts[i].x, pts[i].y,
                (pts[i].x + pts[i + 1].x) / 2, (pts[i].y + pts[i + 1].y) / 2)
        }, Paint().apply { color = acc; style = Paint.Style.STROKE; strokeWidth = 2.4f * d
            isAntiAlias = true; setShadowLayer(6f * d, 0f, 0f, acc) })
        pts.forEachIndexed { i, pt ->
            cv.drawCircle(pt.x, pt.y, 3f * d, Paint().apply { color = if (i == 0) 0xFFFFFFFF.toInt() else acc })
            val hr = Calendar.getInstance().apply { timeInMillis = w.hourly[i].first }.get(Calendar.HOUR_OF_DAY)
            cv.text(if (i == 0) "Now" else "$hr:00", pt.x, pt.y - 15f * d, 8f * d, TEXT2, align = Paint.Align.CENTER)
            cv.text("${w.hourly[i].second.toInt()}°", pt.x, pt.y - 6f * d, 9f * d, TEXT, align = Paint.Align.CENTER)
            miniIcon(cv, cond(w.hourlyCode[i]), pt.x, pt.y + 13f * d, 4.5f * d)
        }
    }

    private fun miniIcon(cv: Canvas, c: Cond, x: Float, y: Float, r: Float) {
        val col = accent(c)
        cv.drawCircle(x, y, r, Paint().apply { color = col })
        if (c == Cond.CLEAR || c == Cond.PARTLY) for (a in 0 until 8) { val t = a * PI / 4
            cv.drawLine((x + cos(t) * r * 1.4f).toFloat(), (y + sin(t) * r * 1.4f).toFloat(),
                (x + cos(t) * r * 1.9f).toFloat(), (y + sin(t) * r * 1.9f).toFloat(),
                Paint().apply { color = col; strokeWidth = r * .35f }) }
    }

    private fun fiveDay(cv: Canvas, w: Weather, p: Prefs, x: Float, y: Float, wdt: Float, d: Float) {
        val days = arrayOf("Su","Mo","Tu","We","Th","Fr","Sa")
        w.daily.take(5).forEachIndexed { i, day ->
            val cx = x + i * wdt / 4f
            cv.text(if (i == 0) "Today" else
                days[Calendar.getInstance().apply { timeInMillis = day.ts }.get(Calendar.DAY_OF_WEEK) - 1],
                cx, y, 9f * d, TEXT2, align = Paint.Align.CENTER)
            miniIcon(cv, cond(day.code), cx, y + 11f * d, 4.5f * d)
            cv.text("${day.max.toInt()}/${day.min.toInt()}°", cx, y + 25f * d, 9f * d, TEXT, align = Paint.Align.CENTER)
        }
    }

    private fun sunBar(cv: Canvas, w: Weather, x: Float, y: Float, wdt: Float, h: Float, d: Float) {
        cv.drawRoundRect(RectF(x, y, x + wdt, y + h), h / 2, h / 2, Paint().apply { color = GLASS })
        cv.drawRoundRect(RectF(x, y, x + wdt, y + h), h / 2, h / 2,
            Paint().apply { color = HAIR; style = Paint.Style.STROKE; strokeWidth = d })
        val fmt = java.text.SimpleDateFormat("HH:mm", Locale.US)
        horizonIcon(cv, x + 16f * d, y + h * .5f, 6f * d, 0xFF9B8CFF.toInt())
        cv.text("Sunrise", x + 30f * d, y + h * .4f, 8.5f * d, TEXT2)
        cv.text(fmt.format(Date(w.sunrise)), x + 30f * d, y + h * .78f, 11f * d, TEXT)
        horizonIcon(cv, x + wdt - 16f * d, y + h * .5f, 6f * d, 0xFFFF8A3C.toInt())
        cv.text("Sunset", x + wdt - 30f * d, y + h * .4f, 8.5f * d, TEXT2, align = Paint.Align.RIGHT)
        cv.text(fmt.format(Date(w.sunset)), x + wdt - 30f * d, y + h * .78f, 11f * d, TEXT, align = Paint.Align.RIGHT)
        val pw = 118f * d; val px = x + wdt / 2 - pw / 2
        cv.drawRoundRect(RectF(px, y + h * .12f, px + pw, y + h * .88f), h * .38f, h * .38f, Paint().apply { color = 0x14FFFFFF })
        cv.text("5-Day Forecast →", px + pw / 2, y + h * .62f, 9.5f * d, TEXT, align = Paint.Align.CENTER)
    }

    private fun horizonIcon(cv: Canvas, x: Float, y: Float, r: Float, color: Int) {
        cv.drawCircle(x, y, r * .8f, Paint().apply { this.color = color })
        cv.drawLine(x - r * 1.6f, y + r * .5f, x + r * 1.6f, y + r * .5f,
            Paint().apply { this.color = color; strokeWidth = r * .3f })
    }
}

/* ================= Provider + scheduler (your refresh-interval idea) ================= */
class NimbusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, mgr, it) }; schedule(ctx)
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = update(ctx, mgr, id)
    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == A_REFRESH) AppWidgetManager.getInstance(ctx).let { m ->
            m.getAppWidgetIds(ComponentName(ctx, NimbusWidgetProvider::class.java)).forEach { update(ctx, m, it) } }
    }
    companion object {
        const val A_REFRESH = "app.nimbus.REFRESH"
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val opts = mgr.getAppWidgetOptions(id); val sc = SizeClass.from(opts); val p = Prefs(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_nimbus)
            views.setFloat(R.id.clock, "setTextSize", when (sc) { SizeClass.GLANCE -> 26f; SizeClass.STRIP -> 30f; else -> 40f })
            views.setString(R.id.clock, "setFormat24Hour", if (p.h24) "HH:mm" else "h:mm")
            views.setString(R.id.clock, "setFormat12Hour", "h:mm")
            views.setOnClickPendingIntent(R.id.art, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, StudioActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            mgr.updateAppWidget(id, views)
            val wdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
            val hdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
            WeatherRepo.refresh(ctx) { w ->          // cache-first, network in background
                val v2 = RemoteViews(ctx.packageName, R.layout.widget_nimbus)
                v2.setImageViewBitmap(R.id.art, NimbusRenderer.render(ctx, w, p, sc, wdp, hdp))
                v2.setString(R.id.art, "setContentDescription", "${w.temp.toInt()} degrees, ${condName(cond(w.code))}")
                mgr.updateAppWidget(id, v2)
            }
        }
        fun schedule(ctx: Context) {                 // user-chosen interval; inexact = battery-safe
            val min = Prefs(ctx).intervalMin
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, NimbusWidgetProvider::class.java)
                .setAction(A_REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (min <= 0) am.cancel(pi)              // Manual mode: no alarms at all
            else am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + min * 60000L, min * 60000L, pi)
        }
    }
}
class BootReceiver : BroadcastReceiver() { override fun onReceive(ctx: Context, i: Intent) = NimbusWidgetProvider.schedule(ctx) }

/* ================= Studio (modular editor + interval picker) ================= */
class StudioActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Prefs(this)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (id != -1) setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48); setBackgroundColor(0xFF0B0D10.toInt()) }
        fun title(t: String) = col.addView(TextView(this).apply { text = t; setTextColor(0xFFA9B4C0.toInt()); setPadding(0, 28, 0, 8) })
        fun sw(label: String, get: () -> Boolean, set: (Boolean) -> Unit) =
            col.addView(Switch(this).apply { text = label; setTextColor(0xFFF7F9FB.toInt()); isChecked = get()
                setOnCheckedChangeListener { _, on -> set(on); apply() } })

        title("PRESETS")
        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mapOf("Minimal" to emptySet<String>(), "Information" to setOf("chips", "spark"),
              "Dashboard" to setOf("chips", "spark", "sunbar", "fiveday", "moon")).forEach { (n, mods) ->
            pr.addView(Button(this).apply { text = n; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF14171C.toInt())
                setOnClickListener { p.modules = mods; apply() } }) }
        col.addView(pr)

        title("MODULES")
        listOf("chips" to "Qualifier chips", "spark" to "Temp sparkline", "sunbar" to "Sunrise/Sunset bar",
               "fiveday" to "5-day forecast", "moon" to "Moon phase").forEach { (k, l) -> sw(l, { k in p.modules }, { p.toggle(k, it) }) }

        title("REFRESH INTERVAL (data + look)")
        val intervals = intArrayOf(15, 30, 60, 120, 240, 360, 720, 0)
        val labels = arrayOf("Every 15 min", "Every 30 min", "Every 1 hour", "Every 2 hours",
                             "Every 4 hours", "4x a day", "2x a day", "Manual only")
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@StudioActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(intervals.indexOf(p.intervalMin).coerceAtLeast(1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: android.view.View?, pos: Int, l: Long) {
                    if (intervals[pos] != p.intervalMin) { p.intervalMin = intervals[pos]; NimbusWidgetProvider.schedule(this@StudioActivity); apply() } }
                override fun onNothingSelected(a: AdapterView<*>?) {} } })
        col.addView(Button(this).apply { text = "Refresh now"; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF14171C.toInt())
            setOnClickListener { apply() } })

        title("OPTIONS")
        sw("Celsius", { p.celsius }, { p.celsius = it })
        sw("24-hour clock", { p.h24 }, { p.h24 = it })
        sw("Dynamic background", { p.dynamic }, { p.dynamic = it })
        setContentView(ScrollView(this).apply { addView(col) })
    }
    private fun apply() {
        val mgr = AppWidgetManager.getInstance(this)
        mgr.getAppWidgetIds(ComponentName(this, NimbusWidgetProvider::class.java)).forEach { NimbusWidgetProvider.update(this, mgr, it) }
    }
}