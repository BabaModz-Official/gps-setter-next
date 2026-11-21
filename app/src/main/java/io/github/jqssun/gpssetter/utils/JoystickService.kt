package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import io.github.controlwear.virtual.joystick.android.JoystickView
import io.github.jqssun.gpssetter.R
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.sin

class JoystickService : Service() {

    private var wm: WindowManager? = null
    private var mJoystickContainerView: View? = null
    private var mJoystickView: JoystickView? = null
    private var mJoystickLayoutParams: WindowManager.LayoutParams? = null

    private var lat: Double = PrefManager.getLat
    private var lon: Double = PrefManager.getLng
    private var speedMultiplier: Double = 1.0 // 1.0 = Walk, 3.0 = Run, 12.0 = Drive

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickerJob: Job? = null
    @Volatile private var currentAngle: Int = 0
    @Volatile private var currentStrength: Int = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mJoystickContainerView = inflater.inflate(R.layout.joystick, null)

        mJoystickView = mJoystickContainerView?.findViewById(R.id.joystickView_right)
        val header = mJoystickContainerView?.findViewById<View>(R.id.joystick_header)
        val btnClose = mJoystickContainerView?.findViewById<ImageView>(R.id.btn_close_joystick)

        val btnWalk = mJoystickContainerView?.findViewById<TextView>(R.id.btn_speed_walk)
        val btnRun = mJoystickContainerView?.findViewById<TextView>(R.id.btn_speed_run)
        val btnDrive = mJoystickContainerView?.findViewById<TextView>(R.id.btn_speed_drive)

        val activeColor = Color.parseColor("#3D82F6")
        val inactiveColor = Color.parseColor("#AAAAAA")

        fun selectSpeed(mult: Double, selectedView: TextView?) {
            speedMultiplier = mult
            btnWalk?.setTextColor(if (selectedView == btnWalk) activeColor else inactiveColor)
            btnRun?.setTextColor(if (selectedView == btnRun) activeColor else inactiveColor)
            btnDrive?.setTextColor(if (selectedView == btnDrive) activeColor else inactiveColor)
        }

        btnWalk?.setOnClickListener { selectSpeed(1.0, btnWalk) }
        btnRun?.setOnClickListener { selectSpeed(3.0, btnRun) }
        btnDrive?.setOnClickListener { selectSpeed(12.0, btnDrive) }

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

        // Joystick Movement listener with continuous tick on hold
        mJoystickView?.setOnMoveListener { angle, strength, _ ->
            currentAngle = angle
            currentStrength = strength
            if (strength > 0) {
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
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive && currentStrength > 0) {
                stepLocation(currentAngle, currentStrength)
                delay(100)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        currentStrength = 0
        // Freeze at current coordinate and clear motion (speed 0, bearing -1)
        PrefManager.update(start = true, la = lat, ln = lon)
    }

    private fun stepLocation(angle: Int, strength: Int) {
        val radians = Math.toRadians(angle.toDouble())
        try {
            val steps = (strength / 35.0) * speedMultiplier
            val factorY: Double = sin(radians) / 100000.0 * steps
            val currentLat = PrefManager.getLat
            val cosLat = cos(Math.toRadians(currentLat)).coerceAtLeast(0.01)
            val factorX: Double = cos(radians) / 100000.0 * steps / cosLat

            lon = PrefManager.getLng + factorX
            lat = currentLat + factorY

            val baseSpeedMps = when (speedMultiplier) {
                1.0 -> 1.4f   // ~5 km/h walking
                3.0 -> 4.2f   // ~15 km/h running
                12.0 -> 16.7f // ~60 km/h driving
                else -> (speedMultiplier * 1.4).toFloat()
            }
            val curSpeed = baseSpeedMps * (strength / 100f).coerceIn(0.1f, 1.0f)
            // Convert joystick Cartesian angle to geographic Compass bearing:
            // 90 deg (Up) -> 0 deg (North), 0 deg (Right) -> 90 deg (East), 270 deg (Down) -> 180 deg (South)
            val bearing = ((90 - angle + 360) % 360).toFloat()

            PrefManager.updateMotion(
                la = lat,
                ln = lon,
                altitude = PrefManager.altitude,
                speed = curSpeed,
                bearing = bearing,
                along = 0f
            )
        } catch (e: Exception) {
            e.printStackTrace()
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
