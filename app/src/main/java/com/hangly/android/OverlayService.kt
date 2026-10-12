package com.hangly.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that draws the hanging pendant over every app.
 *
 * Two overlay windows are used:
 *  - a full-width, touch-through window that draws the chain and pendant, and
 *  - a small invisible window that follows the pendant and catches touches, so you can
 *    grab and swing it while everything else on screen stays tappable.
 */
class OverlayService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "com.hangly.android.STOP"
        private const val CHANNEL = "hangly_overlay"
        private const val NOTIF_ID = 7
        private const val STEP = 1f / 120f

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var sensorManager: SensorManager
    private var accel: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val screenDisplay: Display by lazy {
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    }

    private var pendulum: PendulumView? = null
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var handleW = 0
    private var handleH = 0
    private val center = FloatArray(2)

    // Animation loop state
    private var frameScheduled = false
    private var lastNanos = 0L
    private var acc = 0f
    private var restFrames = 0
    private var paused = false

    // Gravity as felt by the hanging pendant, in m/s^2 along screen axes.
    private var rawRight = 0f
    private var rawDown = 9.81f
    private var wokeRight = 0f
    private var wokeDown = 9.81f

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "enabled") {
            handler.removeCallbacks(rebuild)
            handler.postDelayed(rebuild, 150)
        }
    }
    private val rebuild = Runnable { if (pendulum != null) buildOverlay() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) pauseAll() else resumeAll()
        }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val p = pendulum
            if (p == null || paused) {
                frameScheduled = false
                return
            }
            var dt = if (lastNanos == 0L) 1f / 60f else (frameTimeNanos - lastNanos) / 1e9f
            lastNanos = frameTimeNanos
            dt = min(dt, 0.05f)
            acc += dt
            var n = 0
            while (acc >= STEP && n < 8) {
                p.step(STEP)
                acc -= STEP
                n++
            }
            if (n == 8) acc = 0f
            p.invalidate()
            updateHandle(p)

            if (!p.dragging && p.motion() < 0.05f * resources.displayMetrics.density) restFrames++ else restFrames = 0
            if (restFrames > 90) {
                frameScheduled = false // settled: stop drawing until something disturbs it
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundNotification()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (pendulum == null) buildOverlay()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (pendulum != null) handler.post { buildOverlay() } // rotation: re-measure the screen
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        sensorManager.unregisterListener(this)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // already gone
        }
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hangly overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hangly is hanging around")
            .setContentText("Tap to change settings")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification), "Stop", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- overlay windows ----------

    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun buildOverlay() {
        removeOverlay()
        val d = resources.displayMetrics.density
        val screen = screenSize()

        val bob = PendantStore.load(this, (prefs.sizeDp * d).toInt())
        val chain = min(prefs.chainDp * d, screen.y * 0.6f)
        val pivotX = screen.x * (0.06f + 0.88f * prefs.pivot / 100f)

        val p = PendulumView(this, bob, pivotX, chain, d, prefs.style)
        val height = (chain + bob.height + 28 * d).toInt()

        val renderFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val rp = WindowManager.LayoutParams(
            screen.x, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            renderFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Android 12+ blocks touches that pass through overlays more opaque than 0.8.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        handleW = max(bob.width, (56 * d).toInt())
        handleH = max(bob.height, (56 * d).toInt())
        val hv = HandleView()
        val handleFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val hp = WindowManager.LayoutParams(
            handleW, handleH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            handleFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        try {
            wm.addView(p, rp)
            wm.addView(hv, hp)
        } catch (e: Exception) {
            // Permission revoked or window refused: shut down cleanly.
            try { wm.removeView(p) } catch (e: Exception) {}
            prefs.enabled = false
            stopSelf()
            return
        }
        pendulum = p
        handleView = hv
        handleParams = hp

        sensorManager.unregisterListener(this)
        if (prefs.tilt && !paused) {
            accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        pushGravity()
        p.bobCenter(center)
        hp.x = (center[0] - handleW / 2f).toInt()
        hp.y = (center[1] - handleH / 2f).toInt()
        try { wm.updateViewLayout(hv, hp) } catch (e: Exception) {}
        wake()
    }

    private fun removeOverlay() {
        choreographer.removeFrameCallback(frame)
        frameScheduled = false
        pendulum?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        handleView?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        pendulum = null
        handleView = null
        handleParams = null
    }

    private fun updateHandle(p: PendulumView) {
        val hv = handleView ?: return
        val hp = handleParams ?: return
        p.bobCenter(center)
        val nx = (center[0] - handleW / 2f).toInt()
        val ny = (center[1] - handleH / 2f).toInt()
        if (nx != hp.x || ny != hp.y) {
            hp.x = nx
            hp.y = ny
            try { wm.updateViewLayout(hv, hp) } catch (e: Exception) {}
        }
    }

    // ---------- animation control ----------

    private fun wake() {
        restFrames = 0
        if (!frameScheduled && pendulum != null && !paused) {
            frameScheduled = true
            lastNanos = 0L
            choreographer.postFrameCallback(frame)
        }
    }

    private fun pauseAll() {
        paused = true
        choreographer.removeFrameCallback(frame)
        frameScheduled = false
        sensorManager.unregisterListener(this)
    }

    private fun resumeAll() {
        paused = false
        if (prefs.tilt) {
            accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        wake()
    }

    // ---------- sensor ----------

    override fun onSensorChanged(e: SensorEvent) {
        val ax = e.values[0]
        val ay = e.values[1]
        // Rotate device axes into screen axes (sx right, sy up) for the current rotation.
        val sx: Float
        val sy: Float
        when (screenDisplay.rotation) {
            Surface.ROTATION_90 -> { sx = -ay; sy = ax }
            Surface.ROTATION_180 -> { sx = -ax; sy = -ay }
            Surface.ROTATION_270 -> { sx = ay; sy = -ax }
            else -> { sx = ax; sy = ay }
        }
        // The accelerometer measures the reaction force; a hanging charm feels the opposite.
        rawRight += (-sx - rawRight) * 0.4f
        rawDown += (sy - rawDown) * 0.4f
        pushGravity()
        if (abs(rawRight - wokeRight) > 0.3f || abs(rawDown - wokeDown) > 0.3f) {
            wokeRight = rawRight
            wokeDown = rawDown
            wake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pushGravity() {
        val p = pendulum ?: return
        val unit = 300f * resources.displayMetrics.density // px per (m/s^2)
        val right: Float
        val down: Float
        if (prefs.tilt && accel != null) {
            val strength = 0.2f + prefs.motion / 50f // 0.2 .. 2.2
            right = (rawRight * strength).coerceIn(-30f, 30f)
            down = max(rawDown, 3.5f) // never let it float away when the phone lies flat
        } else {
            right = 0f
            down = 9.81f
        }
        p.gx = right * unit
        p.gy = down * unit
    }

    // ---------- touch ----------

    private inner class HandleView : View(this@OverlayService) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = pendulum ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    p.beginDrag(e.rawX, e.rawY)
                    wake()
                }
                MotionEvent.ACTION_MOVE -> {
                    p.dragTo(e.rawX, e.rawY)
                    wake()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    p.endDrag()
                    wake()
                }
            }
            return true
        }
    }
}
