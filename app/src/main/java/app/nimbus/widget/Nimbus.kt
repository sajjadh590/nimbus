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

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("nimbus", Context.MODE_PRIVATE)
    var celsius: Boolean get() = p.getBoolean("celsius", true); set(v) { p.edit().putBoolean("celsius", v).apply() }
    var h24: Boolean get() = p.getBoolean("h24", true); set(v) { p.edit().putBoolean("h24", v).apply() }
    var dynamic: Boolean get() = p.getBoolean("dynamic", true); set(v) { p.edit().putBoolean("dynamic", v).apply() }
    var city: String get() = p.getString("city", "Mashhad")!!; set(v) { p.edit().putString("city", v).apply() }
    var lat: Float get() = p.getFloat("lat", 36.26f); set(v) { p.edit().putFloat("lat", v).apply() }
    var lon: Float get() = p.getFloat("lon", 59.61f); set(v) { p.edit().putFloat("lon", v).apply() }
    var intervalMin: Int get() = p.getInt("intervalMin", 30); set(v) { p.edit().putInt("intervalMin", v).apply() }
    var modules: Set<String> get() = p.getStringSet("modules", setOf("chips", "spark", "sunbar", "fiveday", "moon"))!!; set(v) { p.edit().putStringSet("modules", v).apply() }
    fun toggle(m: String, on: Boolean) { val s = modules.toMutableSet(); if (on) s.add(m) else s.remove(m); modules = s }
}

data class Weather(val temp: Float, val feels: Float, val hum: Int, val wind: Float, val pressure: Float, val code: Int, val uv: Float, val hourly: List<Float>)

object WeatherRepo {
    private val exec = Executors.newSingleThreadExecutor()
    private var mem: Weather? = null
    fun refresh(ctx: Context, cb: (Weather) -> Unit) {
        mem?.let(cb)
        exec.execute {
            runCatching {
                val p = Prefs(ctx)
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,surface_pressure,uv_index&hourly=temperature_2m&timezone=auto&forecast_days=1"
                val json = JSONObject(get(url))
                val c = json.getJSONObject("current")
                val h = json.getJSONObject("hourly")
                val temps = (0 until min(10, h.getJSONArray("temperature_2m").length())).map { h.getJSONArray("temperature_2m").getDouble(it).toFloat() }
                mem = Weather(c.getDouble("temperature_2m").toFloat(), c.getDouble("apparent_temperature").toFloat(), c.getInt("relative_humidity_2m"), c.getDouble("wind_speed_10m").toFloat(), c.getDouble("surface_pressure").toFloat(), c.getInt("weather_code"), c.getDouble("uv_index").toFloat(), temps)
                mem?.let(cb)
            }
        }
    }
    private fun get(u: String): String = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }.inputStream.bufferedReader().readText()
}

object NimbusRenderer {
    fun render(ctx: Context, w: Weather, p: Prefs, wdp: Int, hdp: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val W = (wdp * d).toInt(); val H = (hdp * d).toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val r = 28f * d
        cv.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), r, r, Path.Direction.CW) })
        cv.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0xFF0B0D10.toInt(), 0xFF12151A.toInt(), Shader.TileMode.CLAMP) })
        cv.drawRoundRect(RectF(1f * d, 1f * d, W - 1f * d, H - 1f * d), r, r, Paint().apply { color = 0x1AFFFFFF; style = Paint.Style.STROKE; strokeWidth = 1.2f * d })
        val m = 18f * d
        cv.drawText("${w.temp.toInt()}°", m, H * .5f, 52f * d, Paint().apply { isAntiAlias = true; textSize = 52f * d; color = 0xFFF7F9FB.toInt(); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        cv.drawText("Feels ${w.feels.toInt()}°", m, H * .5f + 24f * d, 12f * d, Paint().apply { isAntiAlias = true; textSize = 12f * d; color = 0xFFA9B4C0.toInt() })
        cv.drawText(p.city, m, H - m, 14f * d, Paint().apply { isAntiAlias = true; textSize = 14f * d; color = 0xFFF7F9FB.toInt() })
        return bmp
    }
}

class NimbusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { update(ctx, mgr, it) }; schedule(ctx) }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = update(ctx, mgr, id)
    companion object {
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val opts = mgr.getAppWidgetOptions(id); val p = Prefs(ctx)
            val views = android.widget.RemoteViews(ctx.packageName, R.layout.widget_nimbus)
            views.setFloat(R.id.clock, "setTextSize", if (opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250) < 200) 26f else 40f)
            views.setCharSequence(R.id.clock, "setFormat24Hour", if (p.h24) "HH:mm" else "h:mm")
            views.setCharSequence(R.id.clock, "setFormat12Hour", "h:mm")
            mgr.updateAppWidget(id, views)
            val wdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
            val hdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
            WeatherRepo.refresh(ctx) { w ->
                val v2 = android.widget.RemoteViews(ctx.packageName, R.layout.widget_nimbus)
                v2.setImageViewBitmap(R.id.art, NimbusRenderer.render(ctx, w, p, wdp, hdp))
                mgr.updateAppWidget(id, v2)
            }
        }
        fun schedule(ctx: Context) {
            val min = Prefs(ctx).intervalMin
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, NimbusWidgetProvider::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (min <= 0) am.cancel(pi) else am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + min * 60000L, min * 60000L, pi)
        }
    }
}

class BootReceiver : BroadcastReceiver() { override fun onReceive(ctx: Context, i: Intent) = NimbusWidgetProvider.schedule(ctx) }

class StudioActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Prefs(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48); setBackgroundColor(0xFF0B0D10.toInt()) }
        fun title(t: String) = col.addView(TextView(this).apply { text = t; setTextColor(0xFFA9B4C0.toInt()); setPadding(0, 28, 0, 8) })
        title("OPTIONS")
        col.addView(Switch(this).apply { text = "Celsius"; setTextColor(0xFFF7F9FB.toInt()); isChecked = p.celsius; setOnCheckedChangeListener { _, on -> p.celsius = on; apply() } })
        col.addView(Switch(this).apply { text = "24-hour clock"; setTextColor(0xFFF7F9FB.toInt()); isChecked = p.h24; setOnCheckedChangeListener { _, on -> p.h24 = on; apply() } })
        setContentView(ScrollView(this).apply { addView(col) })
    }
    private fun apply() {
        val mgr = AppWidgetManager.getInstance(this)
        mgr.getAppWidgetIds(ComponentName(this, NimbusWidgetProvider::class.java)).forEach { NimbusWidgetProvider.update(this, mgr, it) }
    }
}