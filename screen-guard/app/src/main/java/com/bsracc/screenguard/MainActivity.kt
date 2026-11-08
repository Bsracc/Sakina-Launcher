package com.bsracc.screenguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(0xFFF6F4EE.toInt())
        }

        val title = TextView(this).apply {
            text = "Screen Guard"
            textSize = 28f
            setTextColor(0xFF18251E.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        content.addView(title)

        val subtitle = TextView(this).apply {
            text = "Sensor layar lokal untuk video dan aplikasi yang sedang kamu buka."
            textSize = 15f
            setTextColor(0xFF4E5B52.toInt())
            setPadding(0, dp(8), 0, dp(18))
        }
        content.addView(subtitle)

        val info = TextView(this).apply {
            text = "CARA KERJA\n" +
                "• Kamu memberi izin screen capture Android.\n" +
                "• Model lokal memeriksa wajah yang terlihat.\n" +
                "• Jika model memperkirakan tampilan wajah feminin, aplikasi menutup area tubuh dengan blok hitam.\n\n" +
                "CATATAN PENTING\n" +
                "Ini prototipe eksperimental. Area tubuh diperkirakan dari posisi wajah, bukan hasil segmentasi tubuh yang presisi. Wajah tertutup, sangat kecil, atau tidak terdeteksi bisa terlewat. Hasil klasifikasi juga bisa salah."
            textSize = 14f
            setTextColor(0xFF28352D.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        content.addView(info)

        statusText = TextView(this).apply {
            text = "Status: belum aktif"
            textSize = 14f
            setTextColor(0xFF1F4D3F.toInt())
            setPadding(0, dp(20), 0, dp(12))
        }
        content.addView(statusText)

        val startButton = Button(this).apply {
            text = "Mulai sensor layar"
            setOnClickListener { beginPermissionsFlow() }
        }
        content.addView(startButton, matchWidth())

        val stopButton = Button(this).apply {
            text = "Hentikan sensor"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                })
                statusText.text = "Status: perintah berhenti dikirim"
            }
        }
        content.addView(stopButton, matchWidth())

        val privacy = TextView(this).apply {
            text = "Privasi: pemrosesan wajah dilakukan di perangkat. Aplikasi ini tidak meminta izin internet dan tidak menyimpan rekaman layar."
            textSize = 12f
            setTextColor(0xFF647067.toInt())
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(privacy)

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun beginPermissionsFlow() {
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "Status: izinkan tampil di atas aplikasi lain"
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Uses the platform activity result callback for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    requestScreenCapture()
                } else {
                    statusText.text = "Status: izin overlay belum diberikan"
                }
            }
            REQUEST_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_START
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    statusText.text = "Status: permintaan sensor dikirim"
                } else {
                    statusText.text = "Status: izin screen capture ditolak"
                }
            }
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_OVERLAY = 401
        private const val REQUEST_CAPTURE = 402
    }
}
