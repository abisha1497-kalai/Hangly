package com.hangly.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/** Loads, saves and prepares the image that hangs on the chain. */
object PendantStore {
    private const val FILE = "pendant.png"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun clearCustom(ctx: Context) {
        file(ctx).delete()
    }

    /** The pendant scaled to fit [widthPx] wide (and at most 1.8x as tall). */
    fun load(ctx: Context, widthPx: Int): Bitmap {
        val f = file(ctx)
        if (f.exists()) {
            val src = BitmapFactory.decodeFile(f.absolutePath)
            if (src != null && src.width > 0 && src.height > 0) {
                val s = minOf(widthPx.toFloat() / src.width, widthPx * 1.8f / src.height)
                return Bitmap.createScaledBitmap(
                    src,
                    max(1, (src.width * s).toInt()),
                    max(1, (src.height * s).toInt()),
                    true
                )
            }
        }
        return DefaultPendant.create(widthPx)
    }

    /** Reads an image the user picked, optionally strips a plain background, trims it and stores it. */
    fun saveFromUri(ctx: Context, uri: Uri, removeBg: Boolean): Boolean {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false

            val work = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: return false
            if (removeBg) removeBackground(work, 44)
            val trimmed = trim(work)

            FileOutputStream(file(ctx)).use { trimmed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return true
        } catch (e: Exception) {
            return false
        } catch (e: OutOfMemoryError) {
            return false
        }
    }

    /** Flood-fills from the image border, making pixels close to the corner colour transparent. */
    private fun removeBackground(bmp: Bitmap, tol: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val bg = px[0]
        if (Color.alpha(bg) < 16) return // already transparent, nothing to do
        val br = Color.red(bg)
        val bgG = Color.green(bg)
        val bb = Color.blue(bg)

        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0

        fun near(c: Int): Boolean =
            Color.alpha(c) < 16 ||
                (abs(Color.red(c) - br) <= tol &&
                    abs(Color.green(c) - bgG) <= tol &&
                    abs(Color.blue(c) - bb) <= tol)

        fun push(i: Int) {
            if (!seen[i] && near(px[i])) {
                seen[i] = true
                stack[top++] = i
            }
        }

        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (top > 0) {
            val i = stack[--top]
            px[i] = 0
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Crops away fully transparent borders. */
    private fun trim(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.alpha(px[y * w + x]) > 20) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return bmp
        return Bitmap.createBitmap(bmp, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
