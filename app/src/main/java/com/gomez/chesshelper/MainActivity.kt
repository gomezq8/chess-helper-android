package com.gomez.chesshelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val reqCapture = 7
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun title(t: String, size: Float) = TextView(this).apply {
            text = t; textSize = size; gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        }
        fun btn(t: String, on: () -> Unit) = Button(this).apply { text = t; setOnClickListener { on() } }

        box.addView(title("مساعد الشطرنج", 26f))
        box.addView(title("للتدريب ضد البوتات فقط. استخدامه ضد لاعبين حقيقيين يقفل حسابك.", 13f).apply {
            setTextColor(Color.rgb(230, 160, 60))
        })
        status = title("", 15f)
        box.addView(status)

        box.addView(btn("1) السماح بالظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        box.addView(btn("2) تفعيل اللعب التلقائي (إمكانية الوصول)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        box.addView(btn("3) تشغيل") { start() })
        box.addView(btn("إيقاف") { stopService(Intent(this, HelperService::class.java)); refresh() })

        box.addView(title(
            "\nطريقة الاستخدام:\n" +
            "• شغّل، ووافق على تسجيل الشاشة.\n" +
            "• افتح Chess.com وابدأ لعبة ضد بوت.\n" +
            "• خل النافذة العائمة برا الرقعة (اسحبها من النص).\n" +
            "• والرقعة على وضع البداية اضغط «ابدأ».\n\n" +
            "إذا ما قدرت تفعّل إمكانية الوصول: معلومات التطبيق ← ⋮ ← السماح بالإعدادات المقيدة.",
            13f
        ).apply { gravity = Gravity.START })

        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val tap = TapService.instance != null
        status.text = (if (overlay) "✓" else "✗") + " الظهور فوق التطبيقات\n" +
                (if (tap) "✓" else "✗") + " اللعب التلقائي\n" +
                (if (HelperService.running) "▶ شغال" else "■ متوقف")
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "فعّل الخطوة 1 أول"; return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) { status.text = "لازم توافق على تسجيل الشاشة"; return }
        startForegroundService(Intent(this, HelperService::class.java)
            .putExtra(HelperService.EXTRA_CODE, resultCode)
            .putExtra(HelperService.EXTRA_DATA, data))
        // Open Chess.com if it is installed
        packageManager.getLaunchIntentForPackage("com.chess")?.let { startActivity(it) } ?: moveTaskToBack(true)
    }
}
