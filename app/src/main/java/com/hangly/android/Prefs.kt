package com.hangly.android

import android.content.Context
import android.content.SharedPreferences

/** All user settings, stored in one SharedPreferences file. */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("hangly", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    /** Max width of the pendant in dp. */
    var sizeDp: Int
        get() = sp.getInt("sizeDp", 90)
        set(v) { sp.edit().putInt("sizeDp", v).apply() }

    /** Chain length in dp. */
    var chainDp: Int
        get() = sp.getInt("chainDp", 200)
        set(v) { sp.edit().putInt("chainDp", v).apply() }

    /** Horizontal position of the hook, 0 (left) .. 100 (right). */
    var pivot: Int
        get() = sp.getInt("pivot", 82)
        set(v) { sp.edit().putInt("pivot", v).apply() }

    /** How strongly tilts and shakes move the pendant, 0..100. */
    var motion: Int
        get() = sp.getInt("motion", 50)
        set(v) { sp.edit().putInt("motion", v).apply() }

    var tilt: Boolean
        get() = sp.getBoolean("tilt", true)
        set(v) { sp.edit().putBoolean("tilt", v).apply() }

    /** 0 = gold, 1 = silver, 2 = red thread. */
    var style: Int
        get() = sp.getInt("style", 0)
        set(v) { sp.edit().putInt("style", v).apply() }

    var bootStart: Boolean
        get() = sp.getBoolean("bootStart", false)
        set(v) { sp.edit().putBoolean("bootStart", v).apply() }

    var removeBg: Boolean
        get() = sp.getBoolean("removeBg", true)
        set(v) { sp.edit().putBoolean("removeBg", v).apply() }

    /** Bumped whenever the pendant image changes, so the overlay reloads it. */
    fun bumpImage() {
        sp.edit().putInt("imgVer", sp.getInt("imgVer", 0) + 1).apply()
    }
}
