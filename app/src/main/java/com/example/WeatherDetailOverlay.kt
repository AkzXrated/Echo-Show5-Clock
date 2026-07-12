package com.example

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

class WeatherDetailOverlay @JvmOverloads constructor(
    context: Context,
    private val weatherState: WeatherState,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var selectedDayIndex = 1 // Default index 1 is TODAY (index 0 is YESTERDAY)
    private var selectedTab = 0      // 0 = Temperature, 1 = Precipitation, 2 = Wind

    private lateinit var contentContainer: LinearLayout
    private lateinit var locationTitleView: TextView
    private lateinit var currentTempView: TextView
    private lateinit var curDescView: TextView
    private lateinit var curHumidityView: TextView
    private lateinit var curWindView: TextView
    private lateinit var curRainView: TextView
    private lateinit var curIconContainer: FrameLayout

    private lateinit var daysLayout: LinearLayout
    private lateinit var tabTemperature: Button
    private lateinit var tabPrecipitation: Button
    private lateinit var tabWind: Button
    private lateinit var chartView: WeatherHourlyChartView

    private val dns = resources.displayMetrics.density

    init {
        // Immersive glassmospheric dark overlay background
        setBackgroundColor(Color.parseColor("#EC060B1E")) // deep dark space slate violet
        alpha = 0f
        scaleX = 0.96f
        scaleY = 0.96f
        translationY = 12f * resources.displayMetrics.density

        // Block lower clicks from leaking
        setOnClickListener { /* consume */ }

        setupMainLayout()
        updateSelectedDayUI()
        animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
    }

    private fun setupMainLayout() {
        // Vertical content orientation
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            setPadding((16 * dns).toInt(), (12 * dns).toInt(), (16 * dns).toInt(), (12 * dns).toInt())
        }
        addView(contentContainer)

        // 1. HEADER ROW: Left Title, Right Close button
        val headerRow = RelativeLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dns).toInt()
            }
        }

        // Left section vertical: "WEATHER DETAILS" label and city/day summary
        val headerTextLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = lp
        }

        val overlayTitle = TextView(context).apply {
            text = "WEATHER INTELLIGENCE CENTER"
            setTextColor(Color.parseColor("#38BDF8")) // Cool Sky Blue accent
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        headerTextLayout.addView(overlayTitle)

        locationTitleView = TextView(context).apply {
            text = "${WeatherRepository.LOCATION_LABEL.uppercase()}  ·  FORECAST STATUS"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        headerTextLayout.addView(locationTitleView)
        headerRow.addView(headerTextLayout)

        // Right circular "✕" close button
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val size = (36 * dns).toInt()
            val lp = RelativeLayout.LayoutParams(size, size).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = lp
            
            // Circular grey stroke backdrop
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(40, 255, 255, 255))
                setStroke((1 * dns).toInt(), Color.argb(100, 255, 255, 255))
            }
            background = circle
            setOnClickListener { dismiss() }
        }
        headerRow.addView(closeBtn)
        contentContainer.addView(headerRow)

        // 2. MAIN SECTION: 2 Column Side-by-side Layout (Current Highlight + Dynamic Day List)
        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (110 * dns).toInt()
            ).apply {
                bottomMargin = (12 * dns).toInt()
            }
        }

        // Left Panel: Big Temperature & Icon Card
        val curWeatherCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                (310 * dns).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((12 * dns).toInt(), 0, (12 * dns).toInt(), 0)
            
            val cardBg = GradientDrawable().apply {
                cornerRadius = 10f * dns
                setColor(Color.argb(24, 255, 255, 255))
                setStroke((1 * dns).toInt(), Color.argb(40, 255, 255, 255))
            }
            background = cardBg
        }

        curIconContainer = FrameLayout(context).apply {
            val size = (54 * dns).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (12 * dns).toInt()
            }
        }
        curWeatherCard.addView(curIconContainer)

        val curTextLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        currentTempView = TextView(context).apply {
            text = "--°C"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        curTextLayout.addView(currentTempView)

        curDescView = TextView(context).apply {
            text = "LOADING..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        curTextLayout.addView(curDescView)

        curWeatherCard.addView(curTextLayout)
        mainRow.addView(curWeatherCard)

        // Space divider
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (12 * dns).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        mainRow.addView(spacer)

        // Right Panel: Days Carousel
        val daysScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.0f
            )
        }
        daysLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            )
        }
        daysScrollView.addView(daysLayout)
        mainRow.addView(daysScrollView)

        contentContainer.addView(mainRow)

        // 3. TABS SELECTOR (Temperature, Precipitation, Wind)
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * dns).toInt()
            ).apply {
                bottomMargin = (8 * dns).toInt()
            }
        }

        fun makeTab(title: String, index: Int): Button {
            return Button(context).apply {
                text = title
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setAllCaps(true)
                
                // Slim rounded capsule layout
                val dp6 = (6 * dns).toInt()
                val dp16 = (16 * dns).toInt()
                setPadding(dp16, dp6, dp16, dp6)
                
                layoutParams = LinearLayout.LayoutParams(
                    (150 * dns).toInt(),
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    leftMargin = (6 * dns).toInt()
                    rightMargin = (6 * dns).toInt()
                }

                setOnClickListener {
                    selectedTab = index
                    updateTabsStyle()
                    chartView.setSelectedTab(selectedTab)
                }
            }
        }

        tabTemperature = makeTab("Temperature", 0)
        tabPrecipitation = makeTab("Precipitation %", 1)
        tabWind = makeTab("Wind (km/h)", 2)

        tabContainer.addView(tabTemperature)
        tabContainer.addView(tabPrecipitation)
        tabContainer.addView(tabWind)
        contentContainer.addView(tabContainer)

        // 4. THE LIVE HOURLY CHART (dynamic canvas viewport)
        chartView = WeatherHourlyChartView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            ).apply {
                bottomMargin = (4 * dns).toInt()
            }
        }
        contentContainer.addView(chartView)

        // 5. CURRENT EXTRA METRICS (Row overlay under chart)
        val extraMetricsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * dns).toInt()
            )
        }

        fun makeMetricComponent(tag: String, label: String, colorHex: String): TextView {
            return TextView(context).apply {
                this.tag = tag
                text = "$label: --"
                setTextColor(Color.parseColor(colorHex))
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                )
            }
        }

        curHumidityView = makeMetricComponent("humidity", "HUMIDITY", "#38BDF8")
        curWindView = makeMetricComponent("wind", "WIND", "#2DD4BF")
        curRainView = makeMetricComponent("rain", "PRECIPITATION", "#A78BFA")

        extraMetricsRow.addView(curHumidityView)
        extraMetricsRow.addView(curWindView)
        extraMetricsRow.addView(curRainView)
        contentContainer.addView(extraMetricsRow)

        // Populate dynamic tabs initially
        updateTabsStyle()
    }

    private fun updateTabsStyle() {
        val selectedBg = GradientDrawable().apply {
            cornerRadius = 20f * dns
            setColor(Color.argb(50, 56, 189, 248)) // sky blue glow
            setStroke((1.5f * dns).toInt(), Color.parseColor("#38BDF8"))
        }
        val unselectedBg = GradientDrawable().apply {
            cornerRadius = 20f * dns
            setColor(Color.argb(15, 255, 255, 255))
            setStroke((1 * dns).toInt(), Color.argb(30, 255, 255, 255))
        }

        tabTemperature.background = if (selectedTab == 0) selectedBg else unselectedBg
        tabTemperature.setTextColor(if (selectedTab == 0) Color.WHITE else Color.parseColor("#94A3B8"))

        tabPrecipitation.background = if (selectedTab == 1) selectedBg else unselectedBg
        tabPrecipitation.setTextColor(if (selectedTab == 1) Color.WHITE else Color.parseColor("#94A3B8"))

        tabWind.background = if (selectedTab == 2) selectedBg else unselectedBg
        tabWind.setTextColor(if (selectedTab == 2) Color.WHITE else Color.parseColor("#94A3B8"))
    }

    private fun updateSelectedDayUI() {
        val days = weatherState.dailyList
        if (days.isEmpty()) return

        // 1. Rebuild Days List Carousel
        daysLayout.removeAllViews()
        val cardWidth = (86 * dns).toInt()
        
        for (i in days.indices) {
            val day = days[i]
            
            val dayCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    cardWidth,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    leftMargin = (3 * dns).toInt()
                    rightMargin = (3 * dns).toInt()
                }

                // Smooth glassmorphic outline card
                val cardBacking = GradientDrawable().apply {
                    cornerRadius = 8f * dns
                    if (i == selectedDayIndex) {
                        setColor(Color.argb(45, 56, 189, 248)) // High blue accent color shade
                        setStroke((1.6f * dns).toInt(), Color.parseColor("#38BDF8")) // Solid outline frame
                    } else {
                        setColor(Color.argb(16, 255, 255, 255))
                        setStroke((1 * dns).toInt(), Color.argb(20, 255, 255, 255))
                    }
                }
                background = cardBacking

                // Add elegant Material Design 3 touch ripple feedback
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = context.getDrawable(outValue.resourceId)

                setOnClickListener {
                    selectedDayIndex = i
                    updateSelectedDayUI()
                }
            }

            // Day title text: "YEST", "TODAY", "TUE"
            val dayLabelView = TextView(context).apply {
                text = day.dayLabel
                setTextColor(Color.WHITE)
                if (i == selectedDayIndex) {
                    setTextColor(Color.parseColor("#38BDF8"))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                } else {
                    setTextColor(Color.parseColor("#94A3B8"))
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                textSize = 10f
                gravity = Gravity.CENTER
            }
            dayCard.addView(dayLabelView)

            // Date text: "JUN 8"
            val dateLabelView = TextView(context).apply {
                text = day.dateLabel
                setTextColor(Color.parseColor("#64748B"))
                textSize = 8.5f
                gravity = Gravity.CENTER
            }
            dayCard.addView(dateLabelView)

            // Draw custom mini vector icon for day card on canvas holder
            val holderW = (24 * dns).toInt()
            val miniIconHolder = object : View(context) {
                override fun onDraw(c: Canvas) {
                    super.onDraw(c)
                    drawOverlayWeatherIcon(c, width / 2f, height / 2f, 18f * dns, day.conditionId, isDay = true)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(holderW, holderW).apply {
                    topMargin = (2 * dns).toInt()
                    bottomMargin = (2 * dns).toInt()
                }
            }
            dayCard.addView(miniIconHolder)

            // Temperatures: "29° 26°"
            var tMax = day.tempMaxC.roundToInt()
            var tMin = day.tempMinC.roundToInt()
            val tempLabelView = TextView(context).apply {
                text = "$tMax°  $tMin°"
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
            }
            dayCard.addView(tempLabelView)

            daysLayout.addView(dayCard)
        }

        // 2. Update Left current/detailed panel with selected day particulars
        val activeDay = days[selectedDayIndex]
        
        // Re-draw Current Highlight icon holder dynamically
        curIconContainer.removeAllViews()
        val bigIconDrawView = object : View(context) {
            override fun onDraw(c: Canvas) {
                super.onDraw(c)
                drawOverlayWeatherIcon(c, width / 2f, height / 2f, width * 0.72f, activeDay.conditionId, isDay = true)
            }
        }.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }
        curIconContainer.addView(bigIconDrawView)

        // Show max temperature of active day in big panel
        val activeMax = activeDay.tempMaxC.roundToInt()
        val activeMin = activeDay.tempMinC.roundToInt()
        currentTempView.text = "$activeMax°C"
        curDescView.text = activeDay.description.uppercase()

        // Sync helper info tags on bottom row
        curHumidityView.text = "PROBABILITY OF RAIN: ${activeDay.precipitationProbMax}%"
        
        // Calculate total rainfall for selected day from its 24h
        val hStartIndex = (selectedDayIndex * 24).coerceAtMost(weatherState.hourlyList.size)
        val hEndIndex = (hStartIndex + 24).coerceAtMost(weatherState.hourlyList.size)
        val activeHoursList = if (hEndIndex > hStartIndex) {
            weatherState.hourlyList.subList(hStartIndex, hEndIndex)
        } else emptyList()

        val dailyRainfallSum = activeHoursList.sumOf { it.precipitationMm.toDouble() }
        val maxWind = activeHoursList.maxOfOrNull { it.windSpeedKmh } ?: weatherState.windSpeed * 3.6f

        curWindView.text = String.format("PEAK WIND RATE: %.1f KM/H", maxWind)
        curRainView.text = String.format("ACCUMULATED RAIN: %.2f MM", dailyRainfallSum)

        // 3. Inform the line charting sub-view about the 24 hours of selected day
        chartView.setHourlyPoints(activeHoursList, selectedTab)
    }

    private fun dismiss() {
        animate().alpha(0f).scaleX(0.97f).scaleY(0.97f)
            .translationY(8f * resources.displayMetrics.density)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { (parent as? ViewGroup)?.removeView(this) }
            .start()
    }

    // Custom Canvas drawings helper for weather condition vectors
    private fun drawOverlayWeatherIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, conditionId: Int, isDay: Boolean) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(size / 50f, size / 50f)

        val paint = Paint().apply {
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        when (conditionId) {
            800 -> { // Clear
                if (isDay) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#F59E0B")
                    canvas.drawCircle(0f, 0f, 13f, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    paint.color = Color.parseColor("#F59E0B")
                    for (i in 0 until 8) {
                        val angle = i * Math.PI / 4.0
                        val x1 = (17f * cos(angle)).toFloat()
                        val y1 = (17f * sin(angle)).toFloat()
                        val x2 = (22f * cos(angle)).toFloat()
                        val y2 = (22f * sin(angle)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                } else {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#E2E8F0")
                    val mainPath = Path().apply { addCircle(-2f, 0f, 13f, Path.Direction.CW) }
                    val subPath = Path().apply { addCircle(5f, -3f, 12f, Path.Direction.CW) }
                    val finalPath = Path().apply { op(mainPath, subPath, Path.Op.DIFFERENCE) }
                    canvas.drawPath(finalPath, paint)
                }
            }
            801, 802 -> { // Partly Cloudy
                if (isDay) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#F59E0B")
                    canvas.drawCircle(-8f, -6f, 9f, paint)
                } else {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#E2E8F0")
                    canvas.drawCircle(-8f, -6f, 8f, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#E2E8F0")
                val cloudPath = Path().apply {
                    addCircle(-6f, 6f, 9f, Path.Direction.CW)
                    addCircle(8f, 6f, 8f, Path.Direction.CW)
                    addCircle(1f, -1f, 11f, Path.Direction.CW)
                    addRoundRect(RectF(-14f, 4f, 14f, 14f), 5f, 5f, Path.Direction.CW)
                }
                canvas.drawPath(cloudPath, paint)
            }
            803, 804, 741 -> { // Overcast or Fog
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#94A3B8")
                val cloudPath = Path().apply {
                    addCircle(-7f, 5f, 10f, Path.Direction.CW)
                    addCircle(9f, 5f, 9f, Path.Direction.CW)
                    addCircle(1f, -2f, 12f, Path.Direction.CW)
                    addRoundRect(RectF(-15f, 3f, 15f, 14f), 5f, 5f, Path.Direction.CW)
                }
                canvas.drawPath(cloudPath, paint)
            }
            in 500..599, in 300..399 -> { // Rain / Drizzle
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#475569")
                val cloudPath = Path().apply {
                    addCircle(-7f, 2f, 10f, Path.Direction.CW)
                    addCircle(9f, 2f, 9f, Path.Direction.CW)
                    addCircle(1f, -5f, 12f, Path.Direction.CW)
                    addRoundRect(RectF(-15f, 0f, 15f, 11f), 5f, 5f, Path.Direction.CW)
                }
                canvas.drawPath(cloudPath, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f
                paint.color = Color.parseColor("#38BDF8")
                canvas.drawLine(-8f, 13f, -11f, 20f, paint)
                canvas.drawLine(1f, 13f, -2f, 20f, paint)
                canvas.drawLine(9f, 13f, 6f, 20f, paint)
            }
            in 200..299 -> { // Thunderstorm
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#1E293B")
                val cloudPath = Path().apply {
                    addCircle(-7f, 2f, 10f, Path.Direction.CW)
                    addCircle(9f, 2f, 9f, Path.Direction.CW)
                    addCircle(1f, -5f, 12f, Path.Direction.CW)
                    addRoundRect(RectF(-15f, 0f, 15f, 11f), 5f, 5f, Path.Direction.CW)
                }
                canvas.drawPath(cloudPath, paint)

                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = 1f
                paint.color = Color.parseColor("#FACC15")
                val lightningPath = Path().apply {
                    moveTo(2f, 9f)
                    lineTo(-5f, 16f)
                    lineTo(-1f, 16f)
                    lineTo(-4f, 23f)
                    lineTo(4f, 14f)
                    lineTo(0f, 14f)
                    close()
                }
                canvas.drawPath(lightningPath, paint)
            }
            in 600..699 -> { // Snow
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#94A3B8")
                val cloudPath = Path().apply {
                    addCircle(-7f, 2f, 10f, Path.Direction.CW)
                    addCircle(9f, 2f, 9f, Path.Direction.CW)
                    addCircle(1f, -5f, 12f, Path.Direction.CW)
                    addRoundRect(RectF(-15f, 0f, 15f, 11f), 5f, 5f, Path.Direction.CW)
                }
                canvas.drawPath(cloudPath, paint)

                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawCircle(-7f, 14f, 2f, paint)
                canvas.drawCircle(1f, 16f, 2f, paint)
                canvas.drawCircle(9f, 14f, 2f, paint)
            }
            else -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#F59E0B")
                canvas.drawCircle(0f, 0f, 12f, paint)
            }
        }

        canvas.restore()
    }
}

class WeatherHourlyChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hourlyPoints: List<HourlyWeather> = emptyList()
    private var selectedTab = 0 // 0 = Temperature, 1 = Precipitation Probability, 2 = Wind Speed

    private val density = resources.displayMetrics.density

    private val pathPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val valuePaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 10.5f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val axisPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#475569") // slate gray grid line
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val hourLabelPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#94A3B8")
        textSize = 9.5f * density
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val dotGlowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    fun setHourlyPoints(points: List<HourlyWeather>, tabIndex: Int) {
        this.hourlyPoints = points
        this.selectedTab = tabIndex
        invalidate()
    }

    fun setSelectedTab(tabIndex: Int) {
        this.selectedTab = tabIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hourlyPoints.isEmpty()) {
            canvas.drawText("NO HOURLY DATA LOADED", width / 2f, height / 2f, valuePaint)
            return
        }

        val paddingLeft = 32f * density
        val paddingRight = 32f * density
        val paddingTop = 36f * density
        val paddingBottom = 26f * density

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        // 1. Get values based on selected tab and find limits
        val values = mutableListOf<Float>()
        for (p in hourlyPoints) {
            val v = when (selectedTab) {
                0 -> p.tempC
                1 -> p.precipitationProb.toFloat()
                else -> p.windSpeedKmh
            }
            values.add(v)
        }

        var minValue = values.minOrNull() ?: 0f
        var maxValue = values.maxOrNull() ?: 10f

        // Add visual buffering breathing room so line doesn't hit chart borders
        if (selectedTab == 0 || selectedTab == 2) { // Temp or Wind
            val diff = maxValue - minValue
            val padding = if (diff < 2f) 1.5f else diff * 0.15f
            minValue -= padding
            maxValue += padding
        } else { // Precipitation is solid boundaries: 0% to 100%
            minValue = 0f
            maxValue = 100f
        }

        if (maxValue == minValue) {
            maxValue += 1f
            minValue -= 1f
        }

        // 2. Define colors & gradients
        val accentColor: Int
        val secondaryGlow: Int
        val gradientColors: IntArray

        when (selectedTab) {
            0 -> { // Temperature (Gold/Orange Amber Glow)
                accentColor = Color.parseColor("#F59E0B")
                secondaryGlow = Color.argb(40, 245, 158, 11)
                gradientColors = intArrayOf(
                    Color.argb(90, 245, 158, 11),
                    Color.argb(0, 245, 158, 11)
                )
            }
            1 -> { // Precipitation (Water/Sky Blue)
                accentColor = Color.parseColor("#38BDF8")
                secondaryGlow = Color.argb(40, 56, 189, 248)
                gradientColors = intArrayOf(
                    Color.argb(90, 56, 189, 248),
                    Color.argb(0, 56, 189, 248)
                )
            }
            else -> { // Wind Speed (Soft Mint Teal)
                accentColor = Color.parseColor("#2DD4BF")
                secondaryGlow = Color.argb(40, 45, 212, 191)
                gradientColors = intArrayOf(
                    Color.argb(90, 45, 212, 191),
                    Color.argb(0, 45, 212, 191)
                )
            }
        }

        // Apply shader to fill paint
        val areaShader = LinearGradient(
            0f, paddingTop, 0f, height - paddingBottom,
            gradientColors, null, Shader.TileMode.CLAMP
        )
        fillPaint.shader = areaShader

        pathPaint.color = accentColor
        dotGlowPaint.color = accentColor

        // 3. Compute (X, Y) layout coordinates for 24 points
        val pointsX = FloatArray(24)
        val pointsY = FloatArray(24)
        val dx = chartWidth / 23f

        for (i in 0 until 24) {
            val valF = values[i]
            val pct = (valF - minValue) / (maxValue - minValue)
            pointsX[i] = paddingLeft + i * dx
            pointsY[i] = paddingTop + (1f - pct) * chartHeight
        }

        // 4. Draw horizontal background guidelines
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft + chartWidth, paddingTop, axisPaint)
        canvas.drawLine(paddingLeft, paddingTop + chartHeight, paddingLeft + chartWidth, paddingTop + chartHeight, axisPaint)

        // 5. Draw Curve Path using cubic splines (Bezier)
        if (24 > 1) {
            val mainPath = Path()
            val fillPath = Path()

            mainPath.moveTo(pointsX[0], pointsY[0])
            fillPath.moveTo(pointsX[0], paddingTop + chartHeight)
            fillPath.lineTo(pointsX[0], pointsY[0])

            for (i in 1 until 24) {
                val prevX = pointsX[i - 1]
                val prevY = pointsY[i - 1]
                val currX = pointsX[i]
                val currY = pointsY[i]

                // Perfect control point mapping for smooth curves
                val cpX1 = prevX + (currX - prevX) / 2.0f
                val cpY1 = prevY
                val cpX2 = prevX + (currX - prevX) / 2.0f
                val cpY2 = currY

                mainPath.cubicTo(cpX1, cpY1, cpX2, cpY2, currX, currY)
            }

            // Copy path for overlay gradient fill
            fillPath.addPath(mainPath)
            fillPath.lineTo(pointsX[23], paddingTop + chartHeight)
            fillPath.close()

            // Draw shading area
            canvas.drawPath(fillPath, fillPaint)
            // Draw stroke outline line
            canvas.drawPath(mainPath, pathPaint)
        }

        // 6. Draw glowing circles, value labels & time anchors for selective intervals
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val sfTime = SimpleDateFormat("h a", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }

        for (i in 0 until 24) {
            val cx = pointsX[i]
            val cy = pointsY[i]
            val valF = values[i]

            // Value indicators (Draw on every 3rd point, or 1st/last to avoid overlap)
            val shouldDrawLabel = i == 0 || i == 23 || (i % 3 == 0)

            if (shouldDrawLabel) {
                // Draw white circular dot on actual curve
                dotPaint.alpha = 255
                canvas.drawCircle(cx, cy, 3.8f * density, dotPaint)
                
                // Draw outer glowing halo circle
                dotGlowPaint.alpha = 110
                canvas.drawCircle(cx, cy, 6.5f * density, dotGlowPaint)

                // Format the value string correctly
                val valueStr = when (selectedTab) {
                    0 -> "${valF.roundToInt()}°"
                    1 -> "${valF.roundToInt()}%"
                    else -> "${valF.roundToInt()}"
                }
                
                // Text value drawing directly above coordinates
                valuePaint.color = Color.WHITE
                canvas.drawText(valueStr, cx, cy - (9f * density), valuePaint)

                // Draw X time anchors
                val hourEpoch = hourlyPoints[i].epoch
                calendar.timeInMillis = hourEpoch * 1000L
                val labelTime = sfTime.format(calendar.time).uppercase()
                
                canvas.drawText(labelTime, cx, paddingTop + chartHeight + (18f * density), hourLabelPaint)
            }
        }
    }
}
