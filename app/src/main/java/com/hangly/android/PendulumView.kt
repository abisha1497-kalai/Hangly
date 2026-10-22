package com.hangly.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A chain hanging from a fixed hook at the top of the screen with a pendant on the end.
 * The chain is a Verlet rope; the pendant is a heavy last link that rotates with the chain.
 * Coordinates are screen pixels (this view sits at the top-left corner of the screen).
 */
class PendulumView(
    context: Context,
    private val bob: Bitmap,
    private val pivotX: Float,
    private val chainLen: Float,
    private val density: Float,
    style: Int,
    /** How far the rope may stretch while dragging, as a multiple of its rest length. */
    private val maxStretch: Float = 2f
) : View(context) {

    companion object {
        private const val N = 14          // points in the chain
        private const val ITERATIONS = 22 // constraint passes per step
        private const val DAMPING = 0.996f // velocity kept per 1/120 s step
        private const val SPRING_K = 260f  // elastic pull-back strength (higher = snappier)
        private const val SPRING_C = 9f    // elastic damping (lower = more bounce)
        private const val MIN_STRETCH = 0.9f // slight squash on the rebound
    }

    private val x = FloatArray(N)
    private val y = FloatArray(N)
    private val ox = FloatArray(N)
    private val oy = FloatArray(N)
    private val invMass = FloatArray(N) { 1f }
    private val seg = chainLen / (N - 1)

    /** Acceleration in px/s^2: gx to the right, gy downwards. */
    var gx = 0f
    var gy = 0f

    var dragging = false
        private set
    private var dragTx = 0f
    private var dragTy = 0f
    private var grabDx = 0f
    private var grabDy = 0f

    private var angle = 0f

    /** Current rope length as a multiple of its rest length (1 = relaxed). */
    private var stretch = 1f
    private var stretchVel = 0f
    private val path = Path()

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.4f * density
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.1f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 4.5f * density), 0f)
    }
    private val hookPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        when (style) {
            1 -> setColors(0xFF7D8A97.toInt(), 0xFFEEF3F7.toInt())
            2 -> setColors(0xFF7A1C1C.toInt(), 0xFFE0524D.toInt())
            else -> setColors(0xFFB07A0A.toInt(), 0xFFFFD966.toInt())
        }
        invMass[0] = 0f
        invMass[N - 1] = 0.15f // heavy pendant
        for (i in 0 until N) {
            x[i] = pivotX
            y[i] = seg * i
            ox[i] = x[i]
            oy[i] = y[i]
        }
        // A small initial push so it swings gently when it appears.
        for (i in 1 until N) ox[i] = x[i] - 1.2f * density * i / (N - 1)
        gy = 9.81f * 300f * density
    }

    private fun setColors(base: Int, light: Int) {
        basePaint.color = base
        linkPaint.color = light
        hookPaint.color = base
        ringPaint.color = base
    }

    /** Advances the simulation by [dt] seconds (call with a small fixed step, e.g. 1/120). */
    fun step(dt: Float) {
        val dt2 = dt * dt
        // Elastic rope: follows the finger while dragging, springs back (with a bounce) on release.
        if (dragging) {
            val d = hypot(x[N - 1] - pivotX, y[N - 1])
            val target = (d / chainLen).coerceIn(1f, maxStretch)
            stretchVel = (target - stretch) / dt * 0.2f
            stretch += (target - stretch) * 0.5f
        } else if (stretch != 1f || stretchVel != 0f) {
            stretchVel += (-SPRING_K * (stretch - 1f) - SPRING_C * stretchVel) * dt
            stretch = (stretch + stretchVel * dt).coerceIn(MIN_STRETCH, maxStretch)
            if (abs(stretch - 1f) < 0.0005f && abs(stretchVel) < 0.01f) {
                stretch = 1f
                stretchVel = 0f
            }
        }
        val rest = seg * stretch
        for (i in 1 until N) {
            if (dragging && i == N - 1) continue
            val vx = (x[i] - ox[i]) * DAMPING
            val vy = (y[i] - oy[i]) * DAMPING
            ox[i] = x[i]
            oy[i] = y[i]
            x[i] += vx + gx * dt2
            y[i] += vy + gy * dt2
        }
        x[0] = pivotX
        y[0] = 0f
        if (dragging) {
            ox[N - 1] = x[N - 1]
            oy[N - 1] = y[N - 1]
            x[N - 1] += (dragTx - x[N - 1]) * 0.4f
            y[N - 1] += (dragTy - y[N - 1]) * 0.4f
        }
        for (it in 0 until ITERATIONS) {
            for (i in 0 until N - 1) {
                val j = i + 1
                val dx = x[j] - x[i]
                val dy = y[j] - y[i]
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 1e-4f) continue
                val wi = invMass[i]
                val wj = if (dragging && j == N - 1) 0f else invMass[j]
                val ws = wi + wj
                if (ws == 0f) continue
                val diff = (dist - rest) / dist
                x[i] += dx * diff * wi / ws
                y[i] += dy * diff * wi / ws
                x[j] -= dx * diff * wj / ws
                y[j] -= dy * diff * wj / ws
            }
            x[0] = pivotX
            y[0] = 0f
        }
    }

    /** Sum of recent movement; near zero when the chain has settled. */
    fun motion(): Float {
        var s = 0f
        for (i in 1 until N) s += abs(x[i] - ox[i]) + abs(y[i] - oy[i])
        s += abs(stretch - 1f) * 50f + abs(stretchVel)
        return s
    }

    /** Writes the pendant's centre (screen px) into [out]. */
    fun bobCenter(out: FloatArray) {
        val dx = x[N - 1] - x[N - 2]
        val dy = y[N - 1] - y[N - 2]
        val len = hypot(dx, dy).let { if (it < 1e-4f) 1f else it }
        val half = bob.height / 2f - 4f * density
        out[0] = x[N - 1] + dx / len * half
        out[1] = y[N - 1] + dy / len * half
    }

    fun beginDrag(rawX: Float, rawY: Float) {
        dragging = true
        grabDx = x[N - 1] - rawX
        grabDy = y[N - 1] - rawY
        dragTx = x[N - 1]
        dragTy = y[N - 1]
    }

    fun dragTo(rawX: Float, rawY: Float) {
        var tx = rawX + grabDx
        var ty = (rawY + grabDy).coerceAtLeast(0f)
        val dx = tx - pivotX
        val dist = hypot(dx, ty)
        val maxReach = chainLen * maxStretch * 0.995f
        if (dist > maxReach) {
            val k = maxReach / dist
            tx = pivotX + dx * k
            ty *= k
        }
        dragTx = tx
        dragTy = ty
    }

    fun endDrag() {
        dragging = false
    }

    override fun onDraw(canvas: Canvas) {
        // Chain: smooth curve through the points.
        path.reset()
        path.moveTo(x[0], y[0])
        for (i in 1 until N - 1) {
            val mx = (x[i] + x[i + 1]) / 2f
            val my = (y[i] + y[i + 1]) / 2f
            path.quadTo(x[i], y[i], mx, my)
        }
        path.lineTo(x[N - 1], y[N - 1])
        canvas.drawPath(path, basePaint)
        canvas.drawPath(path, linkPaint)

        // Hook at the top edge.
        canvas.drawCircle(x[0], y[0] + 1f * density, 4.5f * density, hookPaint)

        // Pendant, rotated to follow the last chain segment.
        val dx = x[N - 1] - x[N - 2]
        val dy = y[N - 1] - y[N - 2]
        val target = -Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
        val diff = ((target - angle + 540f) % 360f) - 180f
        angle += diff * 0.4f

        canvas.save()
        canvas.translate(x[N - 1], y[N - 1])
        canvas.rotate(angle)
        canvas.drawBitmap(bob, -bob.width / 2f, -4f * density, bmpPaint)
        canvas.drawCircle(0f, 0f, 3.2f * density, ringPaint) // jump ring
        canvas.restore()
    }
}
