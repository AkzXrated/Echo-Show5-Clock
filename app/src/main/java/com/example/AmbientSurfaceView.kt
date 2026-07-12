package com.example

import android.content.Context
import android.graphics.*
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class AmbientSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, Runnable {

    companion object {
        private const val TAG = "AmbientSurfaceView"
    }

    private var renderThread: Thread? = null
    @Volatile private var isRunning = false

    @Volatile private var viewWidth = 960f
    @Volatile private var viewHeight = 480f

    // Weather state: starts with beautiful default placeholder values
    @Volatile var weatherState: WeatherState = WeatherState(
        tempC = 28f,
        feelsLikeC = 31f,
        conditionId = 800,
        description = "CLEAR SKY",
        humidity = 76,
        windSpeed = 8f,
        cloudCover = 10,
        rainMmH = 0f,
        visibility = 10000,
        sunriseEpoch = System.currentTimeMillis() / 1000 - 12 * 3600,
        sunsetEpoch = System.currentTimeMillis() / 1000 + 12 * 3600,
        moonPhase = 0.5f,
        highC = 32f,
        lowC = 25f,
        hourlyTemps = List(12) { 28f },
        fetchedAt = System.currentTimeMillis()
    )

    // Smooth Sky Gradient Lerp Variables
    private var renderedTopColor = Color.parseColor("#0A1128")
    private var renderedBottomColor = Color.parseColor("#03050F")

    // Particle Systems (Pre-allocated once)
    private val rainParticles = List(500) { RainParticle() }
    private val splashParticles = List(20) { SplashParticle() }
    private val snowParticles = List(200) { SnowParticle() }
    private val clouds = List(6) { Cloud() }
    private val fogBands = List(4) { FogBand() }
    private val stars = List(200) { Star() }
    private val shimmerMotes = List(30) { ShimmerMote() }
    private val fireflies = List(15) { Firefly() }
    private val ambientParticles = List(20) { AmbientParticle() }
    private val shootingStar = ShootingStar()

    private var starPositionsDayOfYear = -1
    private var nextShootingStarTime = 0L

    // Lightning parameters
    private var lightningActive = false
    private var lightningAlpha = 0f
    private var lightningX = 0f
    private val lightningPath = Path()
    private var nextLightningTimeMs = 0L

    // Date/bar master alpha multiplier
    private var dateBarAlpha = 1.0f

    // Timers
    private var lastUpdateTime = System.nanoTime()
    @Volatile private var frameDt = 0.033f // last frame delta seconds, set once per frame in run()

    // Shared RNG (never allocate per frame)
    private val rand = java.util.Random()

    // Solar position, recomputed each frame in drawLayers and shared by all layers
    private var sunAltitudeDeg = 0f
    private var sunAzimuthDeg = 180f

    // Paint pool (instantiated once)
    private val skyPaint = Paint().apply { isDither = true }
    private val sunPaint = Paint().apply { isAntiAlias = true }
    private val flarePaint = Paint().apply { isAntiAlias = true; strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val moonPaint = Paint().apply { isAntiAlias = true; color = Color.parseColor("#FFFFF0") }
    private val moonHaloPaint = Paint().apply { isAntiAlias = true }
    private val shadowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }

    private val rainPaint = Paint().apply { isAntiAlias = true; strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val splashPaint = Paint().apply { isAntiAlias = true; strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val snowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.WHITE }
    private val cloudPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val fogPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val lightningPaint = Paint().apply { isAntiAlias = true; strokeWidth = 2.5f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER; color = Color.WHITE }

    private val shimmerPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val fireflyPaint = Paint().apply { isAntiAlias = true }
    private val starPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.WHITE }
    private val shootingStarPaint = Paint().apply { isAntiAlias = true; strokeWidth = 2f; style = Paint.Style.STROKE }
    private val ambientPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.WHITE }

    // Celestial body paints (shared by sun and moon renderers)
    private val celestialPaint = Paint().apply { isAntiAlias = true }
    private val celestialGlowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val celestialRayPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val horizonGlowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val lightningGlowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }

    // Position-independent shaders built once; drawn around a translated origin
    private val sunGlowShader = RadialGradient(0f, 0f, 280f, intArrayOf(
        Color.argb(110, 253, 224, 71), Color.argb(38, 249, 115, 22),
        Color.argb(10, 234, 88, 12), Color.argb(0, 234, 88, 12)
    ), floatArrayOf(0.0f, 0.35f, 0.75f, 1.0f), Shader.TileMode.CLAMP)
    private val moonGlowShader = RadialGradient(0f, 0f, 240f, intArrayOf(
        Color.argb(75, 224, 242, 254), Color.argb(25, 186, 230, 253),
        Color.argb(6, 14, 165, 233), Color.argb(0, 14, 165, 233)
    ), floatArrayOf(0.0f, 0.35f, 0.75f, 1.0f), Shader.TileMode.CLAMP)
    private val sunCoreShader = RadialGradient(0f, 0f, 24f, intArrayOf(
        Color.rgb(253, 224, 71), Color.rgb(249, 115, 22), Color.rgb(234, 88, 12)
    ), null, Shader.TileMode.CLAMP)
    private val moonHaloShader = RadialGradient(0f, 0f, 45f, intArrayOf(
        Color.argb(80, 186, 230, 253), Color.argb(0, 186, 230, 253)
    ), null, Shader.TileMode.CLAMP)
    private val moonBodyShader = LinearGradient(-15f, -15f, 15f, 15f,
        Color.rgb(254, 240, 138), Color.rgb(251, 191, 36), Shader.TileMode.CLAMP)
    // Unit-radius radial glows: place with canvas.translate + canvas.scale
    private val horizonGlowShader = RadialGradient(0f, 0f, 1f, intArrayOf(
        Color.argb(255, 255, 140, 66), Color.argb(90, 255, 110, 60), Color.argb(0, 255, 100, 60)
    ), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    private val lightningGlowShader = RadialGradient(0f, 0f, 1f, intArrayOf(
        Color.argb(160, 190, 220, 255), Color.argb(60, 150, 190, 255), Color.argb(0, 140, 180, 255)
    ), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    private val fireflyGlowShader = RadialGradient(0f, 0f, 1f, intArrayOf(
        Color.argb(200, 180, 255, 0), Color.argb(0, 180, 255, 0)
    ), null, Shader.TileMode.CLAMP)

    // Sky gradient shader cache: rebuilt only when the blended colors change
    private var skyShaderTop = 1
    private var skyShaderBottom = 1

    // Cached blur filters (allocating BlurMaskFilter per frame is expensive)
    private val blur40 = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL)
    private val blur20 = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    private val blur8 = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    private val blur15 = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
    private val blur6 = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)

    // Bottom info bar paints (configured per frame, allocated once)
    private val tempPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); color = Color.WHITE }
    private val tempGlowPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val descPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val locationPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val metricPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val triPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val dividerPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private var dividerShaderAlpha = -1
    private val triPathUp = Path()
    private val triPathDown = Path()
    private val fogPath = Path()

    // Mini icon shared resources
    private val iconPaint = Paint().apply { isAntiAlias = true }
    private val baseCloudPath = Path().apply {
        addCircle(-15f, 3f, 11f, Path.Direction.CW)
        addCircle(15f, 4f, 10f, Path.Direction.CW)
        addCircle(0f, -8f, 16f, Path.Direction.CW)
        addRoundRect(RectF(-22f, 1f, 22f, 15f), 7f, 7f, Path.Direction.CW)
    }
    private val dropletPath = Path().apply {
        moveTo(0f, -35f)
        cubicTo(20f, 0f, 25f, 25f, 0f, 35f)
        cubicTo(-25f, 25f, -20f, 0f, 0f, -35f)
        close()
    }
    private val dropletPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        shader = LinearGradient(0f, -30f, 0f, 35f,
            Color.rgb(96, 165, 250), Color.rgb(37, 99, 235), Shader.TileMode.CLAMP)
    }
    private val windTrackPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; color = Color.argb(80, 203, 213, 225); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
    private val windFlowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; color = Color.rgb(241, 245, 249); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
    private val windPath = Path().apply {
        moveTo(-45f, 0f)
        lineTo(15f, 0f)
        cubicTo(32f, 0f, 32f, -18f, 18f, -18f)
        cubicTo(8f, -18f, 8f, -6f, 16f, -6f)
    }

    // Text & Glow drawing paints (Off-screen clock canvas)
    private val clockFontPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val clockGlowPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val datePaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = 21f
    }
    private val dateGlowPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = 21f
    }

    // Clock cache: digits+AM/PM and colon live in separate bitmaps re-rendered
    // once per minute; the colon breathes via bitmap-draw alpha, and minute
    // changes crossfade between prev/current bitmaps.
    private var clockBmp: Bitmap? = null
    private var clockCanvas: Canvas? = null
    private var prevClockBmp: Bitmap? = null
    private var prevClockCanvas: Canvas? = null
    private var colonBmp: Bitmap? = null
    private var colonCanvas: Canvas? = null
    private var cachedClockMinute = -1
    private var clockSwapTimeMs = 0L
    private var clockBaselineInBmp = 0f
    private val clockBmpPaint = Paint().apply { isFilterBitmap = true }
    private val colonBmpPaint = Paint().apply { isFilterBitmap = true }
    private val amPmPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val amPmGlowPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    // Touch and action listeners for weather bottom bar
    var onWeatherBarClicked: (() -> Unit)? = null
    @Volatile var clockBottomY: Float = 0f

    init {
        holder.addCallback(this)
        // Set fixed size parameters initially, though surfaceChanged will refine them
        initParticles()
    }

    private fun initParticles() {
        // Initialize ambient floaters spread out
        for (p in ambientParticles) {
            p.x = rand.nextFloat() * viewWidth
            p.y = rand.nextFloat() * viewHeight
            p.vx = -15f + rand.nextFloat() * 30f
            p.vy = -15f + rand.nextFloat() * 30f
            p.size = 2f + rand.nextFloat() * 2f
            p.alpha = 0.15f + rand.nextFloat() * 0.2f
        }
        // Rain particles spread initially, interleaved across two depth layers
        // (far = slow/short/dim behind, near = fast/long/bright in front) so any
        // intensity-scaled active count keeps a mix of both depths.
        for ((i, p) in rainParticles.withIndex()) {
            p.layer = if (i % 5 < 3) 0 else 1
            p.x = rand.nextFloat() * viewWidth
            p.y = rand.nextFloat() * viewHeight
            if (p.layer == 0) {
                p.vy = 260f + rand.nextFloat() * 160f
                p.vx = weatherState.windSpeed * 5f
                p.alpha = 0.22f + rand.nextFloat() * 0.22f
                p.length = 7f + rand.nextFloat() * 5f
            } else {
                p.vy = 520f + rand.nextFloat() * 260f
                p.vx = weatherState.windSpeed * 8f
                p.alpha = 0.38f + rand.nextFloat() * 0.3f
                p.length = 16f + rand.nextFloat() * 9f
            }
        }
        // Snow particles spread initially
        for (p in snowParticles) {
            p.x = rand.nextFloat() * viewWidth
            p.y = rand.nextFloat() * viewHeight
            p.vy = 30f + rand.nextFloat() * 40f
            p.size = 2f + rand.nextFloat() * 3f
            p.wWiggleSpeed = 1f + rand.nextFloat() * 2f
            p.wOffset = rand.nextFloat() * (2 * Math.PI).toFloat()
        }
        // Clouds initial setup
        for (i in clouds.indices) {
            val c = clouds[i]
            c.x = rand.nextFloat() * viewWidth * 1.5f - 100f
            c.y = 40f + rand.nextFloat() * 80f
            c.speed = 4f + rand.nextFloat() * 8f
            c.width = 120f + rand.nextFloat() * 100f
            c.height = 40f + rand.nextFloat() * 30f
            c.scale = 0.8f + rand.nextFloat() * 0.4f
        }
        // Fog Bands setup
        for (i in fogBands.indices) {
            val f = fogBands[i]
            f.y = viewHeight * (0.6f + i * 0.08f)
            f.speed = 10f + rand.nextFloat() * 20f
            f.xOffset = rand.nextFloat() * viewWidth
            f.height = 30f + rand.nextFloat() * 30f
        }
        // Shimmer motes
        for (m in shimmerMotes) {
            m.x = rand.nextFloat() * viewWidth
            m.y = rand.nextFloat() * viewHeight
            m.speed = 15f + rand.nextFloat() * 25f
            m.size = 2f + rand.nextFloat() * 3f
            m.alpha = 0.1f + rand.nextFloat() * 0.4f
        }
        // Fireflies
        for (f in fireflies) {
            f.x = rand.nextFloat() * viewWidth
            f.y = rand.nextFloat() * viewHeight
            f.vx = -20f + rand.nextFloat() * 40f
            f.vy = -20f + rand.nextFloat() * 40f
            f.pulsePhase = rand.nextFloat() * 100f
        }
        nextShootingStarTime = System.currentTimeMillis() + 15000 + rand.nextInt(30000)
        nextLightningTimeMs = System.currentTimeMillis() + 4000 + rand.nextInt(8000)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        renderThread = Thread(this).apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        Log.d(TAG, "Surface sized changed: $width x $height")

        // Reallocate the clock text cache bitmaps fitting the screen sizes
        val clockW = (width * 0.95f).toInt().coerceAtLeast(800)
        val clockH = (height * 0.65f).toInt().coerceAtLeast(300)
        clockBmp = Bitmap.createBitmap(clockW, clockH, Bitmap.Config.ARGB_8888)
        clockCanvas = Canvas(clockBmp!!)
        prevClockBmp = Bitmap.createBitmap(clockW, clockH, Bitmap.Config.ARGB_8888)
        prevClockCanvas = Canvas(prevClockBmp!!)
        colonBmp = Bitmap.createBitmap(clockW, clockH, Bitmap.Config.ARGB_8888)
        colonCanvas = Canvas(colonBmp!!)
        cachedClockMinute = -1

        initParticles()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var retry = true
        isRunning = false
        while (retry) {
            try {
                renderThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                // Keep trying to join
            }
        }
        renderThread = null
    }

    override fun run() {
        while (isRunning) {
            val nowNano = System.nanoTime()
            val deltaTimeSec = ((nowNano - lastUpdateTime) / 1000000000.0).toFloat().coerceIn(0.001f, 0.1f)
            lastUpdateTime = nowNano
            frameDt = deltaTimeSec

            updatePhysics(deltaTimeSec)

            var canvas: Canvas? = null
            try {
                if (isRunning && holder.surface?.isValid == true) {
                    canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        try {
                            holder.lockHardwareCanvas()
                        } catch (e: Exception) {
                            holder.lockCanvas()
                        }
                    } else {
                        holder.lockCanvas()
                    }
                    if (canvas != null) {
                        synchronized(holder) {
                            if (isRunning) {
                                drawLayers(canvas)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in drawing thread", e)
            } finally {
                if (canvas != null) {
                    try {
                        if (holder.surface?.isValid == true) {
                            holder.unlockCanvasAndPost(canvas)
                        } else {
                            Log.w(TAG, "Surface became invalid, dropping current frame")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in posting canvas", e)
                    }
                }
            }

            // Cap at 30 FPS
            val processTimeMs = (System.nanoTime() - nowNano) / 1000000
            val sleepTimeMs = 33 - processTimeMs
            if (sleepTimeMs > 0) {
                try {
                    Thread.sleep(sleepTimeMs)
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Updates physics with delta-time. Never allocates inside loop.
     */
    private fun updatePhysics(dt: Float) {
        val nowMs = System.currentTimeMillis()

        // 1. Stars stable position regeneration check (by day of year)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val currentDay = cal.get(Calendar.DAY_OF_YEAR)
        if (starPositionsDayOfYear != currentDay) {
            starPositionsDayOfYear = currentDay
            val starRand = java.util.Random(currentDay.toLong())
            for (s in stars) {
                s.x = starRand.nextFloat() * viewWidth
                s.y = starRand.nextFloat() * (viewHeight * 0.65f)
                s.baseAlpha = 0.3f + starRand.nextFloat() * 0.7f
                s.speed = 1.0f + starRand.nextFloat() * 2.5f
                s.offset = starRand.nextFloat() * 100f
                s.size = 0.8f + starRand.nextFloat() * 1.1f
                // Subtle stellar color variety: mostly white, some blue-white, a few warm
                val tintRoll = starRand.nextFloat()
                when {
                    tintRoll < 0.18f -> { s.tintR = 190; s.tintG = 215; s.tintB = 255 }
                    tintRoll < 0.30f -> { s.tintR = 255; s.tintG = 226; s.tintB = 190 }
                    else -> { s.tintR = 255; s.tintG = 255; s.tintB = 255 }
                }
            }
        }

        // 2. Clear Sky Shooting Star Physics
        val cond = weatherState.conditionId
        val isClearNight = (cond == 800 || cond == 801) && isNightTime()
        if (isClearNight) {
            if (!shootingStar.active) {
                if (nowMs >= nextShootingStarTime) {
                    shootingStar.active = true
                    shootingStar.startX = viewWidth * (0.2f + rand.nextFloat() * 0.6f)
                    shootingStar.startY = viewHeight * (0.05f + rand.nextFloat() * 0.25f)
                    shootingStar.x = shootingStar.startX
                    shootingStar.y = shootingStar.startY
                    shootingStar.vx = -300f - rand.nextFloat() * 300f
                    shootingStar.vy = 150f + rand.nextFloat() * 150f
                    shootingStar.progress = 0f
                    shootingStar.maxLife = 0.4f + rand.nextFloat() * 0.4f
                    nextShootingStarTime = nowMs + 20000 + rand.nextInt(40000)
                }
            } else {
                shootingStar.progress += dt / shootingStar.maxLife
                if (shootingStar.progress >= 1.0f) {
                    shootingStar.active = false
                } else {
                    shootingStar.x = shootingStar.startX + shootingStar.vx * (shootingStar.progress * shootingStar.maxLife)
                    shootingStar.y = shootingStar.startY + shootingStar.vy * (shootingStar.progress * shootingStar.maxLife)
                }
            }
        } else {
            shootingStar.active = false
        }

        // 3. Date+Bar Fade out/in during Shooting Star
        if (shootingStar.active) {
            dateBarAlpha = (dateBarAlpha - dt / 0.3f).coerceAtLeast(0f)
        } else {
            dateBarAlpha = (dateBarAlpha + dt / 0.5f).coerceAtLeast(0f).coerceAtMost(1f)
        }

        // 4. Rain Particles Physics (condition codes 300-531)
        val isRain = weatherState.conditionId in 300..531
        if (isRain) {
            // Scale active rain count based on rain intensity (rainMmH)
            val rainIntensityFactor = (weatherState.rainMmH * 100f).coerceIn(50f, 500f)
            val activeRainCount = rainIntensityFactor.toInt()

            for (i in 0 until activeRainCount) {
                val p = rainParticles[i]
                p.y += p.vy * dt
                p.x += p.vx * dt

                // Boundary check
                if (p.y > viewHeight || p.x < -20 || p.x > viewWidth + 20) {
                    p.y = -20f
                    p.x = rand.nextFloat() * viewWidth
                    if (p.layer == 0) {
                        p.vy = 280f + rand.nextFloat() * 160f
                        p.vx = weatherState.windSpeed * 5f
                    } else {
                        p.vy = 520f + rand.nextFloat() * 260f
                        p.vx = weatherState.windSpeed * 8f
                    }

                    // Spawn a small water splash at bottom
                    for (sp in splashParticles) {
                        if (sp.life <= 0f) {
                            sp.x = p.x.coerceIn(0f, viewWidth)
                            sp.y = viewHeight - 2f
                            sp.vx = -25f + rand.nextFloat() * 50f
                            sp.vy = -30f - rand.nextFloat() * 50f
                            sp.alpha = 0.5f
                            sp.maxLife = 0.2f + rand.nextFloat() * 0.15f
                            sp.life = sp.maxLife
                            break
                        }
                    }
                }
            }
        }

        // Update active splashes
        for (sp in splashParticles) {
            if (sp.life > 0f) {
                sp.life -= dt
                sp.x += sp.vx * dt
                // add subtle gravity to splash
                sp.vy += 220f * dt
                sp.y += sp.vy * dt
                sp.alpha = (sp.life / sp.maxLife) * 0.6f
            }
        }

        // 5. Snow Particles Physics (condition codes 600-622)
        val isSnow = weatherState.conditionId in 600..622
        if (isSnow) {
            for (p in snowParticles) {
                p.y += p.vy * dt
                p.wOffset += p.wWiggleSpeed * dt
                // Draw sinuous wobble
                val wobbleX = sin(p.wOffset) * 20f * dt
                p.x += wobbleX

                if (p.y > viewHeight || p.x < -10f || p.x > viewWidth + 10f) {
                    p.y = -10f
                    p.x = rand.nextFloat() * viewWidth
                    p.vy = 30f + rand.nextFloat() * 40f
                }
            }
        }

        // 6. Clouds drifting
        val isCloudy = weatherState.cloudCover > 30
        if (isCloudy) {
            for (c in clouds) {
                c.x -= c.speed * dt
                if (c.x < -c.width * 1.5f) {
                    c.x = viewWidth + 50f
                    c.y = 30f + rand.nextFloat() * 100f
                    c.speed = 4f + rand.nextFloat() * 8f
                }
            }
        }

        // 7. Fog Bands
        val isFoggy = weatherState.conditionId in 701..781
        if (isFoggy) {
            for (f in fogBands) {
                f.xOffset += f.speed * dt
                if (f.xOffset > viewWidth * 2f) {
                    f.xOffset = 0f
                }
            }
        }

        // 8. Lightning Physics (Thunderstorm 200-232)
        val isThunderstorm = weatherState.conditionId in 200..232
        if (isThunderstorm) {
            if (!lightningActive) {
                if (nowMs >= nextLightningTimeMs) {
                    lightningActive = true
                    lightningAlpha = 1.0f
                    lightningX = viewWidth * (0.15f + rand.nextFloat() * 0.7f)
                    nextLightningTimeMs = nowMs + 4000 + rand.nextInt(8000)

                    // Generate a jagged thunder path once
                    lightningPath.reset()
                    lightningPath.moveTo(lightningX, 0f)
                    var curY = 0f
                    var curX = lightningX
                    val segments = 6 + rand.nextInt(4)
                    val segHeight = (viewHeight * 0.6f) / segments
                    for (i in 1..segments) {
                        curY += segHeight
                        curX += -25f + rand.nextFloat() * 50f
                        lightningPath.lineTo(curX, curY)
                    }
                }
            } else {
                lightningAlpha -= dt / 0.3f
                if (lightningAlpha <= 0f) {
                    lightningActive = false
                }
            }
        } else {
            lightningActive = false
        }

        // 9. Day Shimmer or Night Fireflies
        val dayTime = isDayTime()
        val noPrecipitation = !isRain && !isSnow && !isFoggy && !isThunderstorm
        if (noPrecipitation) {
            if (dayTime) {
                for (m in shimmerMotes) {
                    m.y -= m.speed * dt
                    if (m.y < -10f) {
                        m.y = viewHeight + 10f
                        m.x = rand.nextFloat() * viewWidth
                    }
                }
            } else {
                for (f in fireflies) {
                    // Small brownian walk offset
                    f.vx += (-50f + rand.nextFloat() * 100f) * dt
                    f.vy += (-50f + rand.nextFloat() * 100f) * dt
                    // clamp speed
                    val speed = sqrt(f.vx * f.vx + f.vy * f.vy)
                    if (speed > 40f) {
                        f.vx = (f.vx / speed) * 40f
                        f.vy = (f.vy / speed) * 40f
                    }
                    f.x += f.vx * dt
                    f.y += f.vy * dt
                    f.pulsePhase += dt * 3.14f

                    // Soft boundary check with bounce
                    if (f.x < 10f) { f.x = 10f; f.vx = -f.vx }
                    if (f.x > viewWidth - 10f) { f.x = viewWidth - 10f; f.vx = -f.vx }
                    if (f.y < 10f) { f.y = 10f; f.vy = -f.vy }
                    if (f.y > viewHeight - 10f) { f.y = viewHeight - 10f; f.vy = -f.vy }
                }
            }
        }

        // 10. Ambient particles drifting (Always)
        for (p in ambientParticles) {
            p.x += p.vx * dt
            p.y += p.vy * dt

            // Soft wall bounces with clamping
            if (p.x < 2f || p.x > viewWidth - 2f) {
                p.vx = -p.vx
                p.x = p.x.coerceIn(2f, viewWidth - 2f)
            }
            if (p.y < 2f || p.y > viewHeight - 2f) {
                p.vy = -p.vy
                p.y = p.y.coerceIn(2f, viewHeight - 2f)
            }
        }
    }

    /**
     * Helper to lerp colors.
     */
    private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + f * (Color.alpha(to) - Color.alpha(from))).toInt()
        val r = (Color.red(from) + f * (Color.red(to) - Color.red(from))).toInt()
        val g = (Color.green(from) + f * (Color.green(to) - Color.green(from))).toInt()
        val b = (Color.blue(from) + f * (Color.blue(to) - Color.blue(from))).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun isDayTime(): Boolean {
        val nowSec = System.currentTimeMillis() / 1000L
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        
        // Under default placeholder fallback (sunset - sunrise = 24 hours), determine day/night based on local hour
        val isDefault = (weatherState.sunsetEpoch - weatherState.sunriseEpoch == 24 * 3600L)
        if (isDefault) {
            return hour in 6..18 // Day is 6:00 AM to 6:59 PM, Night is 7:00 PM to 5:59 AM
        }
        
        // For real OWM data, force/safeguard obvious night hours (7:00 PM to 4:59 AM)
        if (hour >= 19 || hour < 5) {
            return false
        }
        // Force/safeguard obvious core day hours (7:00 AM to 5:59 PM)
        if (hour in 7..17) {
            return true
        }

        // Use precise actual OWM epoch bounds during dawn/dusk twilight transition periods
        return nowSec in (weatherState.sunriseEpoch + 1) until weatherState.sunsetEpoch
    }

    private fun isNightTime(): Boolean {
        return !isDayTime()
    }

    /**
     * Compute and blend sky gradients, celestial arc, and weather layers.
     */
    private fun drawLayers(canvas: Canvas) {
        val timeMs = System.currentTimeMillis()
        val isDay = isDayTime()

        // -------------------------------------------------------------
        // LAYER 0 — SOLAR POSITION (drives the sky palette + celestial arc)
        // -------------------------------------------------------------
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val timeFraction = hour + minute / 60.0f + second / 3600.0f

        // Solar Declination (radians)
        val declination = (0.409 * sin((2.0 * Math.PI / 365.0) * (dayOfYear - 80))).toFloat()

        // Solar hour angle from local solar time (82.5°E is India's IST meridian)
        val localSolarTime = timeFraction + (WeatherRepository.LON - 82.5f) / 15.0f
        val hourAngle = ((localSolarTime - 12.0) * (Math.PI / 12.0)).toFloat()
        val latRad = (WeatherRepository.LAT * Math.PI / 180.0).toFloat()

        // Solar Altitude h: sin(h) = sin(lat)*sin(dec) + cos(lat)*cos(dec)*cos(H)
        val sinElev = sin(latRad) * sin(declination) + cos(latRad) * cos(declination) * cos(hourAngle)
        val altitude = asin(sinElev)
        sunAltitudeDeg = (altitude * 180.0 / Math.PI).toFloat()

        // Standard Max Elevation (Hour Angle H = 0)
        val sinMaxElev = sin(latRad) * sin(declination) + cos(latRad) * cos(declination)
        val maxAltitude = asin(sinMaxElev).coerceAtLeast(0.1f)

        // Solar Azimuth: cos(A) = (sin(dec) - sin(lat)*sin(h))/(cos(lat)*cos(h))
        val cosElev = cos(altitude)
        var cosAz = (sin(declination) - sin(latRad) * sinElev) / (cos(latRad) * cosElev)
        cosAz = cosAz.coerceIn(-1f, 1f)
        var azimuth = acos(cosAz)
        if (sin(hourAngle) > 0) {
            azimuth = (2.0 * Math.PI).toFloat() - azimuth
        }
        sunAzimuthDeg = (azimuth * 180.0 / Math.PI).toFloat()

        // Screen mapping: visible horizontal arc is 60..300 degrees azimuth
        val celX = viewWidth * (sunAzimuthDeg - 60f) / 240f
        val celY = viewHeight * 0.85f * (1.0f - (sin(altitude) / sin(maxAltitude)).toFloat().coerceIn(0f, 1f))

        // -------------------------------------------------------------
        // LAYER 1 — SKY GRADIENT: continuous 24h palette from sun altitude
        // (deep night → astronomical twilight → blue hour → golden hour → day)
        // -------------------------------------------------------------
        val skyPair = skyColorsForAltitude(sunAltitudeDeg)
        var targetTop = skyPair.first
        var targetBottom = skyPair.second

        // Weather blending: pull the palette toward slate greys for cloud/storm,
        // scaled darker at night so overcast nights stay properly dark.
        val condNow = weatherState.conditionId
        val isStorm = condNow in 200..232 || condNow in 300..531
        val dayFactor = ((sunAltitudeDeg + 6f) / 26f).coerceIn(0f, 1f)
        if (isStorm || weatherState.cloudCover > 50) {
            val greyTop = lerpColor(Color.parseColor("#0D1218"), Color.parseColor("#37474F"), dayFactor)
            val greyBottom = lerpColor(Color.parseColor("#161E27"), Color.parseColor("#78909C"), dayFactor)
            val mix = if (isStorm) 0.7f else (weatherState.cloudCover / 100f) * 0.55f
            targetTop = lerpColor(targetTop, greyTop, mix)
            targetBottom = lerpColor(targetBottom, greyBottom, mix)
        }

        // Ease the rendered sky toward the target (~30s exponential approach)
        val step = (frameDt / 30f).coerceAtMost(1f)
        renderedTopColor = lerpColor(renderedTopColor, targetTop, step)
        renderedBottomColor = lerpColor(renderedBottomColor, targetBottom, step)

        // Rebuild the gradient shader only when the blended colors actually move
        if (renderedTopColor != skyShaderTop || renderedBottomColor != skyShaderBottom) {
            skyShaderTop = renderedTopColor
            skyShaderBottom = renderedBottomColor
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, viewHeight,
                renderedTopColor, renderedBottomColor, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, skyPaint)

        // Horizon glow hugging the bottom edge near the sun during golden/blue hour
        if (!isStorm && sunAltitudeDeg > -9f && sunAltitudeDeg < 9f) {
            val glowStrength = (1f - abs(sunAltitudeDeg) / 9f).coerceIn(0f, 1f)
            horizonGlowPaint.shader = horizonGlowShader
            horizonGlowPaint.alpha = (glowStrength * 110f).toInt()
            canvas.save()
            canvas.translate(celX, viewHeight * 1.02f)
            canvas.scale(viewWidth * 0.55f, viewHeight * 0.38f)
            canvas.drawCircle(0f, 0f, 1f, horizonGlowPaint)
            canvas.restore()
        }

        // Sun rides the solar arc; the moon rides the anti-solar arc so it is
        // actually up at night (the old code drew it at the sun's position,
        // which is below the horizon all night — the moon never appeared).
        if (isDay && sunAltitudeDeg >= -5.0f) {
            // Majestic sun with expansive glow and rotating rays
            canvas.save()
            canvas.translate(celX, celY)
            canvas.scale(1.1f, 1.1f)

            val spin = (timeMs % 32000L) / 32000f * 360f // majestic, slow rotation
            val scaleG = 1f + 0.05f * sin(timeMs / 800.0).toFloat()

            // 1. Large ambient sunlight glow (pre-built shader)
            celestialGlowPaint.shader = sunGlowShader
            celestialGlowPaint.alpha = 255
            canvas.drawCircle(0f, 0f, 280f, celestialGlowPaint)

            // 2. Long breathing rays
            val pulse = (1f + 0.08f * sin(timeMs / 1200.0).toFloat())
            val raySpin = (timeMs % 48000L) / 48000f * 360f
            celestialRayPaint.shader = null
            celestialRayPaint.color = Color.argb(60, 255, 244, 200)
            for (i in 0 until 12) {
                canvas.save()
                canvas.rotate(i * 30f + raySpin)
                val startDist = 48f * scaleG
                val endDist = (125f + 25f * sin(timeMs / 1000.0 + i).toFloat()) * pulse
                celestialRayPaint.strokeWidth = 3f + 1.5f * sin(timeMs / 1000.0 + i).toFloat()
                canvas.drawLine(0f, startDist, 0f, endDist, celestialRayPaint)
                canvas.restore()
            }

            // 3. Golden sun core (fixed-radius shader, sized via canvas scale)
            celestialPaint.style = Paint.Style.FILL
            celestialPaint.shader = sunCoreShader
            canvas.save()
            canvas.scale(scaleG, scaleG)
            canvas.drawCircle(0f, 0f, 24f, celestialPaint)
            canvas.restore()

            // 4. Rotating pill spokes
            celestialPaint.shader = null
            celestialPaint.style = Paint.Style.STROKE
            celestialPaint.strokeWidth = 5.5f
            celestialPaint.strokeCap = Paint.Cap.ROUND
            celestialPaint.color = Color.rgb(249, 115, 22)
            for (i in 0 until 8) {
                canvas.save()
                canvas.rotate(i * 45f + spin)
                canvas.drawLine(0f, 31f * scaleG, 0f, 40f * scaleG, celestialPaint)
                canvas.restore()
            }
            canvas.restore()
        } else if (!isDay) {
            // Anti-solar position: opposite azimuth, mirrored altitude
            val moonAltDeg = -sunAltitudeDeg
            if (moonAltDeg >= -5f) {
                val moonAz = (sunAzimuthDeg + 180f) % 360f
                val moonX = viewWidth * (moonAz - 60f) / 240f
                val moonY = viewHeight * 0.85f *
                    (1.0f - (sin(-altitude) / sin(maxAltitude)).toFloat().coerceIn(0f, 1f))
                drawMoon(canvas, moonX, moonY, timeMs)
            }
        }


        // -------------------------------------------------------------
        // LAYER 3 — WEATHER PARTICLE SYSTEM
        // -------------------------------------------------------------
        val condCode = weatherState.conditionId

        // 3a. Night stars — visible whenever dark, dimmed through partial cloud
        if (!isDay) {
            val cloudDim = 1f - (weatherState.cloudCover / 100f) * 0.85f
            if (cloudDim > 0.06f) {
                for (s in stars) {
                    // Twinkle cycle calculations
                    val phase = (timeMs / 1000.0 * s.speed + s.offset).toFloat()
                    val twinkleFactor = 0.4f + 0.6f * (sin(phase) * 0.5f + 0.5f)
                    val a = (s.baseAlpha * twinkleFactor * cloudDim * 255f).toInt().coerceIn(0, 255)
                    starPaint.color = Color.argb(a, s.tintR, s.tintG, s.tintB)
                    canvas.drawCircle(s.x, s.y, s.size, starPaint)
                }
            }

            // Shooting star with a glowing head (active on clear nights only)
            if (shootingStar.active) {
                val tailX = shootingStar.x - shootingStar.vx * 0.1f
                val tailY = shootingStar.y - shootingStar.vy * 0.1f
                val lineAlpha = ((1.0f - shootingStar.progress) * 220f).toInt().coerceIn(0, 255)
                shootingStarPaint.color = Color.argb(lineAlpha, 255, 255, 255)
                canvas.drawLine(tailX, tailY, shootingStar.x, shootingStar.y, shootingStarPaint)
                starPaint.color = Color.argb(lineAlpha, 255, 255, 255)
                canvas.drawCircle(shootingStar.x, shootingStar.y, 2.2f, starPaint)
            }
        }

        // 3b. Shimmer Motes & Fireflies (Sunny or clear night, no precip)
        val noPrecip = condCode !in 200..232 && condCode !in 300..531 && condCode !in 600..622 && condCode !in 701..781
        if (noPrecip) {
            if (isDay) {
                // Rising shimmer motes: pale gold at midday, ember-orange in golden hour
                val emberMix = (1f - (sunAltitudeDeg / 25f)).coerceIn(0f, 1f)
                val moteColor = lerpColor(Color.rgb(255, 230, 160), Color.rgb(255, 150, 70), emberMix)
                for (m in shimmerMotes) {
                    val mAlpha = (m.alpha * 255).toInt().coerceIn(0, 255)
                    shimmerPaint.color = (moteColor and 0x00FFFFFF) or (mAlpha shl 24)
                    canvas.drawCircle(m.x, m.y, m.size, shimmerPaint)
                }
            } else {
                // Fireflies slow walk glow pulse (pre-built unit shader, alpha-pulsed)
                fireflyPaint.shader = fireflyGlowShader
                for (f in fireflies) {
                    val pulse = 0.4f + 0.6f * (sin(f.pulsePhase) * 0.5f + 0.5f)
                    fireflyPaint.alpha = (pulse * 255).toInt()
                    canvas.save()
                    canvas.translate(f.x, f.y)
                    canvas.scale(6f, 6f)
                    canvas.drawCircle(0f, 0f, 1f, fireflyPaint)
                    canvas.restore()
                }
            }
        }

        // 3c. Overcast Clouds (cloudCover > 30)
        if (weatherState.cloudCover > 30) {
            val isStormSky = condCode in 200..232 || condCode in 300..531
            val cloudColorHex = if (isStormSky) "#3A4B56" else "#AEBCC4"
            var cloudAlpha = (weatherState.cloudCover / 200.0) // max 0.5 (very soft)
            if (isStormSky) cloudAlpha += 0.1
            cloudAlpha = cloudAlpha.coerceIn(0.08, 0.6)

            cloudPaint.color = Color.parseColor(cloudColorHex)
            cloudPaint.alpha = (cloudAlpha * 255).toInt()

            for (c in clouds) {
                // Render cloud sprite clusters (3 overlapping ellipses)
                canvas.drawOval(c.x, c.y, c.x + c.width * c.scale, c.y + c.height * c.scale, cloudPaint)
                canvas.drawOval(c.x + c.width * 0.25f, c.y - c.height * 0.3f, c.x + c.width * 0.85f * c.scale, c.y + c.height * 0.7f * c.scale, cloudPaint)
                canvas.drawOval(c.x + c.width * 0.45f, c.y + c.height * 0.1f, c.x + c.width * 1.1f * c.scale, c.y + c.height * 0.9f * c.scale, cloudPaint)
            }
        }

        // 3d. Rain drops (thin angled lines) and splashes
        if (condCode in 300..531) {
            val rainIntensityFactor = (weatherState.rainMmH * 100f).coerceIn(50f, 500f)
            val activeRainCount = rainIntensityFactor.toInt()

            // Two-depth parallax rain: dim thin far streaks, bright long near streaks
            for (i in 0 until activeRainCount) {
                val p = rainParticles[i]
                if (p.layer == 0) {
                    rainPaint.strokeWidth = 1.0f
                    rainPaint.color = Color.argb((p.alpha * 150).toInt(), 160, 190, 235)
                } else {
                    rainPaint.strokeWidth = 1.8f
                    rainPaint.color = Color.argb((p.alpha * 255).toInt(), 190, 215, 255)
                }
                canvas.drawLine(p.x, p.y, p.x + p.vx * 0.02f, p.y + p.length, rainPaint)
            }

            // Draw splashes at ground boundaries
            for (sp in splashParticles) {
                if (sp.life > 0f) {
                    splashPaint.color = Color.argb((sp.alpha * 255).toInt(), 200, 220, 255)
                    // Draw tiny splash drops radiating
                    canvas.drawLine(sp.x, sp.y, sp.x + sp.vx * 0.05f, sp.y + sp.vy * 0.05f, splashPaint)
                }
            }
        }

        // 3e. Snow Particles (condition 600-622)
        if (condCode in 600..622) {
            for (p in snowParticles) {
                canvas.drawCircle(p.x, p.y, p.size, snowPaint)
            }
        }

        // 3f. Fog / Mist Ribbon Bands
        if (condCode in 701..781) {
            // Smoky, dust or fog/mist
            val isSmoke = condCode == 711
            val fogColorHex = if (isSmoke) "#E59866" else "#BDC3C7" // smoky orange vs misty slate
            fogPaint.color = Color.parseColor(fogColorHex)

            for (i in fogBands.indices) {
                val f = fogBands[i]
                val fogAlpha = (0.15f + i * 0.02f).coerceIn(0.12f, 0.25f)
                fogPaint.alpha = (fogAlpha * 255).toInt()

                // Draw simple horizontal rolling mist layers using sinusoidal offsets
                val offset = f.xOffset
                fogPath.reset()
                fogPath.moveTo(0f, f.y)
                var xTick = 0f
                while (xTick <= viewWidth + 40f) {
                    val yTick = f.y + sin((xTick + offset) * 0.01f).toFloat() * 12f
                    fogPath.lineTo(xTick, yTick)
                    xTick += 40f
                }
                fogPath.lineTo(viewWidth, f.y + f.height)
                fogPath.lineTo(0f, f.y + f.height)
                fogPath.close()
                canvas.drawPath(fogPath, fogPaint)
            }
        }

        // 3g. Custom Thunderstorm lightning overlays
        if (condCode in 200..232 && lightningActive) {
            // Cloud-interior glow around the strike origin, fading with the bolt
            lightningGlowPaint.shader = lightningGlowShader
            lightningGlowPaint.alpha = (lightningAlpha * 200f).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(lightningX, viewHeight * 0.12f)
            canvas.scale(viewWidth * 0.28f, viewHeight * 0.22f)
            canvas.drawCircle(0f, 0f, 1f, lightningGlowPaint)
            canvas.restore()

            // Draw Lightning bolt path
            lightningPaint.alpha = (lightningAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawPath(lightningPath, lightningPaint)

            // Draw branching spikes randomly
            if (lightningAlpha > 0.4f) {
                val branchPath = Path()
                branchPath.moveTo(lightningX, viewHeight * 0.25f)
                branchPath.lineTo(lightningX + 40f, viewHeight * 0.35f)
                branchPath.lineTo(lightningX + 25f, viewHeight * 0.42f)
                canvas.drawPath(branchPath, lightningPaint)
            }

            // Flash effect on first frame (raw pure white over screen)
            if (lightningAlpha > 0.92f) {
                canvas.drawColor(Color.argb(120, 255, 255, 255))
            }
        }


        // -------------------------------------------------------------
        // LAYER 4 — AMBIENT FLOATING PARTICLES (always)
        // -------------------------------------------------------------
        // 20 small blue-white dust particles drifting gently
        for (p in ambientParticles) {
            ambientPaint.alpha = (p.alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.size, ambientPaint)
        }


        // -------------------------------------------------------------
        // LAYER 5 — THE CLOCK (cached bitmaps, re-rendered once per minute)
        // -------------------------------------------------------------
        // Anti burn-in: the clock + info composition slowly orbits a few px
        val burnX = 3f * sin(timeMs / 480000.0 * 2.0 * Math.PI).toFloat()
        val burnY = 2f * sin(timeMs / 657000.0 * 2.0 * Math.PI).toFloat()
        canvas.save()
        canvas.translate(burnX, burnY)

        clockBottomY = viewHeight * 0.65f
        val dnsVal = context.resources.displayMetrics.scaledDensity

        if (clockBmp != null && colonBmp != null) {
            val timeCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
            val rawHour = timeCal.get(Calendar.HOUR_OF_DAY)
            val mm = timeCal.get(Calendar.MINUTE)
            val minuteKey = rawHour * 60 + mm

            if (minuteKey != cachedClockMinute) {
                // Swap current into previous for the crossfade, then re-render.
                // Glow tint (sky-reactive) also refreshes here, once per minute.
                val swapBmp = prevClockBmp
                val swapCanvas = prevClockCanvas
                prevClockBmp = clockBmp
                prevClockCanvas = clockCanvas
                clockBmp = swapBmp
                clockCanvas = swapCanvas
                renderClockCache(rawHour, mm, dnsVal)
                clockSwapTimeMs = if (cachedClockMinute == -1) 0L else timeMs
                cachedClockMinute = minuteKey
            }

            val curB = clockBmp!!
            val finalL = (viewWidth - curB.width) / 2f
            val finalT = (viewHeight - curB.height) / 2f - 68f * dnsVal // Shift up slightly to balance date bar

            // Minute-change crossfade (400ms)
            val fade = ((timeMs - clockSwapTimeMs) / 400f).coerceIn(0f, 1f)
            val prevB = prevClockBmp
            if (fade < 1f && prevB != null) {
                clockBmpPaint.alpha = ((1f - fade) * 255).toInt()
                canvas.drawBitmap(prevB, finalL, finalT, clockBmpPaint)
                clockBmpPaint.alpha = (fade * 255).toInt()
                canvas.drawBitmap(curB, finalL, finalT, clockBmpPaint)
            } else {
                clockBmpPaint.alpha = 255
                canvas.drawBitmap(curB, finalL, finalT, clockBmpPaint)
            }

            // Breathing colon: soft sine ease on the cached full-brightness colon
            val breathe = (0.5 + 0.5 * sin(timeMs / 2000.0 * 2.0 * Math.PI - Math.PI / 2.0)).toFloat()
            colonBmpPaint.alpha = ((0.35f + 0.65f * breathe) * 255).toInt()
            canvas.drawBitmap(colonBmp!!, finalL, finalT, colonBmpPaint)

            // Set highly precise clock bottom baseline relative to layout
            clockBottomY = finalT + clockBaselineInBmp + 25f * dnsVal
        }

         // -------------------------------------------------------------
        // LAYER 6 — DATE + CONDITION BAR
        // -------------------------------------------------------------
        val dns = context.resources.displayMetrics.scaledDensity

        // Math-based elements of weatherState
        val currentTemp = weatherState.tempC.roundToInt()
        val feelsTemp = weatherState.feelsLikeC.roundToInt()
        val condDesc = weatherState.description.uppercase()
        val limitHigh = weatherState.highC.roundToInt()
        val limitLow = weatherState.lowC.roundToInt()
        val humidityVal = weatherState.humidity
        val windVal = weatherState.windSpeed.roundToInt()

        // Setup base texts
        val dfDay = SimpleDateFormat("EEEE", Locale.US).format(cal.time).uppercase()
        val dfFullDate = SimpleDateFormat("dd MMMM yyyy", Locale.US).format(cal.time).uppercase()
        val dateLineText = "$dfDay  ·  $dfFullDate"

        // Paint configurations
        val baseAlpha = (dateBarAlpha * 255).toInt()
        val dateBaseColor = Color.argb((0.9f * dateBarAlpha * 255).toInt(), 220, 235, 255) // brighter elegant ice white
        
        datePaint.color = dateBaseColor
        datePaint.maskFilter = null
        datePaint.textSize = 21.5f * dns
        datePaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)

        // Glow pass style for elegant feel
        dateGlowPaint.color = dateBaseColor
        dateGlowPaint.textSize = 21.5f * dns
        dateGlowPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        dateGlowPaint.maskFilter = blur6
        dateGlowPaint.alpha = (0.35f * dateBarAlpha * 255).toInt()

        // Render Date Line (Clock bottom + 14px)
        val l1Y = clockBottomY + 14f
        val l1X = (viewWidth - datePaint.measureText(dateLineText)) / 2f
        canvas.drawText(dateLineText, l1X, l1Y, dateGlowPaint)
        canvas.drawText(dateLineText, l1X, l1Y, datePaint)

        // Render sleek fading horizontal divider line (14px below Date Line)
        val dividerY = l1Y + 18f
        dividerPaint.strokeWidth = 1.6f * dns
        val dividerHalfW = viewWidth * 0.28f // Beautiful, short, concentrated divider
        val dividerAlphaInt = (0.45f * dateBarAlpha * 255).toInt()
        if (dividerAlphaInt != dividerShaderAlpha) {
            // Horizontal gradient is y-independent; rebuild only when alpha moves
            dividerShaderAlpha = dividerAlphaInt
            dividerPaint.shader = LinearGradient(
                viewWidth / 2f - dividerHalfW, 0f,
                viewWidth / 2f + dividerHalfW, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(dividerAlphaInt, 180, 215, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawLine(viewWidth / 2f - dividerHalfW, dividerY, viewWidth / 2f + dividerHalfW, dividerY, dividerPaint)

        // -------------------------------------------------------------
        // ROW 1 — Current Weather Highlight (Divider + 38px)
        // -------------------------------------------------------------
        val r1Y = dividerY + 38f

        // Configure row 1 field paints (allocated once, tuned per frame)
        tempPaint.textSize = 38f * dns
        tempPaint.color = Color.WHITE
        tempPaint.alpha = (dateBarAlpha * 255).toInt()

        tempGlowPaint.textSize = 38f * dns
        tempGlowPaint.color = glowColorForAltitude(sunAltitudeDeg)
        tempGlowPaint.maskFilter = blur15
        tempGlowPaint.alpha = (0.4f * dateBarAlpha * 255).toInt()

        descPaint.textSize = 19f * dns
        descPaint.color = Color.argb((0.8f * dateBarAlpha * 255).toInt(), 200, 220, 245)

        locationPaint.textSize = 19f * dns
        locationPaint.color = Color.argb((0.5f * dateBarAlpha * 255).toInt(), 160, 190, 225)

        val tempStr = "${currentTemp}°C"
        val locationStr = WeatherRepository.LOCATION_LABEL.uppercase()
        val descStr = "FEELS LIKE ${feelsTemp}°  ·  $condDesc"

        // Measure Row 1 contents for perfect centering
        val r1IconSize = 44f * dns
        val r1IconGap = 12f * dns
        val r1TempW = tempPaint.measureText(tempStr)
        val r1SepGap = 18f * dns
        val r1SepW = descPaint.measureText("   |   ")
        val r1LocW = locationPaint.measureText(locationStr)
        val r1DescW = descPaint.measureText(descStr)

        val totalR1W = r1IconSize + r1IconGap + r1TempW + r1SepGap + r1LocW + r1SepW + r1DescW
        var r1Start = (viewWidth - totalR1W) / 2f

        // Draw Row 1 Icon
        val xIcon = r1Start + r1IconSize / 2f
        val yIcon = r1Y - 14f * dns
        drawMiniWeatherIcon(canvas, xIcon, yIcon, r1IconSize, weatherState.conditionId, tempPaint)

        // Draw Row 1 Temp
        val xTemp = r1Start + r1IconSize + r1IconGap
        val yTextBaseline = r1Y
        canvas.drawText(tempStr, xTemp, yTextBaseline, tempGlowPaint)
        canvas.drawText(tempStr, xTemp, yTextBaseline, tempPaint)

        // Draw Row 1 Location
        val xLoc = xTemp + r1TempW + r1SepGap
        canvas.drawText(locationStr, xLoc, yTextBaseline - 4f * dns, locationPaint)

        // Draw Row 1 Sep
        val xSep = xLoc + r1LocW
        canvas.drawText("   |   ", xSep, yTextBaseline - 4f * dns, locationPaint)

        // Draw Row 1 Description
        val xDesc = xSep + r1SepW
        canvas.drawText(descStr, xDesc, yTextBaseline - 4f * dns, descPaint)


        // -------------------------------------------------------------
        // ROW 2 — Vital Metrics Grid (Row 1 + 36px)
        // -------------------------------------------------------------
        val r2Y = r1Y + 36f * dns

        metricPaint.textSize = 16.5f * dns
        metricPaint.color = Color.argb((0.65f * dateBarAlpha * 255).toInt(), 180, 205, 235)

        // We prepare our items (Enlarged)
        val iconSize = 23.5f * dns // Increased from 18f
        val triSize = 11.5f * dns  // Increased from 8.5f
        val iconGap = 8f * dns

        // Column 1: ▲ H: 32°  ▼ L: 25° (Math-perfect tracking width for exact centering)
        val wHText = metricPaint.measureText("H:${limitHigh}° ")
        val wLText = metricPaint.measureText("L:${limitLow}°")
        // total width of high/low badge: triangle + text + gap + triangle + text
        val item1W = (triSize + 4f * dns + wHText) + (triSize * 0.8f + triSize + 4f * dns + wLText)

        // Column 2: Humidity droplet + 76%
        val humText = "HUMIDITY ${humidityVal}%"
        val wHumText = metricPaint.measureText(humText)
        val item2W = iconSize + iconGap + wHumText

        // Column 3: Wind breeze + 8 KM/H
        val windText = "WIND ${windVal} KM/H"
        val wWindText = metricPaint.measureText(windText)
        val item3W = iconSize + iconGap + wWindText

        val colGap = 42f * dns
        val totalR2W = item1W + colGap + item2W + colGap + item3W
        var r2Start = (viewWidth - totalR2W) / 2f

        // Draw Item 1: High/Low
        // Custom draw colored triangles
        val xT1 = r2Start
        val yTri = r2Y - 5.5f * dns
        
        // draw up triangle (orange accent)
        triPathUp.reset()
        triPathUp.moveTo(xT1, yTri - triSize / 2f)
        triPathUp.lineTo(xT1 - triSize * 0.8f, yTri + triSize / 2f)
        triPathUp.lineTo(xT1 + triSize * 0.8f, yTri + triSize / 2f)
        triPathUp.close()
        triPaint.color = Color.rgb(255, 120, 50)
        triPaint.alpha = (dateBarAlpha * 255).toInt()
        canvas.drawPath(triPathUp, triPaint)

        val xHighText = xT1 + triSize + 4f * dns
        canvas.drawText("H:${limitHigh}° ", xHighText, r2Y, metricPaint)
        val wH = metricPaint.measureText("H:${limitHigh}° ")

        // draw down triangle (cool blue accent)
        val xT2 = xHighText + wH + triSize * 0.8f
        triPathDown.reset()
        triPathDown.moveTo(xT2, yTri + triSize / 2f)
        triPathDown.lineTo(xT2 - triSize * 0.8f, yTri - triSize / 2f)
        triPathDown.lineTo(xT2 + triSize * 0.8f, yTri - triSize / 2f)
        triPathDown.close()
        triPaint.color = Color.rgb(80, 180, 255)
        triPaint.alpha = (dateBarAlpha * 255).toInt()
        canvas.drawPath(triPathDown, triPaint)

        canvas.drawText("L:${limitLow}°", xT2 + triSize + 4f * dns, r2Y, metricPaint)

        // Draw Item 2: Humidity
        val xHumStart = r2Start + item1W + colGap
        drawMiniDroplet(canvas, xHumStart + iconSize / 2f, r2Y - 5.5f * dns, iconSize, metricPaint)
        canvas.drawText(humText, xHumStart + iconSize + iconGap, r2Y, metricPaint)

        // Draw Item 3: Wind
        val xWindStart = xHumStart + item2W + colGap
        drawMiniWind(canvas, xWindStart + iconSize / 2f, r2Y - 5.5f * dns, iconSize, metricPaint)
        canvas.drawText(windText, xWindStart + iconSize + iconGap, r2Y, metricPaint)

        // Close the anti burn-in drift transform opened before LAYER 5
        canvas.restore()
    }

    // Sky palette keyframes across the solar day (sun altitude in degrees):
    // deep night → astronomical twilight → blue hour → golden hour → sunrise → morning → midday
    private val skyKeyAlts = floatArrayOf(-30f, -14f, -7f, -2f, 3f, 10f, 30f)
    private val skyKeyTops = intArrayOf(
        Color.parseColor("#020408"),
        Color.parseColor("#050B20"),
        Color.parseColor("#0B1240"),
        Color.parseColor("#2A1348"),
        Color.parseColor("#234A8C"),
        Color.parseColor("#1B62B8"),
        Color.parseColor("#1565C0")
    )
    private val skyKeyBottoms = intArrayOf(
        Color.parseColor("#0A1128"),
        Color.parseColor("#12204A"),
        Color.parseColor("#3A3670"),
        Color.parseColor("#E86A2B"),
        Color.parseColor("#F2954A"),
        Color.parseColor("#6FB1E8"),
        Color.parseColor("#42A5F5")
    )

    private fun skyColorsForAltitude(altDeg: Float): Pair<Int, Int> {
        if (altDeg <= skyKeyAlts.first()) return skyKeyTops.first() to skyKeyBottoms.first()
        if (altDeg >= skyKeyAlts.last()) return skyKeyTops.last() to skyKeyBottoms.last()
        for (i in 0 until skyKeyAlts.size - 1) {
            if (altDeg <= skyKeyAlts[i + 1]) {
                val f = (altDeg - skyKeyAlts[i]) / (skyKeyAlts[i + 1] - skyKeyAlts[i])
                return lerpColor(skyKeyTops[i], skyKeyTops[i + 1], f) to
                        lerpColor(skyKeyBottoms[i], skyKeyBottoms[i + 1], f)
            }
        }
        return skyKeyTops.last() to skyKeyBottoms.last()
    }

    /**
     * Sky-reactive glow tint: ice blue at night, rose through twilight,
     * warm amber in full daylight.
     */
    private fun glowColorForAltitude(altDeg: Float): Int {
        val night = Color.rgb(100, 180, 255)
        val twilight = Color.rgb(255, 140, 110)
        val day = Color.rgb(255, 185, 50)
        return when {
            altDeg <= -12f -> night
            altDeg <= 0f -> lerpColor(night, twilight, (altDeg + 12f) / 12f)
            altDeg <= 10f -> lerpColor(twilight, day, altDeg / 10f)
            else -> day
        }
    }

    /**
     * Renders glowing digits + AM/PM into clockBmp and the colon (at full
     * brightness) into colonBmp. Called once per minute instead of re-blurring
     * the huge text every frame — the colon breathes via draw-time alpha.
     */
    private fun renderClockCache(rawHour: Int, mm: Int, dnsVal: Float) {
        val clockB = clockBmp ?: return
        val clockC = clockCanvas ?: return
        val colonB = colonBmp ?: return
        val colonC = colonCanvas ?: return

        clockB.eraseColor(Color.TRANSPARENT)
        colonB.eraseColor(Color.TRANSPARENT)

        val isPm = rawHour >= 12
        val hour12 = rawHour % 12
        val hh = if (hour12 == 0) 12 else hour12
        val amPmStr = if (isPm) "PM" else "AM"
        val hoursStr = String.format("%d", hh)
        val minsStr = String.format("%02d", mm)

        val clockTextSize = 182f * dnsVal
        clockFontPaint.textSize = clockTextSize
        clockGlowPaint.textSize = clockTextSize

        val amPmTextSize = clockTextSize * 0.18f
        amPmPaint.textSize = amPmTextSize
        amPmGlowPaint.textSize = amPmTextSize

        // Layout computations for centering [HH] [:] [MM]   [AM/PM]
        val wHrs = clockFontPaint.measureText(hoursStr)
        val wCol = clockFontPaint.measureText(":")
        val wMin = clockFontPaint.measureText(minsStr)
        val wAmPm = amPmPaint.measureText(amPmStr)

        val amPmGap = 16f * dnsVal
        val grandW = wHrs + wCol + wMin + amPmGap + wAmPm

        val clockX = (clockB.width - grandW) / 2f
        val clockY = clockB.height / 2f - (clockFontPaint.ascent() + clockFontPaint.descent()) / 2f
        val colonX = clockX + wHrs
        val minsX = clockX + wHrs + wCol
        val amPmX = minsX + wMin + amPmGap

        val glowColor = glowColorForAltitude(sunAltitudeDeg)
        clockGlowPaint.color = glowColor
        amPmGlowPaint.color = glowColor

        // Three soft glow passes (40/20/8px) then the sharp white core
        val passAlphas = floatArrayOf(0.3f, 0.5f, 0.8f)
        val passBlurs = arrayOf(blur40, blur20, blur8)
        for (i in passAlphas.indices) {
            clockGlowPaint.maskFilter = passBlurs[i]
            amPmGlowPaint.maskFilter = passBlurs[i]
            val a = (passAlphas[i] * 255).toInt()
            clockGlowPaint.alpha = a
            amPmGlowPaint.alpha = a
            clockC.drawText(hoursStr, clockX, clockY, clockGlowPaint)
            clockC.drawText(minsStr, minsX, clockY, clockGlowPaint)
            clockC.drawText(amPmStr, amPmX, clockY, amPmGlowPaint)
            colonC.drawText(":", colonX, clockY, clockGlowPaint)
        }

        clockFontPaint.maskFilter = null
        clockFontPaint.color = Color.WHITE
        clockFontPaint.alpha = 255
        clockC.drawText(hoursStr, clockX, clockY, clockFontPaint)
        clockC.drawText(minsStr, minsX, clockY, clockFontPaint)
        colonC.drawText(":", colonX, clockY, clockFontPaint)

        amPmPaint.maskFilter = null
        amPmPaint.color = Color.WHITE
        amPmPaint.alpha = 220
        clockC.drawText(amPmStr, amPmX, clockY, amPmPaint)

        clockBaselineInBmp = clockY + clockFontPaint.descent()
    }

    /**
     * Moon with real phase shading from weatherState.moonPhase
     * (0 = new, 0.5 = full, 1 = new again).
     */
    private fun drawMoon(canvas: Canvas, x: Float, y: Float, timeMs: Long) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(1.2f, 1.2f)

        val rock = 4f * sin(timeMs / 2000.0).toFloat() // slow swaying
        val phase = weatherState.moonPhase
        val illum = (0.5f * (1f - cos(2.0 * Math.PI * phase).toFloat())).coerceIn(0f, 1f) // 0 new → 1 full

        // 1. Ambient moonlight glow, brighter toward full moon
        celestialGlowPaint.shader = moonGlowShader
        celestialGlowPaint.alpha = (120 + 135 * illum).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, 240f, celestialGlowPaint)

        // 2. Faint counter-rotating light beams
        val pulse = (1f + 0.05f * sin(timeMs / 1800.0).toFloat())
        val raySpin = (timeMs % 64000L) / 64000f * -360f
        celestialRayPaint.shader = null
        celestialRayPaint.color = Color.argb((25 + 30 * illum).toInt(), 224, 242, 254)
        celestialRayPaint.strokeWidth = 2f
        for (i in 0 until 8) {
            canvas.save()
            canvas.rotate(i * 45f + raySpin)
            val endDist = (95f + 15f * sin(timeMs / 1500.0 + i).toFloat()) * pulse
            canvas.drawLine(0f, 32f, 0f, endDist, celestialRayPaint)
            canvas.restore()
        }

        // 3. Close halo
        celestialPaint.style = Paint.Style.FILL
        celestialPaint.strokeCap = Paint.Cap.BUTT
        celestialPaint.shader = moonHaloShader
        canvas.drawCircle(0f, 0f, 45f, celestialPaint)

        canvas.save()
        canvas.rotate(rock)

        // 4. Faint earthshine disc behind the lit portion
        celestialPaint.shader = null
        celestialPaint.color = Color.argb(45, 120, 150, 190)
        canvas.drawCircle(0f, 0f, 25f, celestialPaint)

        // 5. Lit portion: full disc minus a shadow disc swept by the cycle.
        //    Waxing pushes the shadow left (lit on the right), waning mirrors it.
        val moonPath = Path().apply { addCircle(0f, 0f, 25f, Path.Direction.CW) }
        if (illum < 0.985f) {
            val shadowOffset = illum * 50f
            val shadowX = if (phase < 0.5f) -shadowOffset else shadowOffset
            val shadowPath = Path().apply { addCircle(shadowX, 0f, 25f, Path.Direction.CW) }
            moonPath.op(shadowPath, Path.Op.DIFFERENCE)
        }
        celestialPaint.shader = moonBodyShader
        canvas.drawPath(moonPath, celestialPaint)

        // 6. Craters inside the lit body
        celestialPaint.shader = null
        celestialPaint.color = Color.argb(18, 0, 0, 0)
        canvas.drawCircle(-11f, 4f, 3.5f, celestialPaint)
        canvas.drawCircle(-4f, -12f, 2.5f, celestialPaint)

        canvas.restore()
        canvas.restore()
    }

    private fun drawMiniWeatherIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, condCode: Int, paint: Paint) {
        val origStyle = paint.style
        val origColor = paint.color
        val origStrokeWidth = paint.strokeWidth
        
        val isDay = isDayTime()
        val timeMs = System.currentTimeMillis()
        val nowSec = timeMs / 1000L

        // (Cloud silhouette shape lives in the shared baseCloudPath field)

        // Dynamic twilight detection to automatically transition clear conditions into Sunrise/Sunset cases
        val nearSunrise = abs(nowSec - weatherState.sunriseEpoch) <= 1800L
        val nearSunset = abs(nowSec - weatherState.sunsetEpoch) <= 1800L
        
        var code = condCode
        if (nearSunrise && (condCode == 800 || condCode == 801)) {
            code = 1001 // Custom Sunrise code
        } else if (nearSunset && (condCode == 800 || condCode == 801)) {
            code = 1002 // Custom Sunset code
        }

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(size / 100f, size / 100f)

        val localPaint = iconPaint // shared, fully reset before every use

        fun resetLocal(p: Paint) {
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.alpha = 255
            p.strokeWidth = 1f
            p.strokeCap = Paint.Cap.BUTT
            p.pathEffect = null
        }

        when (code) {
            1001 -> { // SUNRISE
                val bob = 4f * sin(timeMs / 1100.0).toFloat()
                
                // Horizon line separating sea/sky
                resetLocal(localPaint)
                localPaint.style = Paint.Style.STROKE
                localPaint.strokeWidth = 3f
                localPaint.color = Color.rgb(148, 163, 184)
                localPaint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(-42f, 18f, 42f, 18f, localPaint)

                // Sun Core rising clipped above horizon
                canvas.save()
                canvas.clipRect(-50f, -50f, 50f, 18f)
                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.shader = RadialGradient(0f, 18f + bob, 18f,
                    intArrayOf(Color.rgb(254, 240, 138), Color.rgb(249, 115, 22), Color.rgb(234, 88, 12)),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(0f, 18f + bob, 18f, localPaint)

                // Flaring Sunrise Ray Spokes inside clipping window
                val rayPulse = 1f + 0.12f * sin(timeMs / 550.0).toFloat()
                val rayAlpha = (180 + 75 * sin(timeMs / 550.0)).toInt().coerceIn(0, 255)
                resetLocal(localPaint)
                localPaint.style = Paint.Style.STROKE
                localPaint.strokeWidth = 4f
                localPaint.strokeCap = Paint.Cap.ROUND
                localPaint.color = Color.rgb(245, 158, 11)
                localPaint.alpha = rayAlpha

                val rayAngles = floatArrayOf(-150f, -120f, -90f, -60f, -30f)
                for (a in rayAngles) {
                    canvas.save()
                    canvas.rotate(a, 0f, 18f + bob)
                    canvas.drawLine(0f, 18f + bob + 22f, 0f, 18f + bob + 22f + 6f * rayPulse, localPaint)
                    canvas.restore()
                }
                canvas.restore()

                // Water reflection ripple lines below horizon
                val shimmer = sin(timeMs / 800.0).toFloat()
                resetLocal(localPaint)
                localPaint.style = Paint.Style.STROKE
                localPaint.strokeWidth = 3f
                localPaint.strokeCap = Paint.Cap.ROUND
                localPaint.shader = LinearGradient(-20f, 0f, 20f, 0f,
                    Color.rgb(251, 191, 36), Color.rgb(244, 63, 94), Shader.TileMode.CLAMP
                )

                localPaint.alpha = (160 + 90 * sin(timeMs / 800.0 + 1.0)).toInt().coerceIn(0, 255)
                val w1 = 18f + 5f * shimmer
                canvas.drawLine(-w1, 23f, w1, 23f, localPaint)

                localPaint.alpha = (120 + 80 * sin(timeMs / 800.0 + 2.0)).toInt().coerceIn(0, 255)
                val w2 = 12f - 3f * shimmer
                canvas.drawLine(-w2, 29f, w2, 29f, localPaint)

                localPaint.alpha = (80 + 60 * sin(timeMs / 800.0 + 3.0)).toInt().coerceIn(0, 255)
                val w3 = 7f + 2f * shimmer
                canvas.drawLine(-w3, 35f, w3, 35f, localPaint)
            }
            1002 -> { // SUNSET
                val bob = 4f * sin(timeMs / 1300.0).toFloat()

                // Dark evening horizon line
                resetLocal(localPaint)
                localPaint.style = Paint.Style.STROKE
                localPaint.strokeWidth = 3f
                localPaint.color = Color.rgb(100, 116, 139)
                localPaint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(-42f, 18f, 42f, 18f, localPaint)

                // Deep crimson sun Core sinking below horizon
                canvas.save()
                canvas.clipRect(-50f, -50f, 50f, 18f)
                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.shader = RadialGradient(0f, 18f + bob, 18f,
                    intArrayOf(Color.rgb(236, 72, 153), Color.rgb(239, 68, 68), Color.rgb(185, 28, 28)),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(0f, 18f + bob, 18f, localPaint)

                // Sunset fading rays
                val rayAlpha = (140 + 70 * sin(timeMs / 950.0)).toInt().coerceIn(0, 255)
                resetLocal(localPaint)
                localPaint.style = Paint.Style.STROKE
                localPaint.strokeWidth = 3.5f
                localPaint.strokeCap = Paint.Cap.ROUND
                localPaint.color = Color.rgb(220, 38, 38)
                localPaint.alpha = rayAlpha

                val rayAngles = floatArrayOf(-150f, -120f, -90f, -60f, -30f)
                for (a in rayAngles) {
                    canvas.save()
                    canvas.rotate(a, 0f, 18f + bob)
                    canvas.drawLine(0f, 18f + bob + 22f, 0f, 18f + bob + 28f, localPaint)
                    canvas.restore()
                }
                canvas.restore()

                // Crimson evening water reflection ripples
                resetLocal(localPaint)
                localPaint.style = Paint.Style.STROKE
                localPaint.strokeWidth = 3f
                localPaint.strokeCap = Paint.Cap.ROUND
                localPaint.shader = LinearGradient(-20f, 0f, 20f, 0f,
                    Color.rgb(219, 39, 119), Color.rgb(239, 68, 68), Shader.TileMode.CLAMP
                )

                localPaint.alpha = (170 + 80 * sin(timeMs / 1000.0)).toInt().coerceIn(0, 255)
                val w1 = 20f + 4f * sin(timeMs / 1000.0).toFloat()
                canvas.drawLine(-w1, 23f, w1, 23f, localPaint)

                localPaint.alpha = (110 + 60 * sin(timeMs / 1000.0 + 1.5)).toInt().coerceIn(0, 255)
                val w2 = 14f - 3f * sin(timeMs / 1000.0).toFloat()
                canvas.drawLine(-w2, 29f, w2, 29f, localPaint)
            }
            800 -> { // CLEAR SKY (Day Sun / Night Moon)
                if (isDay) {
                    val spin = (timeMs % 24000L) / 24000f * 360f
                    val scaleG = 1f + 0.04f * sin(timeMs / 600.0).toFloat()

                    // Glowing golden sun core with dynamic radius
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.shader = RadialGradient(0f, 0f, 22f * scaleG,
                        intArrayOf(Color.rgb(253, 224, 71), Color.rgb(249, 115, 22), Color.rgb(234, 88, 12)),
                        null, Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(0f, 0f, 22f * scaleG, localPaint)

                    // Rotating pill spokes
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.STROKE
                    localPaint.strokeWidth = 5f
                    localPaint.strokeCap = Paint.Cap.ROUND
                    localPaint.color = Color.rgb(249, 115, 22)

                    for (i in 0 until 8) {
                        canvas.save()
                        canvas.rotate(i * 45f + spin)
                        canvas.drawLine(0f, 29f * scaleG, 0f, 37f * scaleG, localPaint)
                        canvas.restore()
                    }
                } else {
                    val rock = 5f * sin(timeMs / 1500.0).toFloat()
                    
                    // Swaying gold crescent moon core and sparkling background stars
                    canvas.save()
                    canvas.rotate(rock)

                    val outerMoon = Path().apply { addCircle(-4f, 0f, 24f, Path.Direction.CW) }
                    val innerMoon = Path().apply { addCircle(4f, -4f, 22f, Path.Direction.CW) }
                    outerMoon.op(innerMoon, Path.Op.DIFFERENCE)

                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.shader = LinearGradient(-15f, -15f, 15f, 15f,
                        Color.rgb(254, 240, 138), Color.rgb(251, 191, 36), Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(outerMoon, localPaint)

                    // Moon craters
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.color = Color.argb(18, 0, 0, 0)
                    canvas.drawCircle(-11f, 4f, 3.5f, localPaint)
                    canvas.drawCircle(-4f, -12f, 2.5f, localPaint)
                    canvas.restore()

                    // Twinkling multi-pointed stars
                    val s1Alpha = (140 + 115 * sin(timeMs / 500.0)).toInt().coerceIn(0, 255)
                    val s2Alpha = (140 + 115 * sin(timeMs / 500.0 + Math.PI)).toInt().coerceIn(0, 255)

                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.STROKE
                    localPaint.strokeWidth = 2.5f
                    localPaint.strokeCap = Paint.Cap.ROUND
                    localPaint.color = Color.rgb(241, 245, 249)

                    // Star 1
                    localPaint.alpha = s1Alpha
                    canvas.drawLine(15f - 4f, -14f, 15f + 4f, -14f, localPaint)
                    canvas.drawLine(15f, -14f - 4f, 15f, -14f + 4f, localPaint)

                    // Star 2
                    localPaint.alpha = s2Alpha
                    canvas.drawLine(22f - 3f, 8f, 22f + 3f, 8f, localPaint)
                    canvas.drawLine(22f, 8f - 3f, 22f, 8f + 3f, localPaint)
                }
            }
            in 801..802 -> { // PARTLY CLOUDY (Fair Day / Night)
                if (isDay) {
                    // Sun peaking behind the cloud
                    canvas.save()
                    canvas.translate(-15f, -15f)
                    val spin = (timeMs % 24000L) / 24000f * 360f
                    canvas.rotate(spin)

                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.shader = RadialGradient(0f, 0f, 15f,
                        intArrayOf(Color.rgb(253, 224, 71), Color.rgb(249, 115, 22)),
                        null, Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(0f, 0f, 15f, localPaint)

                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.STROKE
                    localPaint.strokeWidth = 3f
                    localPaint.strokeCap = Paint.Cap.ROUND
                    localPaint.color = Color.rgb(249, 115, 22)
                    for (i in 0 until 8) {
                        canvas.save()
                        canvas.rotate(i * 45f)
                        canvas.drawLine(0f, 19f, 0f, 24f, localPaint)
                        canvas.restore()
                    }
                    canvas.restore()

                    // Foreground breathing Cloud
                    val breathe = 1f + 0.02f * sin(timeMs / 1000.0).toFloat()
                    canvas.save()
                    canvas.translate(4f, 6f)
                    canvas.scale(1f, breathe)

                    // Depth Shadow
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.color = Color.argb(35, 0, 0, 0)
                    canvas.drawPath(baseCloudPath, localPaint)

                    // Main Cloud
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.shader = LinearGradient(-20f, -15f, 20f, 15f,
                        Color.WHITE, Color.rgb(226, 232, 240), Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(baseCloudPath, localPaint)
                    canvas.restore()
                } else {
                    // Moon peaking behind the cloud
                    canvas.save()
                    canvas.translate(-12f, -12f)
                    canvas.scale(0.85f, 0.85f)
                    val rock = 5f * sin(timeMs / 1600.0).toFloat()
                    canvas.rotate(rock)

                    val outerMoon = Path().apply { addCircle(-4f, 0f, 24f, Path.Direction.CW) }
                    val innerMoon = Path().apply { addCircle(4f, -4f, 22f, Path.Direction.CW) }
                    outerMoon.op(innerMoon, Path.Op.DIFFERENCE)

                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.shader = LinearGradient(-15f, -15f, 15f, 15f,
                        Color.rgb(254, 240, 138), Color.rgb(251, 191, 36), Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(outerMoon, localPaint)
                    canvas.restore()

                    // Foreground Cloud
                    canvas.save()
                    canvas.translate(4f, 6f)

                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.color = Color.argb(40, 0, 0, 0)
                    canvas.drawPath(baseCloudPath, localPaint)

                    val nodeColors = intArrayOf(Color.rgb(241, 245, 249), Color.rgb(203, 213, 225))
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.shader = LinearGradient(-20f, -15f, 20f, 15f,
                        nodeColors, null, Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(baseCloudPath, localPaint)
                    canvas.restore()
                }
            }
            in 803..804 -> { // OVERCAST CLOUDS (Double cloud layer parallax)
                val bgDrift = 4f * sin(timeMs / 2500.0).toFloat()
                
                // Background dark slate cloud
                canvas.save()
                canvas.translate(-12f + bgDrift, -6f)
                canvas.scale(0.82f, 0.82f)
                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.shader = LinearGradient(-20f, -15f, 20f, 15f,
                    Color.rgb(148, 163, 184), Color.rgb(71, 85, 105), Shader.TileMode.CLAMP
                )
                canvas.drawPath(baseCloudPath, localPaint)
                canvas.restore()

                // Foreground light cloud shifts in opposite direction
                val fgDrift = -5f * sin(timeMs / 1800.0 + 0.8).toFloat()
                canvas.save()
                canvas.translate(8f + fgDrift, 10f)

                // Drop shadow
                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.color = Color.argb(35, 0, 0, 0)
                canvas.drawPath(baseCloudPath, localPaint)

                // Cloud body
                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.shader = LinearGradient(-20f, -15f, 20f, 15f,
                    Color.WHITE, Color.rgb(203, 213, 225), Shader.TileMode.CLAMP
                )
                canvas.drawPath(baseCloudPath, localPaint)
                canvas.restore()
            }
            in 300..531 -> { // RAIN SHOWERS
                // Cool raincloud
                canvas.save()
                canvas.translate(0f, -10f)
                
                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.color = Color.argb(45, 0, 0, 0)
                canvas.drawPath(baseCloudPath, localPaint)

                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.shader = LinearGradient(-15f, -15f, 15f, 15f,
                    Color.rgb(100, 116, 139), Color.rgb(51, 65, 85), Shader.TileMode.CLAMP
                )
                canvas.drawPath(baseCloudPath, localPaint)
                canvas.restore()

                // 3 cascading sliding droplets pointing downwards
                resetLocal(localPaint)
                localPaint.style = Paint.Style.STROKE
                localPaint.strokeWidth = 3.8f
                localPaint.strokeCap = Paint.Cap.ROUND
                localPaint.color = Color.rgb(96, 165, 250)

                val rainCols = floatArrayOf(-13f, 0f, 13f)
                for (i in 0 until 3) {
                    val prog = ((timeMs + i * 800L) % 2400L) / 2400f
                    val alphaF = sin(prog * Math.PI).toFloat().coerceIn(0f, 1f)
                    localPaint.alpha = (alphaF * 255).toInt()

                    val y1 = 4f + prog * 28f
                    val y2 = y1 + 9f
                    val slant = prog * -3f
                    canvas.drawLine(rainCols[i] + slant, y1, rainCols[i] - 1.5f + slant, y2, localPaint)
                }
            }
            in 200..232 -> { // THUNDERSTORMS
                // Deep storm cloud
                canvas.save()
                canvas.translate(0f, -10f)

                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.color = Color.argb(45, 0, 0, 0)
                canvas.drawPath(baseCloudPath, localPaint)

                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.shader = LinearGradient(-15f, -15f, 15f, 15f,
                    Color.rgb(71, 85, 105), Color.rgb(30, 41, 59), Shader.TileMode.CLAMP
                )
                canvas.drawPath(baseCloudPath, localPaint)
                canvas.restore()

                // Cascading raindrops
                resetLocal(localPaint)
                localPaint.style = Paint.Style.STROKE
                localPaint.strokeWidth = 3.5f
                localPaint.strokeCap = Paint.Cap.ROUND
                localPaint.color = Color.rgb(96, 165, 250)
                val rainCols = floatArrayOf(-12f, 12f)
                for (i in 0 until 2) {
                    val prog = ((timeMs + i * 1200L) % 2400L) / 2400f
                    val alphaF = sin(prog * Math.PI).toFloat().coerceIn(0f, 1f)
                    localPaint.alpha = (alphaF * 180).toInt()
                    val y1 = 4f + prog * 26f
                    val y2 = y1 + 8f
                    canvas.drawLine(rainCols[i] + prog * -2.5f, y1, rainCols[i] - 1.2f + prog * -2.5f, y2, localPaint)
                }

                // Electric double-pass flasher lightning bolt
                val cycle = timeMs % 4000L
                val isFlicker = (cycle in 0L..90L || cycle in 180L..310L || cycle in 700L..820L || cycle in 1200L..1280L)
                if (isFlicker) {
                    val boltPath = Path().apply {
                        moveTo(2f, 4f)
                        lineTo(-8f, 18f)
                        lineTo(-1f, 18f)
                        lineTo(-10f, 32f)
                        lineTo(4f, 14f)
                        lineTo(-3f, 14f)
                        close()
                    }

                    // 1. Glow envelope
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.color = Color.rgb(14, 165, 233) // Cyber lightning sky blue glow
                    localPaint.alpha = 130
                    canvas.drawPath(boltPath, localPaint)

                    // 2. Energetic yellow Core outline
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.color = Color.rgb(253, 224, 71)
                    canvas.save()
                    canvas.scale(0.85f, 0.85f)
                    canvas.drawPath(boltPath, localPaint)
                    canvas.restore()
                }
            }
            in 600..622 -> { // SNOW
                // Crisp winter cloud
                canvas.save()
                canvas.translate(0f, -10f)
                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.shader = LinearGradient(-20f, -15f, 20f, 15f,
                    Color.rgb(241, 245, 249), Color.rgb(191, 219, 254), Shader.TileMode.CLAMP
                )
                canvas.drawPath(baseCloudPath, localPaint)
                canvas.restore()

                // 3 cascading tumbling snow crystals
                val flakeCols = floatArrayOf(-13f, 0f, 13f)
                for (i in 0 until 3) {
                    val prog = ((timeMs + i * 1100L) % 3300L) / 3300f
                    val sAlpha = sin(prog * Math.PI).toFloat().coerceIn(0f, 1f)
                    val sY = 4f + prog * 30f
                    val sX = flakeCols[i] + 6f * sin(prog * 3f * Math.PI.toFloat() + i)

                    canvas.save()
                    canvas.translate(sX, sY)
                    val spin = (timeMs % 8000L) / 8000f * 360f * (if (i % 2 == 0) 1f else -1f)
                    canvas.rotate(spin)

                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.STROKE
                    localPaint.strokeWidth = 2.2f
                    localPaint.strokeCap = Paint.Cap.ROUND
                    localPaint.color = Color.rgb(248, 250, 252)
                    localPaint.alpha = (sAlpha * 255).toInt()

                    // Draw 6-pointed snowflake asterisks
                    canvas.drawLine(-4.50f, 0f, 4.50f, 0f, localPaint)
                    canvas.save()
                    canvas.rotate(60f)
                    canvas.drawLine(-4.50f, 0f, 4.50f, 0f, localPaint)
                    canvas.rotate(60f)
                    canvas.drawLine(-4.50f, 0f, 4.50f, 0f, localPaint)
                    canvas.restore()
                    canvas.restore()
                }
            }
            in 701..781 -> { // WINDY / METEOROLOGY ATMO (Fog / Mist)
                val isWindy = weatherState.windSpeed > 15f || code == 771 || code == 781
                if (isWindy) {
                    // High-quality wind sweeping breeze patterns
                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.STROKE
                    localPaint.strokeWidth = 4.5f
                    localPaint.strokeCap = Paint.Cap.ROUND
                    localPaint.color = Color.argb(105, 203, 213, 225)

                    val p1 = Path().apply {
                        moveTo(-38f, -14f)
                        lineTo(12f, -14f)
                        cubicTo(22f, -14f, 22f, -23f, 12f, -23f)
                        cubicTo(6f, -23f, 8f, -16f, 12f, -16f)
                    }
                    val p2 = Path().apply {
                        moveTo(-28f, 5f)
                        lineTo(18f, 5f)
                        cubicTo(28f, 5f, 28f, 14f, 18f, 14f)
                        cubicTo(12f, 14f, 14f, 7f, 18f, 7f)
                    }
                    val p3 = Path().apply {
                        moveTo(-33f, 22f)
                        lineTo(8f, 22f)
                        cubicTo(18f, 22f, 18f, 14f, 8f, 14f)
                        cubicTo(2f, 14f, 4f, 20f, 8f, 20f)
                    }

                    canvas.drawPath(p1, localPaint)
                    canvas.drawPath(p2, localPaint)
                    canvas.drawPath(p3, localPaint)

                    // Moving highlight capsule dash effect sliding along wind curls
                    val travel = (timeMs % 1600L) / 1600f
                    localPaint.color = Color.rgb(241, 245, 249)
                    localPaint.alpha = 245
                    localPaint.pathEffect = DashPathEffect(floatArrayOf(12f, 45f), -travel * 57f)

                    canvas.drawPath(p1, localPaint)
                    canvas.drawPath(p2, localPaint)
                    canvas.drawPath(p3, localPaint)
                } else {
                    // 3 shifting Fog/Mist bands layers
                    val f1 = 6f * sin(timeMs / 1400.0).toFloat()
                    val f2 = 9f * sin(timeMs / 1800.0 + 1f).toFloat()
                    val f3 = 5f * sin(timeMs / 1200.0 + 2f).toFloat()

                    resetLocal(localPaint)
                    localPaint.style = Paint.Style.FILL
                    localPaint.shader = LinearGradient(-30f, 0f, 30f, 0f,
                        Color.argb(90, 241, 245, 249), Color.argb(165, 148, 163, 184), Shader.TileMode.CLAMP
                    )

                    canvas.drawRoundRect(RectF(-33f + f1, -17f, 33f + f1, -9f), 4f, 4f, localPaint)
                    canvas.drawRoundRect(RectF(-38f + f2, 1f, 38f + f2, 9f), 4f, 4f, localPaint)
                    canvas.drawRoundRect(RectF(-29f + f3, 19f, 29f + f3, 27f), 4f, 4f, localPaint)
                }
            }
            else -> { // Nice placeholder cloud fallback
                canvas.save()
                canvas.translate(0f, -4f)
                resetLocal(localPaint)
                localPaint.style = Paint.Style.FILL
                localPaint.shader = LinearGradient(-20f, -15f, 20f, 15f,
                    Color.WHITE, Color.rgb(203, 213, 225), Shader.TileMode.CLAMP
                )
                canvas.drawPath(baseCloudPath, localPaint)
                canvas.restore()
            }
        }

        canvas.restore()
        
        paint.style = origStyle
        paint.color = origColor
        paint.strokeWidth = origStrokeWidth
    }

    private fun drawMiniDroplet(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val origStyle = paint.style
        val origColor = paint.color
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(size / 100f, size / 100f)
        
        // Dynamic water tension breathing animation
        val timeMs = System.currentTimeMillis()
        val pulse = 1.0f + 0.05f * sin(timeMs / 400.0).toFloat()
        canvas.scale(pulse, pulse)

        canvas.drawPath(dropletPath, dropletPaint)
        
        canvas.restore()
        paint.style = origStyle
        paint.color = origColor
    }

    private fun drawMiniWind(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val origStyle = paint.style
        val origColor = paint.color
        val origStroke = paint.strokeWidth
        
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(size / 100f, size / 100f)
        
        val timeMs = System.currentTimeMillis()
        val travel = (timeMs % 1500L) / 1500f

        // DashPathEffect phase is immutable, so this small alloc is unavoidable
        windFlowPaint.pathEffect = DashPathEffect(floatArrayOf(15f, 40f), -travel * 55f)

        canvas.drawPath(windPath, windTrackPaint)
        canvas.drawPath(windPath, windFlowPaint)
        
        canvas.restore()
        paint.style = origStyle
        paint.color = origColor
        paint.strokeWidth = origStroke
    }

    // Secondary state structural data models designed for particles
    private class RainParticle {
        var x = 0f
        var y = 0f
        var vy = 0f
        var vx = 0f
        var alpha = 0f
        var length = 0f
        var layer = 0 // 0 = far (slow, dim), 1 = near (fast, bright)
    }

    private class SplashParticle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var alpha = 0f
        var maxLife = 0f
        var life = 0f
    }

    private class SnowParticle {
        var x = 0f
        var y = 0f
        var vy = 0f
        var size = 0f
        var wWiggleSpeed = 0f
        var wOffset = 0f
    }

    private class Cloud {
        var x = 0f
        var y = 0f
        var speed = 0f
        var width = 0f
        var height = 0f
        var scale = 1f
    }

    private class FogBand {
        var y = 0f
        var xOffset = 0f
        var speed = 0f
        var height = 0f
    }

    private class Star {
        var x = 0f
        var y = 0f
        var baseAlpha = 0f
        var speed = 0f
        var offset = 0f
        var size = 1.2f
        var tintR = 255
        var tintG = 255
        var tintB = 255
    }

    private class ShimmerMote {
        var x = 0f
        var y = 0f
        var speed = 0f
        var size = 0f
        var alpha = 0f
    }

    private class Firefly {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var pulsePhase = 0f
    }

    private class AmbientParticle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var size = 0f
        var alpha = 0f
    }

    private class ShootingStar {
        var startX = 0f
        var startY = 0f
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var active = false
        var progress = 0f
        var maxLife = 1.0f
    }

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val result = super.onTouchEvent(event)
        
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
            }
            android.view.MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val duration = System.currentTimeMillis() - downTime
                
                // If soft click/tap in bottom bar area below clockBottomY
                if (duration < 350L && dist < 12f * resources.displayMetrics.density) {
                    if (downY > clockBottomY && clockBottomY > 0f) {
                        onWeatherBarClicked?.invoke()
                        return true
                    }
                }
            }
        }
        return result
    }
}
