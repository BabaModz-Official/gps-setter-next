package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import android.widget.ImageView
import android.widget.TextView
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
    private var bearing = 0f
    private var strength = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mJoystickContainerView = inflater.inflate(R.layout.joystick, null)

        val thumbstick = mJoystickContainerView?.findViewById<ThumbstickView>(R.id.joystickView_right)
        val header = mJoystickContainerView?.findViewById<View>(R.id.joystick_header)
        val btnClose = mJoystickContainerView?.findViewById<ImageView>(R.id.btn_close_joystick)

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
                val speed = speedMps * strength
                val point = GeoOffset.destination(PrefManager.getLat, PrefManager.getLng, bearing, speed * elapsedSeconds)
                lastPoint = point
                PrefManager.updateMotion(point.first, point.second, PrefManager.altitude, speed, bearing, 0f)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
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
        serviceScope.cancel()
        mJoystickContainerView?.let {
            wm?.removeView(it)
            mJoystickContainerView = null
        }
    }
}
