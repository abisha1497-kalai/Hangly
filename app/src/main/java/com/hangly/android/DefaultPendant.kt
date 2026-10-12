package com.hangly.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/** A small golden lotus medallion, drawn in code so the app needs no bundled image. */
object DefaultPendant {
    fun create(widthPx: Int): Bitmap {
        val w = max1(widthPx)
        val h = (w * 1.45f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.035f
            color = 0xFF9C6B00.toInt()
        }

        // Lotus petals fanning down from under the medallion.
        val pivotY = h * 0.55f
        val petalLen = h * 0.40f
        val petalW = w * 0.30f
        val angles = floatArrayOf(-62f, 62f, -34f, 34f, 0f)
        for (a in angles) {
            c.save()
            c.rotate(a, cx, pivotY)
            val r = RectF(cx - petalW / 2, pivotY, cx + petalW / 2, pivotY + petalLen)
            fill.shader = RadialGradient(
                cx, pivotY + petalLen * 0.7f, petalLen,
                0xFFFFB6C8.toInt(), 0xFFD6336C.toInt(), Shader.TileMode.CLAMP
            )
            c.drawOval(r, fill)
            c.drawOval(r, line)
            c.restore()
        }
        fill.shader = null

        // Bail connecting the medallion to the chain.
        val bail = Paint(line).apply { color = 0xFFE0A526.toInt(); strokeWidth = w * 0.05f }
        c.drawLine(cx, 0f, cx, h * 0.12f, bail)

        // Gold medallion.
        val mr = w * 0.32f
        val my = h * 0.33f
        fill.shader = RadialGradient(
            cx - mr * 0.3f, my - mr * 0.3f, mr * 1.4f,
            0xFFFFE9A3.toInt(), 0xFFC9901A.toInt(), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, my, mr, fill)
        c.drawCircle(cx, my, mr, line)

        // Red gem.
        val gr = mr * 0.58f
        fill.shader = RadialGradient(
            cx - gr * 0.3f, my - gr * 0.3f, gr * 1.3f,
            0xFFFF8A80.toInt(), 0xFFB71C1C.toInt(), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, my, gr, fill)
        fill.shader = null
        fill.color = 0xAAFFFFFF.toInt()
        c.drawCircle(cx - gr * 0.35f, my - gr * 0.35f, gr * 0.16f, fill)

        // Tiny bead at the bottom.
        fill.color = 0xFFE0A526.toInt()
        c.drawCircle(cx, h - w * 0.05f, w * 0.05f, fill)
        return bmp
    }

    private fun max1(v: Int) = if (v < 8) 8 else v
}
