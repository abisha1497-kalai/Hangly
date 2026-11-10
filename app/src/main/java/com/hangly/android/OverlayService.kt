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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that draws the hanging pendant over every app.
 *
 * These overlay windows are used:
 *  - a full-width, touch-through window that draws the chains and pendants, and
 *  - one small invisible window per charm that follows its pendant and catches touches, so you
 *    can grab, tap or long-press it while everything else on screen stays tappable.
 */
class OverlayService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "com.hangly.android.STOP"
        private const val CHANNEL = "hangly_overlay"
        private const val NOTIF_ID = 7
        private const val STEP = 1f / 120f
        /** Settings that change behaviour but not the look: no need to rebuild the overlay. */
        private val NO_REBUILD = setOf("enabled", "tapAction", "longAction", "haptics")

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
    private val handles = ArrayList<HandleView>()
    private val handleParams = ArrayList<WindowManager.LayoutParams>()
    private var charmPx = 0
    private var muteRebuild = false
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
        if (!muteRebuild && (key ?: "") !in NO_REBUILD) {
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
            updateHandles(p)

            if (!p.anyDragging && p.motion() < 0.05f * resources.displayMetrics.density) restFrames++ else restFrames = 0
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

        // Several charms must fit side by side, so each one gets narrower.
        val count = prefs.charmCount
        val maxEachDp = (screen.x / d * 0.9f / count).toInt()
        val sizeDp = if (count > 1) min(prefs.sizeDp, maxEachDp) else prefs.sizeDp
        charmPx = (sizeDp * d).toInt()
        val chain = min(prefs.chainDp * d, screen.y * 0.6f)
        val worstBobH = charmPx * 1.8f // tallest a picture can get, so swapping charms never clips

        // Hooks are spaced just wider than a charm, so charms touch only when they swing together.
        val spacing = charmPx * 1.05f
        var groupX = screen.x * (0.06f + 0.88f * prefs.pivot / 100f)
        if (count > 1) {
            val half = spacing * (count - 1) / 2f + charmPx / 2f
            groupX = if (half * 2f < screen.x) groupX.coerceIn(half, screen.x - half) else screen.x / 2f
        }
        val offsets = when (count) {
            1 -> floatArrayOf(0f)
            2 -> floatArrayOf(-0.5f, 0.5f)
            else -> floatArrayOf(-1f, 0f, 1f)
        }
        val lengthFactor = floatArrayOf(1f, 0.82f, 0.92f) // different lengths so they overlap less at rest

        val specs = ArrayList<StrandSpec>()
        var windowH = 0f
        for (i in 0 until count) {
            val len = chain * lengthFactor[i]
            val maxStretch = min(2f, (screen.y - worstBobH - 28 * d) / len).coerceAtLeast(1f)
            specs.add(StrandSpec(PendantStore.load(this, i, charmPx), groupX + offsets[i] * spacing, len, maxStretch))
            windowH = max(windowH, len * maxStretch + worstBobH + 28 * d)
        }
        val p = PendulumView(this, specs, d, prefs.style)
        val height = min(windowH, screen.y.toFloat()).toInt()

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

        val handleFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        for (i in 0 until count) {
            val hv = HandleView(i)
            hv.hw = max(specs[i].bob.width, (56 * d).toInt())
            hv.hh = max(specs[i].bob.height, (56 * d).toInt())
            val hp = WindowManager.LayoutParams(
                hv.hw, hv.hh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                handleFlags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            handles.add(hv)
            handleParams.add(hp)
        }

        try {
            wm.addView(p, rp)
            for (i in handles.indices) wm.addView(handles[i], handleParams[i])
        } catch (e: Exception) {
            // Permission revoked or window refused: shut down cleanly.
            try { wm.removeView(p) } catch (e: Exception) {}
            for (h in handles) try { wm.removeView(h) } catch (e: Exception) {}
            handles.clear()
            handleParams.clear()
            prefs.enabled = false
            stopSelf()
            return
        }
        pendulum = p

        sensorManager.unregisterListener(this)
        if (prefs.tilt && !paused) {
            accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        pushGravity()
        for (i in handles.indices) {
            val hp = handleParams[i]
            p.bobCenter(i, center)
            hp.x = (center[0] - handles[i].hw / 2f).toInt()
            hp.y = (center[1] - handles[i].hh / 2f).toInt()
            try { wm.updateViewLayout(handles[i], hp) } catch (e: Exception) {}
        }
        wake()
    }

    private fun removeOverlay() {
        choreographer.removeFrameCallback(frame)
        frameScheduled = false
        pendulum?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        for (h in handles) try { wm.removeView(h) } catch (e: Exception) {}
        handles.clear()
        handleParams.clear()
        pendulum = null
    }

    private fun updateHandles(p: PendulumView) {
        for (i in handles.indices) {
            val hv = handles[i]
            val hp = handleParams[i]
            p.bobCenter(i, center)
            val nx = (center[0] - hv.hw / 2f).toInt()
            val ny = (center[1] - hv.hh / 2f).toInt()
            if (nx != hp.x || ny != hp.y) {
                hp.x = nx
                hp.y = ny
                try { wm.updateViewLayout(hv, hp) } catch (e: Exception) {}
            }
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

    // ---------- touch and actions ----------

    private fun haptic(v: View, strong: Boolean) {
        if (!prefs.haptics) return
        v.performHapticFeedback(
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    /** A quick tap on charm [i]. [rawX] is where the finger landed, to push it away from that side. */
    private fun onTap(i: Int, rawX: Float) {
        val p = pendulum ?: return
        when (prefs.tapAction) {
            0 -> {
                p.bobCenter(i, center)
                val dir = if (rawX < center[0]) 1f else -1f
                p.nudge(i, dir * 2.4f * resources.displayMetrics.density)
                p.sparkle(i)
                handles.getOrNull(i)?.let { haptic(it, false) }
            }
            1 -> {
                cycleCharm(i)
                handles.getOrNull(i)?.let { haptic(it, false) }
            }
        }
        wake()
    }

    /** Holding charm [i] without moving. */
    private fun onLongPress(i: Int) {
        when (prefs.longAction) {
            0 -> try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (e: Exception) {
                // Some phones refuse to start an app from the background; nothing to do.
            }
            1 -> cycleCharm(i)
            2 -> handler.post {
                prefs.enabled = false
                stopSelf()
            }
        }
        wake()
    }

    /** Swaps charm [i] for the next built-in pendant, keeping it swinging where it is. */
    private fun cycleCharm(i: Int) {
        val p = pendulum ?: return
        if (i !in handles.indices) return
        val ids = DefaultPendant.IDS
        val next = ids[(ids.indexOf(PendantStore.idFor(this, i)) + 1) % ids.size]
        muteRebuild = true
        try {
            prefs.setPendantId(i, next)
        } finally {
            muteRebuild = false
        }
        val bob = PendantStore.load(this, i, charmPx)
        p.setBob(i, bob)
        val d = resources.displayMetrics.density
        val hv = handles[i]
        val hp = handleParams[i]
        hv.hw = max(bob.width, (56 * d).toInt())
        hv.hh = max(bob.height, (56 * d).toInt())
        hp.width = hv.hw
        hp.height = hv.hh
        try { wm.updateViewLayout(hv, hp) } catch (e: Exception) {}
        p.sparkle(i)
    }

    /** The invisible window that follows charm [index] and catches touches on it. */
    private inner class HandleView(val index: Int) : View(this@OverlayService) {
        var hw = 0
        var hh = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var heldLong = false
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop.toFloat()
        private val longPress = Runnable {
            if (!moved && !heldLong) {
                heldLong = true
                haptic(this, true)
                onLongPress(index)
            }
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(longPress)
            super.onDetachedFromWindow()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = pendulum ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    heldLong = false
                    p.beginDrag(index, e.rawX, e.rawY)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    wake()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    p.dragTo(index, e.rawX, e.rawY)
                    wake()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    p.endDrag(index)
                    if (e.actionMasked == MotionEvent.ACTION_UP && !moved && !heldLong) {
                        onTap(index, e.rawX)
                    }
                    wake()
                }
            }
            return true
        }
    }
}
