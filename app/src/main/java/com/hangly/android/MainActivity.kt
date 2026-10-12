package com.hangly.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Settings screen. Everything is built in code to keep the project dependency-free. */
class MainActivity : Activity() {

    private companion object {
        const val REQ_IMAGE = 11
        const val INK = 0xFF1E2430.toInt()
        const val MUTED = 0xFF667085.toInt()
        const val ACCENT = 0xFFB07A0A.toInt()
    }

    private lateinit var prefs: Prefs
    private lateinit var master: Switch
    private lateinit var status: TextView
    private lateinit var permButton: Button
    private lateinit var preview: ImageView
    private var ignoreSwitch = false
    private var waitingForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF7F4EE.toInt())
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        root.addView(text("Hangly", 30f, true, INK))
        root.addView(text("A little charm that hangs from the top of your screen and swings when you touch, tilt or shake.", 14f, false, MUTED).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        // Master switch card
        val card = card(root)
        master = Switch(this).apply {
            text = "Show pendant on screen"
            textSize = 17f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(master)
        status = text("", 13f, false, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        card.addView(status)
        permButton = Button(this).apply {
            text = "Allow display over other apps"
            setOnClickListener { openOverlaySettings() }
        }
        card.addView(permButton)

        master.setOnCheckedChangeListener { _, on ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            if (on) turnOn() else turnOff()
        }

        // Pendant image
        val imgCard = card(root)
        imgCard.addView(text("Pendant", 17f, true, INK))
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        imgCard.addView(preview)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Choose image"
            setOnClickListener { pickImage() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Use default"
            setOnClickListener {
                PendantStore.clearCustom(this@MainActivity)
                prefs.bumpImage()
                refreshPreview()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        imgCard.addView(row)
        imgCard.addView(CheckBox(this).apply {
            text = "Remove a plain background (for JPG or white backgrounds)"
            textSize = 13f
            setTextColor(INK)
            isChecked = prefs.removeBg
            setOnCheckedChangeListener { _, c -> prefs.removeBg = c }
        })
        imgCard.addView(text("Tip: a PNG with a transparent background looks best.", 12f, false, MUTED))

        // Look and feel
        val look = card(root)
        look.addView(text("Look & feel", 17f, true, INK))
        slider(look, "Pendant size", " dp", 40, 200, prefs.sizeDp) { prefs.sizeDp = it }
        slider(look, "Chain length", " dp", 80, 420, prefs.chainDp) { prefs.chainDp = it }
        slider(look, "Position (left to right)", " %", 0, 100, prefs.pivot) { prefs.pivot = it }
        slider(look, "Swing strength", " %", 0, 100, prefs.motion) { prefs.motion = it }

        look.addView(text("Chain style", 14f, false, INK).apply { setPadding(0, dp(12), 0, dp(2)) })
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val names = arrayOf("Gold", "Silver", "Red thread")
        for (i in names.indices) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = names[i]
                setTextColor(INK)
                isChecked = prefs.style == i
            })
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val idx = g.indexOfChild(g.findViewById(checkedId))
            if (idx >= 0) prefs.style = idx
        }
        look.addView(group)

        look.addView(Switch(this).apply {
            text = "Move with tilt and shakes"
            setTextColor(INK)
            isChecked = prefs.tilt
            setPadding(0, dp(10), 0, dp(4))
            setOnCheckedChangeListener { _, c -> prefs.tilt = c }
        })

        // General
        val gen = card(root)
        gen.addView(text("General", 17f, true, INK))
        gen.addView(Switch(this).apply {
            text = "Start when the phone restarts"
            setTextColor(INK)
            isChecked = prefs.bootStart
            setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, c -> prefs.bootStart = c }
        })
        gen.addView(text("Drag the pendant with your finger to swing it. Use the Stop button in the notification to hide it quickly.", 12f, false, MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })

        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        if (waitingForPermission && granted) {
            waitingForPermission = false
            turnOn()
        }
        refreshStatus()
    }

    // ---------- actions ----------

    private fun turnOn() {
        if (!Settings.canDrawOverlays(this)) {
            setSwitch(false)
            Toast.makeText(this, "Allow \"Display over other apps\" for Hangly first", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        prefs.enabled = true
        OverlayService.start(this)
        refreshStatus()
    }

    private fun turnOff() {
        prefs.enabled = false
        OverlayService.stop(this)
        refreshStatus()
    }

    private fun openOverlaySettings() {
        waitingForPermission = true
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Choose pendant image"), REQ_IMAGE)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val removeBg = prefs.removeBg
        Thread {
            val ok = PendantStore.saveFromUri(this, uri, removeBg)
            runOnUiThread {
                if (ok) {
                    prefs.bumpImage()
                    refreshPreview()
                } else {
                    Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- UI helpers ----------

    private fun refreshStatus() {
        val granted = Settings.canDrawOverlays(this)
        permButton.visibility = if (granted) View.GONE else View.VISIBLE
        setSwitch(granted && prefs.enabled)
        status.text = when {
            !granted -> "Hangly needs permission to draw over other apps."
            prefs.enabled -> "Hanging around. Tap the pendant to swing it."
            else -> "Off."
        }
    }

    private fun setSwitch(on: Boolean) {
        ignoreSwitch = true
        master.isChecked = on
        ignoreSwitch = false
    }

    private fun refreshPreview() {
        preview.setImageBitmap(PendantStore.load(this, dp(120)))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        parent.addView(c, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })
        return c
    }

    private fun slider(
        parent: LinearLayout, title: String, unit: String,
        lo: Int, hi: Int, initial: Int, onSet: (Int) -> Unit
    ) {
        val label = text("$title: $initial$unit", 14f, false, INK).apply { setPadding(0, dp(12), 0, 0) }
        val bar = SeekBar(this)
        bar.max = hi - lo
        bar.progress = initial - lo
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + lo
                label.text = "$title: $v$unit"
                if (fromUser) onSet(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(label)
        parent.addView(bar)
    }
}
