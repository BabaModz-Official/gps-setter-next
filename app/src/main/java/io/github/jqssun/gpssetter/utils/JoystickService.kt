package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.route.RoutePlaybackService
import io.github.jqssun.gpssetter.ui.ThumbstickView
import io.github.jqssun.gpssetter.xposed.GeoOffset
import kotlinx.coroutines.*

class JoystickService : Service() {

    private var wm: WindowManager? = null
    private var mJoystickContainerView: View? = null
    private var mJoystickLayoutParams: WindowManager.LayoutParams? = null

    private var lastPoint: Pair<Double, Double>? = null
    private var speedMps = 1.4f

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickerJob: Job? = null
    private var cooldownTickerJob: Job? = null
    private var bearing = 0f
    private var strength = 0f
    private var walkStep = 0L

    companion object {
        const val ACTION_STOP = "io.github.jqssun.gpssetter.joystick.STOP"
        private const val NOTIF_ID = 789
        private const val CHANNEL_ID = "floating_joystick_channel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PrefManager.isJoystickEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Joystick",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating joystick overlay over apps"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, JoystickService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 102, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_joystick)
            .setContentTitle("GPS Setter Next")
            .setContentText("Floating Joystick active")
            .setOngoing(true)
            .addAction(R.drawable.ic_close, "Stop", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        startInForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mJoystickContainerView = inflater.inflate(R.layout.joystick, null)

        val thumbstick = mJoystickContainerView?.findViewById<ThumbstickView>(R.id.joystickView_right)
        val header = mJoystickContainerView?.findViewById<View>(R.id.joystick_header)
        val btnClose = mJoystickContainerView?.findViewById<ImageView>(R.id.btn_close_joystick)
        val btnMinimize = mJoystickContainerView?.findViewById<ImageView>(R.id.btn_minimize_joystick)
        val btnOpacity = mJoystickContainerView?.findViewById<TextView>(R.id.btn_joystick_opacity)
        val joystickBody = mJoystickContainerView?.findViewById<View>(R.id.joystick_body)
        val tvCooldown = mJoystickContainerView?.findViewById<TextView>(R.id.tv_joystick_cooldown)

        // Opacity setup
        val opacities = floatArrayOf(1.0f, 0.75f, 0.45f)
        val opacityLabels = arrayOf("100%", "75%", "45%")
        var opacityIndex = opacities.indexOfFirst { kotlin.math.abs(it - PrefManager.joystickAlpha) < 0.05f }
        if (opacityIndex < 0) opacityIndex = 0

        fun applyOpacity(idx: Int) {
            val alpha = opacities[idx]
            PrefManager.joystickAlpha = alpha
            mJoystickContainerView?.alpha = alpha
            btnOpacity?.text = opacityLabels[idx]
        }
        applyOpacity(opacityIndex)

        btnOpacity?.setOnClickListener {
            opacityIndex = (opacityIndex + 1) % opacities.size
            applyOpacity(opacityIndex)
        }

        // Collapse / Expand setup
        fun applyCollapsed(collapsed: Boolean) {
            PrefManager.isJoystickCollapsed = collapsed
            joystickBody?.visibility = if (collapsed) View.GONE else View.VISIBLE
            btnMinimize?.rotation = if (collapsed) 180f else 0f
        }
        applyCollapsed(PrefManager.isJoystickCollapsed)

        btnMinimize?.setOnClickListener {
            val nowCollapsed = joystickBody?.visibility == View.VISIBLE
            applyCollapsed(nowCollapsed)
        }

        // Cooldown live badge
        val safeColor = Color.parseColor("#22C55E")
        val cdColor = Color.parseColor("#F59E0B")
        cooldownTickerJob = serviceScope.launch {
            while (isActive) {
                val remaining = CooldownHelper.getRemainingCooldownMillis()
                if (remaining <= 0L) {
                    tvCooldown?.text = "Safe"
                    tvCooldown?.setTextColor(safeColor)
                } else {
                    tvCooldown?.text = "⏳ " + CooldownHelper.formatRemaining(remaining)
                    tvCooldown?.setTextColor(cdColor)
                }
                delay(1000)
            }
        }

        val speeds = intArrayOf(1, 5, 10, 15, 20, 30, 50)
        var speedIndex = PrefManager.stickSpeedIndex.coerceIn(0, speeds.lastIndex)

        val tvSpeed = mJoystickContainerView?.findViewById<TextView>(R.id.tv_joystick_speed)
        val btnWalk = mJoystickContainerView?.findViewById<TextView>(R.id.btn_speed_walk)
        val btnRun = mJoystickContainerView?.findViewById<TextView>(R.id.btn_speed_run)
        val btnDrive = mJoystickContainerView?.findViewById<TextView>(R.id.btn_speed_drive)

        val activeColor = Color.parseColor("#3D82F6")
        val inactiveColor = Color.parseColor("#AAAAAA")

        fun updateSpeedDisplay() {
            val spd = speeds[speedIndex]
            speedMps = spd.toFloat()
            PrefManager.stickSpeedIndex = speedIndex
            tvSpeed?.text = "${spd} m/s"
            btnWalk?.setTextColor(if (spd <= 1) activeColor else inactiveColor)
            btnRun?.setTextColor(if (spd in 2..5) activeColor else inactiveColor)
            btnDrive?.setTextColor(if (spd >= 15) activeColor else inactiveColor)
        }

        updateSpeedDisplay()

        tvSpeed?.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            updateSpeedDisplay()
        }

        btnWalk?.setOnClickListener {
            speedIndex = 0 // 1 m/s
            updateSpeedDisplay()
        }
        btnRun?.setOnClickListener {
            speedIndex = 1 // 5 m/s
            updateSpeedDisplay()
        }
        btnDrive?.setOnClickListener {
            speedIndex = 4 // 20 m/s
            updateSpeedDisplay()
        }

        btnClose?.setOnClickListener {
            PrefManager.isJoystickEnabled = false
            stopSelf()
        }

        // Draggable floating window overlay
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header?.setOnTouchListener { _, event ->
            val params = mJoystickLayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    wm?.updateViewLayout(mJoystickContainerView, params)
                    true
                }
                else -> false
            }
        }

        thumbstick?.onMove = { direction, deflection ->
            bearing = direction
            strength = deflection
            if (strength > 0f) {
                if (tickerJob?.isActive != true) {
                    startTicker()
                }
            } else {
                stopTicker()
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        mJoystickLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        wm?.addView(mJoystickContainerView, mJoystickLayoutParams)
    }

    private fun startTicker() {
        tickerJob = serviceScope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            while (isActive && strength > 0f && !RoutePlaybackService.isPlaying) {
                delay(100)
                if (RoutePlaybackService.isPlaying) break
                val now = SystemClock.elapsedRealtime()
                val elapsedSeconds = (now - lastTick).coerceIn(0L, 250L) / 1000.0
                lastTick = now

                walkStep++
                val naturalMultiplier = if (PrefManager.isNaturalMovementEnabled) {
                    // Realistic human stride fluctuation (±6%)
                    1.0 + 0.06 * kotlin.math.sin(walkStep * 0.4)
                } else {
                    1.0
                }
                val speed = (speedMps * strength * naturalMultiplier).toFloat()

                val point = GeoOffset.destination(PrefManager.getLat, PrefManager.getLng, bearing, speed * elapsedSeconds)
                lastPoint = point
                PrefManager.updateMotion(point.first, point.second, PrefManager.altitude, speed, bearing, 0f)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        walkStep = 0L
        strength = 0f
        val point = lastPoint
        lastPoint = null
        if (point != null && PrefManager.isStarted && !RoutePlaybackService.isPlaying &&
            point.first == PrefManager.getLat && point.second == PrefManager.getLng) {
            PrefManager.updateMotion(point.first, point.second, PrefManager.altitude, 0f, -1f, 0f)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTicker()
        cooldownTickerJob?.cancel()
        cooldownTickerJob = null
        serviceScope.cancel()
        mJoystickContainerView?.let {
            wm?.removeView(it)
            mJoystickContainerView = null
        }
    }
}
