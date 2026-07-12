package com.example

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceView: AmbientSurfaceView
    private lateinit var settingsOverlay: FrameLayout

    private lateinit var repository: WeatherRepository
    private lateinit var chimeSynthesizer: ChimeSynthesizer
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())

    // Fired on start and every 10 minutes to sync weather
    private val weatherRunnable = object : Runnable {
        override fun run() {
            lifecycleScope.launch {
                Log.d(TAG, "Triggering scheduled weather fetch...")
                repository.fetchWeather()
                val state = repository.weatherState.value
                if (state != null) {
                    surfaceView.weatherState = state
                }
            }
            // 10 minutes interval = 600,000 ms
            handler.postDelayed(this, 10 * 60 * 1000L)
        }
    }

    // Checking exact hourly chime strikes
    private val chimeCheckRunnable = object : Runnable {
        private var lastChimedHour = -1
        override fun run() {
            val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            val currentHour = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)

            // Fired exactly at minute 0 of the hour
            if (m == 0 && lastChimedHour != currentHour) {
                lastChimedHour = currentHour
                val hour12 = cal.get(Calendar.HOUR)
                val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
                lifecycleScope.launch {
                    chimeSynthesizer.playHourlyChime(hour12, dayOfYear)
                }
            } else if (m != 0) {
                // reset chime anchor if we are past minute 0
                lastChimedHour = -1
            }

            // check every second (1000ms)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Landscape layout locked
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Immersive sticky full-screen setup which hides both nav and status bars initially
        hideSystemUI()

        // Keep screen alive at all times (screensaver/dashboard mode)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("ambient_prefs", Context.MODE_PRIVATE)

        // Setup controller components
        repository = WeatherRepository(this)
        chimeSynthesizer = ChimeSynthesizer(this)

        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Setup Ambient drawing SurfaceView (fills screen)
        surfaceView = AmbientSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(surfaceView)

        // Setup bottom weather bar tap callback to open detailed weather popover
        surfaceView.onWeatherBarClicked = {
            val state = repository.weatherState.value
            if (state != null) {
                val existing = rootLayout.findViewWithTag<View>("weather_detail_overlay_tag")
                if (existing == null) {
                    val overlay = WeatherDetailOverlay(this, state).apply {
                        tag = "weather_detail_overlay_tag"
                    }
                    rootLayout.addView(overlay)
                }
            } else {
                Toast.makeText(this, "Fetching current weather... please wait.", Toast.LENGTH_SHORT).show()
            }
        }

        // Enter configuration screen with a standard long-press listener
        surfaceView.setOnLongClickListener {
            showSettingsOverlay()
            true
        }

        // Configure options screen (Overlay is initially GONE)
        setupSettingsOverlayView()
        rootLayout.addView(settingsOverlay)

        setContentView(rootLayout)

        // Continuous StateFlow flow subscription
        lifecycleScope.launch {
            repository.weatherState.collectLatest { state ->
                if (state != null) {
                    surfaceView.weatherState = state
                }
            }
        }

        // Start tasks
        handler.post(weatherRunnable)
        handler.post(chimeCheckRunnable)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        // Immersive Sticky flag combos targeting Android 11 API Level 30
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun setupSettingsOverlayView() {
        settingsOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#E0040812")) // Frosted deep cosmic dark blue
            visibility = View.GONE
            // Intercept touches and clicks to prevent hitting canvas elements underneath
            setOnClickListener { /* consume */ }
        }

        // Floating rounded settings card
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val dns = resources.displayMetrics.density
            val lp = FrameLayout.LayoutParams(
                (420 * dns).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            setPadding((24 * dns).toInt(), (24 * dns).toInt(), (24 * dns).toInt(), (24 * dns).toInt())

            // Create beautiful background with high contrast thin border limits
            val backgroundDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#151B30")) // Deep midnight slate card
                cornerRadius = 14f * dns
                setStroke((1.5f * dns).toInt(), Color.parseColor("#344669")) // Accent Ice border outline
            }
            background = backgroundDrawable
        }

        val pad = (12 * resources.displayMetrics.density).toInt()

        // Card Header Row: Left Title, Right Cancel button
        val cardHeader = RelativeLayout(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = pad * 2
            }
            layoutParams = lp
        }

        val title = TextView(this).apply {
            text = "AMBIENT DISPLAY SETTINGS"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = lp
        }
        cardHeader.addView(title)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 15f
            gravity = Gravity.CENTER
            val size = (30 * resources.displayMetrics.density).toInt()
            val lp = RelativeLayout.LayoutParams(size, size).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = lp
            
            // Circular grey stroke backdrop
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(20, 255, 255, 255))
                setStroke((1 * resources.displayMetrics.density).toInt(), Color.argb(60, 255, 255, 255))
            }
            background = circle
            setOnClickListener {
                settingsOverlay.visibility = View.GONE
                hideSystemUI()
            }
        }
        cardHeader.addView(closeBtn)
        cardLayout.addView(cardHeader)

        // Provider Info Label
        val providerLabel = TextView(this).apply {
            text = "PROVIDER: HIGH-PRECISION OPEN-METEO"
            setTextColor(Color.parseColor("#38BDF8")) // Beautiful Sky Blue
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad * 2
            layoutParams = lp
        }
        cardLayout.addView(providerLabel)

        // Chime Switch
        val chimeToggle = CheckBox(this).apply {
            tag = "chime_tag"
            text = "Enable Hourly Pentatonic Chimes"
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = prefs.getBoolean("chime_enabled", true)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad
            layoutParams = lp
        }
        cardLayout.addView(chimeToggle)

        // Volume Label reference template
        val volumeLabel = TextView(this).apply {
            tag = "volume_label_tag"
            val curVol = prefs.getInt("chime_volume", 80)
            text = "Hourly Chime Strike Volume: ${curVol}%"
            setTextColor(Color.parseColor("#A3B6CE"))
            textSize = 12f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad / 3
            layoutParams = lp
        }
        cardLayout.addView(volumeLabel)

        // Volume slider seek bar
        val volumeSeek = SeekBar(this).apply {
            tag = "volume_tag"
            max = 100
            progress = prefs.getInt("chime_volume", 80)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad * 2
            layoutParams = lp

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    volumeLabel.text = "Hourly Chime Strike Volume: ${progress}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        cardLayout.addView(volumeSeek)

        // Voice selection label
        val voiceLabel = TextView(this).apply {
            text = "Hourly Chime Voice Theme"
            setTextColor(Color.parseColor("#A3B6CE"))
            textSize = 12f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = pad / 3
            }
            layoutParams = lp
        }
        cardLayout.addView(voiceLabel)

        // Voice spinner and Preview button row
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = pad * 2
            }
            layoutParams = lp
        }

        val voices = ChimeSynthesizer.voiceOptions()
        val voiceLabels = voices.map { it.second }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, voiceLabels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is TextView) {
                    v.setTextColor(Color.WHITE)
                    v.textSize = 14f
                    v.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    v.setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                if (v is TextView) {
                    v.setTextColor(Color.WHITE)
                    v.setBackgroundColor(Color.parseColor("#1E293B")) // slate-800
                    v.textSize = 14f
                    v.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    val p = (12 * resources.displayMetrics.density).toInt()
                    v.setPadding(p, p, p, p)
                }
                return v
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val voiceSpinner = Spinner(this).apply {
            tag = "voice_spinner_tag"
            setAdapter(adapter)
            val dns = resources.displayMetrics.density
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B")) // Slate 800
                cornerRadius = 6f * dns
                setStroke((1 * dns).toInt(), Color.parseColor("#475569")) // Slate 600
            }
            background = bg

            val lp = LinearLayout.LayoutParams(
                0,
                (40 * dns).toInt(),
                1f
            ).apply {
                rightMargin = (8 * dns).toInt()
            }
            layoutParams = lp

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedVoiceId = voices[position].first
                    prefs.edit().putString("chime_voice", selectedVoiceId).apply()
                    Log.d(TAG, "Selected chime voice: $selectedVoiceId")
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        voiceRow.addView(voiceSpinner)

        val previewBtn = Button(this).apply {
            tag = "preview_btn_tag"
            text = "PREVIEW"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val dns = resources.displayMetrics.density
            val btnDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#4B5563")) // Medium slate gray
                cornerRadius = 6f * dns
            }
            background = btnDrawable

            val lp = LinearLayout.LayoutParams(
                (100 * dns).toInt(),
                (40 * dns).toInt()
            )
            layoutParams = lp

            setOnClickListener {
                val btn = this
                btn.isEnabled = false
                btn.alpha = 0.5f
                lifecycleScope.launch {
                    try {
                        val curVolProgress = settingsOverlay.findViewWithTag<SeekBar>("volume_tag")?.progress ?: 80
                        prefs.edit().putInt("chime_volume", curVolProgress).apply()
                        chimeSynthesizer.playHourlyChime(hour12 = 3, dayOfYear = 1, forcePlay = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed playing preview chime", e)
                    }
                    delay(6000)
                    btn.isEnabled = true
                    btn.alpha = 1.0f
                }
            }
        }
        voiceRow.addView(previewBtn)
        cardLayout.addView(voiceRow)

        // Test Chime Button
        val testChimeBtn = Button(this).apply {
            text = "TEST CHIME VOICE"
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val dns = resources.displayMetrics.density
            val btnDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#4B5563")) // Medium slate gray button
                cornerRadius = 8f * dns
            }
            background = btnDrawable

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * dns).toInt()
            ).apply {
                bottomMargin = pad * 2
            }
            layoutParams = lp

            setOnClickListener {
                lifecycleScope.launch {
                    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
                    val h = cal.get(Calendar.HOUR)
                    val hour12 = if (h == 0) 12 else h
                    val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
                                        // Temporarily save volume progress for the test preview
                    prefs.edit().putInt("chime_volume", volumeSeek.progress).apply()
                    chimeSynthesizer.playHourlyChime(hour12, dayOfYear, forcePlay = true)
                }
            }
        }
        cardLayout.addView(testChimeBtn)

        // Status/Error Text label
        val statusText = TextView(this).apply {
            tag = "status_text_tag"
            visibility = View.GONE
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad
            layoutParams = lp
        }
        cardLayout.addView(statusText)

        // Save / Done Button
        val doneBtn = Button(this).apply {
            text = "SAVE & SYNC"
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val dns = resources.displayMetrics.density
            val btnDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#265BFF")) // Dynamic Ice blue accent button
                cornerRadius = 8f * dns
            }
            background = btnDrawable

            setOnClickListener {
                val isChimeOn = chimeToggle.isChecked
                val volumeVal = volumeSeek.progress

                // Update prefs immediately for local options
                prefs.edit().apply {
                    putBoolean("chime_enabled", isChimeOn)
                    putInt("chime_volume", volumeVal)
                    apply()
                }

                // Show loading status inside dialogue, keep open
                statusText.text = "Syncing with Open-Meteo... Please wait."
                statusText.setTextColor(Color.parseColor("#A3B6CE"))
                statusText.visibility = View.VISIBLE

                // Disable button and fields
                isEnabled = false
                text = "SYNCING..."
                chimeToggle.isEnabled = false
                volumeSeek.isEnabled = false

                lifecycleScope.launch {
                    val errorReason = repository.fetchWeather()

                    // Re-enable fields
                    isEnabled = true
                    text = "SAVE & SYNC"
                    chimeToggle.isEnabled = true
                    volumeSeek.isEnabled = true

                    if (errorReason == null) {
                        // Success!
                        statusText.visibility = View.GONE
                        val state = repository.weatherState.value
                        if (state != null) {
                            surfaceView.weatherState = state
                        }
                        Toast.makeText(this@MainActivity, "Weather synced successfully!", Toast.LENGTH_SHORT).show()

                        // Hide settings view
                        settingsOverlay.visibility = View.GONE
                        hideSystemUI()
                    } else {
                        // Failure! Show detailed error statement to user
                        statusText.text = errorReason
                        statusText.setTextColor(Color.parseColor("#FF5252")) // Vibrant red
                        Toast.makeText(this@MainActivity, "Sync failed! Check settings details.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        cardLayout.addView(doneBtn)

        settingsOverlay.addView(cardLayout)
    }

    private fun showSettingsOverlay() {
        val chimeToggleView = settingsOverlay.findViewWithTag<CheckBox>("chime_tag")!!
        val volumeSeekView = settingsOverlay.findViewWithTag<SeekBar>("volume_tag")!!
        val volumeLabelView = settingsOverlay.findViewWithTag<TextView>("volume_label_tag")!!
        val statusTextView = settingsOverlay.findViewWithTag<TextView>("status_text_tag")

        statusTextView?.visibility = View.GONE
        val isChimeOn = prefs.getBoolean("chime_enabled", true)
        val curVol = prefs.getInt("chime_volume", 80)

        // Populate values
        chimeToggleView.isChecked = isChimeOn
        volumeSeekView.progress = curVol
        volumeLabelView.text = "Hourly Chime Strike Volume: ${curVol}%"

        val voiceSpinnerView = settingsOverlay.findViewWithTag<Spinner>("voice_spinner_tag")
        if (voiceSpinnerView != null) {
            val savedVoice = prefs.getString("chime_voice", ChimeSynthesizer.DEFAULT_VOICE) ?: ChimeSynthesizer.DEFAULT_VOICE
            val voices = ChimeSynthesizer.voiceOptions()
            var selectedIndex = voices.indexOfFirst { it.first == savedVoice }
            if (selectedIndex < 0) {
                selectedIndex = voices.indexOfFirst { it.first == ChimeSynthesizer.DEFAULT_VOICE }
            }
            if (selectedIndex >= 0) {
                voiceSpinnerView.setSelection(selectedIndex)
            }
        }

        settingsOverlay.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
