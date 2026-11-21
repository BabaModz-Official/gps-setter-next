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
    private var lastUpdateTime: Long = 0

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

        // Joystick Movement listener
        mJoystickView?.setOnMoveListener { angle, strength, _ ->
            if (strength <= 0) return@setOnMoveListener
            val now = System.currentTimeMillis()
            // Throttle to max 10 Hz to prevent disk/IPC thrashing
            if (now - lastUpdateTime < 100) return@setOnMoveListener
            lastUpdateTime = now

            val radians = Math.toRadians(angle.toDouble())
            try {
                val steps = (strength / 30.0) * speedMultiplier
                val factorY: Double = sin(radians) / 100000.0 * steps
                val currentLat = PrefManager.getLat
                val cosLat = cos(Math.toRadians(currentLat)).coerceAtLeast(0.01)
                val factorX: Double = cos(radians) / 100000.0 * steps / cosLat

                lon = PrefManager.getLng + factorX
                lat = currentLat + factorY
                updateLocation(lat, lon)
            } catch (e: Exception) {
                e.printStackTrace()
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mJoystickContainerView?.let {
            wm?.removeView(it)
            mJoystickContainerView = null
        }
    }

    private fun updateLocation(newLat: Double, newLon: Double) {
        PrefManager.update(start = PrefManager.isStarted, la = newLat, ln = newLon)
    }
}
