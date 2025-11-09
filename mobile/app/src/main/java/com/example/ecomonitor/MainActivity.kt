package com.example.ecomonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.os.LocaleListCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private val baseUrl = "http://10.0.2.2:8000" // change to http://192.168.0.205:8000 on real device

    // Required views
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var btnConsult: Button
    private lateinit var out: TextView
    private lateinit var progress: LinearProgressIndicator

    // Optional views (null‑safe)
    private var city: MaterialAutoCompleteTextView? = null
    private var tvPlace: TextView? = null
    private var btnUseCurrent: Button? = null
    private var tvTempValue: TextView? = null
    private var tvAqiValue: TextView? = null
    private var tvAqiStatus: TextView? = null
    private var tvPm25: TextView? = null
    private var tvPm10: TextView? = null
    private var tvRainProb: TextView? = null
    private var tvRainSum: TextView? = null
    private var chipRain: Chip? = null
    private var chipAir: Chip? = null
    private var chipFlood: Chip? = null
    private var btnToggleRaw: Button? = null
    private var btnLanguage: Button? = null

    private lateinit var cityAdapter: ArrayAdapter<PlaceSuggestion>
    private val debounce = Debouncer(350)
    private val suggestionsCache = LruCache<String, List<PlaceSuggestion>>(16)
    private var cityQueryCall: Call? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
                || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        if (ok) getCurrentLocation() else Toast.makeText(this, getString(R.string.location_permission_denied), Toast.LENGTH_SHORT).show()
    }

    data class PlaceSuggestion(
        val name: String?, val country: String?, val admin1: String?,
        val lat: Double?, val lon: Double?
    ) {
        override fun toString(): String = listOfNotNull(name, admin1, country).joinToString(", ")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Global crash logger to UI
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            runOnUiThread { findViewById<TextView>(R.id.out)?.text = "FATAL\n" + android.util.Log.getStackTraceString(e) }
        }
        setContentView(R.layout.activity_main)

        // Bind required
        etLat = findViewById(R.id.lat)
        etLon = findViewById(R.id.lon)
        btnConsult = findViewById(R.id.btn)
        out = findViewById(R.id.out)
        progress = findViewById(R.id.progress)

        // Bind optional
        city = findViewById(R.id.city)
        tvPlace = findViewById(R.id.tvPlace)
        btnUseCurrent = findViewById(R.id.btnUseCurrent)
        tvTempValue = findViewById(R.id.tvTempValue)
        tvAqiValue = findViewById(R.id.tvAqiValue)
        tvAqiStatus = findViewById(R.id.tvAqiStatus)
        tvPm25 = findViewById(R.id.tvPm25)
        tvPm10 = findViewById(R.id.tvPm10)
        tvRainProb = findViewById(R.id.tvRainProb)
        tvRainSum = findViewById(R.id.tvRainSum)
        chipRain = findViewById(R.id.chipRain)
        chipAir = findViewById(R.id.chipAir)
        chipFlood = findViewById(R.id.chipFlood)
        btnToggleRaw = findViewById(R.id.btnToggleRaw)
        btnLanguage = findViewById(R.id.btnLanguage)

        out.visibility = View.GONE
        btnToggleRaw?.setOnClickListener {
            val showing = out.visibility == View.VISIBLE
            out.visibility = if (showing) View.GONE else View.VISIBLE
            btnToggleRaw?.text = getString(if (showing) R.string.show_raw_button else R.string.hide_raw_button)
        }

        if (etLat.text.isNullOrBlank()) etLat.setText("-23.55")
        if (etLon.text.isNullOrBlank()) etLon.setText("-46.64")
        handleNewLocation(etLat.text.toString().toDouble(), etLon.text.toString().toDouble())


        // Autocomplete
        cityAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        city?.setAdapter(cityAdapter)
        city?.setOnItemClickListener { parent, _, pos, _ ->
            val sel = parent.getItemAtPosition(pos) as PlaceSuggestion
            sel.lat?.let { etLat.setText(it.toString()) }
            sel.lon?.let { etLon.setText(it.toString()) }
            tvPlace?.text = getString(R.string.current_place_dynamic, sel.toString())
        }
        city?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerQuery(city?.text?.toString().orEmpty()); true
            } else false
        }
        city?.addTextChangedListener(SimpleTextWatcher { q -> if (q.length >= 3) debounce.submit { triggerQuery(q) } })

        btnUseCurrent?.setOnClickListener { ensureLocationPermissionThenGet() }
        btnConsult.setOnClickListener { fetchEnvironment() }
        btnLanguage?.setOnClickListener {
            val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            val newLang = if (currentLang.startsWith("pt")) "en" else "pt"
            val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(newLang)
            AppCompatDelegate.setApplicationLocales(appLocale)
        }
    }

    private fun triggerQuery(raw: String) {
        val q = raw.trim(); if (q.length < 3) return
        val norm = q.normalized()
        val cached = suggestionsCache[norm]
        if (cached != null) {
            updateCityAdapter(cached)
            return
        }
        queryPlaces(norm) { list ->
            suggestionsCache.put(norm, list)
            updateCityAdapter(list)
        }
    }

    private fun updateCityAdapter(list: List<PlaceSuggestion>) {
        cityAdapter.clear(); cityAdapter.addAll(list)
        if (list.isNotEmpty() && (city?.hasFocus() == true)) city?.showDropDown()
    }

    private fun queryPlaces(query: String, onDone: (List<PlaceSuggestion>) -> Unit) {
        cityQueryCall?.cancel()
        val url = "$baseUrl/places?q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url).build()
        cityQueryCall = client.newCall(req)
        cityQueryCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { onDone(emptyList()) }
            }

            override fun onResponse(call: Call, response: Response) {
                val list = try {
                    if (!response.isSuccessful) throw IOException("HTTP " + response.code)
                    val body = response.body?.string() ?: "{}"
                    val root = JsonParser.parseString(body).asJsonObject
                    val arr: JsonArray? = root.getAsJsonArray("results")
                    val suggestions = mutableListOf<PlaceSuggestion>()
                    arr?.forEach { el ->
                        val o: JsonObject = el.asJsonObject
                        suggestions.add(
                            PlaceSuggestion(
                                name = o.get("name")?.asString,
                                country = o.get("country")?.asString,
                                admin1 = o.get("admin1")?.asString,
                                lat = o.get("lat")?.asDouble,
                                lon = o.get("lon")?.asDouble,
                            )
                        )
                    }
                    suggestions
                } catch (e: Exception) {
                    emptyList<PlaceSuggestion>()
                }
                runOnUiThread { onDone(list) }
            }
        })
    }

    private fun ensureLocationPermissionThenGet() {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) getCurrentLocation()
        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun getCurrentLocation() {
        val fused = LocationServices.getFusedLocationProviderClient(this)
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return
        btnUseCurrent?.isEnabled = false
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    handleNewLocation(loc.latitude, loc.longitude)
                } else {
                    btnUseCurrent?.isEnabled = true
                }
            }
            .addOnFailureListener {
                btnUseCurrent?.isEnabled = true
            }
    }

    private fun handleNewLocation(lat: Double, lon: Double) {
        etLat.setText(String.format(Locale.US, "%.4f", lat))
        etLon.setText(String.format(Locale.US, "%.4f", lon))
        reverseGeocode(lat, lon) { name ->
            tvPlace?.text = getString(R.string.current_place_lat_lon, name, String.format(Locale.US, "%.4f", lat), String.format(Locale.US, "%.4f", lon))
            btnUseCurrent?.isEnabled = true
        }
    }

    private fun reverseGeocode(lat: Double, lon: Double, onDone: (String) -> Unit) {
        thread {
            try {
                val url =
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}&zoom=10&addressdetails=1"
                val req = Request.Builder().url(url).header("User-Agent", "android-app://" + packageName).build()
                client.newCall(req).execute().use { resp ->
                    val name = if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val json = JsonParser.parseString(body).asJsonObject
                        val addr = json.getAsJsonObject("address")
                        val city =
                            addr?.get("city")?.asString ?: addr?.get("town")?.asString ?: addr?.get("village")?.asString
                            ?: addr?.get("suburb")?.asString
                        val state = addr?.get("state")?.asString
                        val country = addr?.get("country")?.asString
                        listOfNotNull(city, state, country).joinToString(", ")
                            .ifBlank { json.get("display_name")?.asString ?: getString(R.string.unknown) }
                    } else getString(R.string.unknown)
                    runOnUiThread { onDone(name) }
                }
            } catch (_: Exception) {
                runOnUiThread { onDone(getString(R.string.unknown)) }
            }
        }
    }

    private fun fetchEnvironment() {
        progress.visibility = View.VISIBLE
        out.text = getString(R.string.loading)
        val la = etLat.text.toString().trim();
        val lo = etLon.text.toString().trim()
        if (la.isEmpty() || lo.isEmpty()) {
            out.text = getString(R.string.provide_lat_lon); return
        }
        thread {
            try {
                val url = "$baseUrl/environment?lat=$la&lon=$lo"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP " + resp.code)
                    val body = resp.body?.string() ?: "{}"
                    runOnUiThread {
                        out.text = body; updateDashboard(body)
                        progress.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    out.text = getString(R.string.error_message, e.message)
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_message, e.message), Snackbar.LENGTH_LONG).show()
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private object AqiColors {
        const val VERY_RED  = 0xFFD32F2F.toInt() // Very Bad
        const val RED      = 0xFFF44336.toInt() // Bad
        const val AMBER    = 0xFFFFC107.toInt() // Moderate
        const val GREEN    = 0xFF66BB6A.toInt() // Good
        const val DEEP_GRN = 0xFF2E7D32.toInt() // Very Good
    }

    enum class AqiBucket(val label: Int, val color: Int) {
        VERY_BAD(R.string.aqi_very_bad, AqiColors.VERY_RED),
        BAD(R.string.aqi_bad, AqiColors.RED),
        MODERATE(R.string.aqi_moderate, AqiColors.AMBER),
        GOOD(R.string.aqi_good, AqiColors.GREEN),
        VERY_GOOD(R.string.aqi_very_good, AqiColors.DEEP_GRN)
    }

    private fun toBucketFromUsAqi(aqi: Int?): AqiBucket? {
        if (aqi == null || aqi < 0) return null
        return when (aqi) {
            in 0..50    -> AqiBucket.VERY_GOOD
            in 51..100  -> AqiBucket.GOOD
            in 101..150 -> AqiBucket.MODERATE
            in 151..200 -> AqiBucket.BAD
            else        -> AqiBucket.VERY_BAD
        }
    }

    // Optional: EAQI fallback (1=Very Good … 6=Extremely Poor)
    private fun toBucketFromEaQi(eaqi: Int?): AqiBucket? = when (eaqi) {
        1 -> AqiBucket.VERY_GOOD
        2 -> AqiBucket.GOOD
        3 -> AqiBucket.MODERATE
        4 -> AqiBucket.BAD
        5,6 -> AqiBucket.VERY_BAD
        else -> null
    }

    private fun setAqiStatus(view: android.view.View?, bucket: AqiBucket?) {
        view ?: return
        val bg = if (bucket == null) 0xFF9E9E9E.toInt() else bucket.color
        val text = getString(bucket?.label ?: R.string.aqi_unknown)

        when (view) {
            is android.widget.TextView -> {
                view.text = text
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
                view.setBackgroundResource(R.drawable.bg_aqi_status)
                view.setTextColor(if (bucket == AqiBucket.MODERATE) Color.BLACK else Color.WHITE)
            }
            is com.google.android.material.chip.Chip -> {
                view.text = text
                view.chipBackgroundColor = android.content.res.ColorStateList.valueOf(bg)
                view.setTextColor(if (bucket == AqiBucket.MODERATE) Color.BLACK else Color.WHITE)
            }
        }
        // Accessibility
        view.contentDescription = getString(R.string.aqi_content_description, text)
    }

    private fun updateDashboard(json: String) {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val current = root.getAsJsonObject("current")
            val next = root.getAsJsonObject("next_hours")
            val risk = root.getAsJsonObject("risk")

            val temp = current.get("temperature_c")?.asDouble
            tvTempValue?.let {
                it.text = temp?.let { getString(R.string.temperature_format, it) } ?: "—"
                animateView(it)
            }

            val aqi = current.get("us_aqi")?.asInt
            val eaqi = current.get("european_aqi")?.asInt
            val bucket = toBucketFromUsAqi(aqi) ?: toBucketFromEaQi(eaqi)
            setAqiStatus(tvAqiStatus, bucket)
            fadeIn(tvAqiStatus)

            tvAqiValue?.let {
                it.text = aqi?.toString() ?: "—"
                animateView(it)
            }
            val pm25 = current.get("pm2_5")?.asDouble
            val pm10 = current.get("pm10")?.asDouble
            tvPm25?.let {
                it.text = pm25?.let { getString(R.string.pm2_5_format, it) } ?: getString(R.string.pm2_5_label)
                animateView(it)
            }
            tvPm10?.let {
                it.text = pm10?.let { getString(R.string.pm10_format, it) } ?: getString(R.string.pm10_label)
                animateView(it)
            }

            val prob = next.get("max_precipitation_probability_next6h_pct")?.asInt
            val sum = next.get("total_precipitation_next6h_mm")?.asDouble
            tvRainProb?.let {
                it.text = prob?.let { getString(R.string.max_probability_format, it) } ?: getString(R.string.max_probability_label)
                animateView(it)
            }
            tvRainSum?.let {
                it.text = sum?.let { getString(R.string.total_6h_format, it) } ?: getString(R.string.total_6h_label)
                animateView(it)
            }

            val rRain = risk.get("chuva")?.asString ?: "—"
            val rAir = risk.get("qualidade_do_ar")?.asString ?: "—"
            val rFlood = risk.get("alagamento")?.asString ?: "—"
            chipRain?.let { it.text = getString(R.string.rain_risk_format, translateRisk(rRain)) }
            chipAir?.let { it.text = getString(R.string.air_risk_format, translateRisk(rAir)) }
            chipFlood?.let { it.text = getString(R.string.flood_risk_format, translateRisk(rFlood)) }
        } catch (e: Exception) {
            out.text = getString(R.string.parse_error_message, e.message)
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.parse_error_message, e.message), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun translateRisk(risk: String): String {
        return when (risk.lowercase()) {
            "low" -> getString(R.string.risk_low)
            "moderate" -> getString(R.string.risk_moderate)
            "high" -> getString(R.string.risk_high)
            "very high" -> getString(R.string.risk_very_high)
            "extreme" -> getString(R.string.risk_extreme)
            else -> risk
        }
    }

    private fun animateView(view: View) {
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(200).start()
    }

    private fun fadeIn(v: android.view.View?) { v?.apply { alpha = 0f; animate().alpha(1f).setDuration(180).start() } }
}

fun String.normalized(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
}

class Debouncer(private val delayMs: Long) {
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    fun submit(block: () -> Unit) {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = Runnable { block() }
        handler.postDelayed(runnable!!, delayMs)
    }
}

class SimpleTextWatcher(private val onText: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onText(s?.toString() ?: "") }
    override fun afterTextChanged(s: android.text.Editable?) {}
}