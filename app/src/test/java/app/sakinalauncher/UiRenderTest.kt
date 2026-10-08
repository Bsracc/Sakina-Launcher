package app.sakinalauncher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.drawToBitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Visual renders: inflates the REAL layouts with the REAL theme tokens and writes
 * PNGs to app/build/renders/, so the UI can be reviewed without a device or
 * emulator (Robolectric NATIVE graphics draws with Skia). Text that the app sets
 * at runtime is filled in here the way the fragments do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-hdpi")
class UiRenderTest {

    private val outDir = File("build/renders").apply { mkdirs() }

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun save(name: String, root: View, widthPx: Int, heightPx: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bmp = root.drawToBitmap(Bitmap.Config.ARGB_8888)
        val out = File(outDir, "$name.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENDER ${out.absolutePath}")
    }

    private fun findText(root: View, id: Int): TextView = root.findViewById(id)

    @Test
    fun renderHome() {
        val a = activity()
        val root = LayoutInflater.from(a).inflate(R.layout.fragment_home, null) as ViewGroup
        // Wallpaper stand-in: mid-tone photo-like grey-blue (light theme, black ink).
        root.setBackgroundColor(Color.rgb(196, 189, 176))
        root.findViewById<View>(R.id.homeWash).visibility = View.VISIBLE
        root.findViewById<View>(R.id.homeClockWash).visibility = View.VISIBLE
        root.findViewById<View>(R.id.dateTimeLayout).visibility = View.VISIBLE
        findText(root, R.id.date).text = "Mon, 06 Oct"
        val labels = listOf(
            R.id.homeApp1 to "Phone", R.id.homeApp2 to "Messages",
            R.id.homeApp3 to "Camera", R.id.homeApp4 to "Instagram",
            R.id.homeApp5 to "WhatsApp", R.id.homeApp6 to "Maps",
            R.id.homeApp7 to "Gmail", R.id.homeApp8 to "YouTube",
            R.id.homeApp9 to "Notes", R.id.homeApp10 to "Files",
        )
        labels.forEach { (id, label) ->
            val tv = findText(root, id)
            tv.visibility = View.VISIBLE
            tv.text = label
        }
        root.findViewById<View>(R.id.tvScreenTime).visibility = View.VISIBLE
        findText(root, R.id.tvScreenTime).text = "2h 11m"
        root.findViewById<View>(R.id.tvScreenTimeGoal).visibility = View.VISIBLE
        findText(root, R.id.tvScreenTimeGoal).text = "1h 20m of 2h"
        root.findViewById<View>(R.id.firstRunTips).visibility = View.VISIBLE
        save("home", root, 540, 1200)
    }

    @Test
    fun renderDrawerRowsMindful() {
        val a = activity()
        val container = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_scrim)
            setPadding(0, 32, 0, 32)
        }
        fun addRow(label: String, sublabel: String = "", mindful: Boolean, picker: Boolean) {
            val row = LayoutInflater.from(a).inflate(R.layout.adapter_app_drawer, container, false) as View
            findText(row, R.id.appTitle).text = label
            val sub = row.findViewById<TextView>(R.id.appTitleSub)
            sub.visibility = if (sublabel.isEmpty()) View.GONE else View.VISIBLE
            sub.text = sublabel
            val check = row.findViewById<View>(R.id.mindfulCheck)
            val checkBg = row.findViewById<View>(R.id.mindfulCheckBg)
            val glyph = row.findViewById<View>(R.id.mindfulCheckGlyph)
            check.visibility = if (mindful || picker) View.VISIBLE else View.GONE
            checkBg.visibility = if (picker) View.VISIBLE else View.GONE
            checkBg.isSelected = mindful
            glyph.visibility = if (mindful) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.otherProfileIndicator).visibility = View.GONE
            container.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        // Normal drawer: quiet check glyph only on opted-in apps.
        addRow("Instagram", mindful = true, picker = false)
        addRow("WhatsApp", mindful = false, picker = false)
        addRow("Settings", sublabel = "com.android.settings", mindful = true, picker = false)
        // Mindful picker: every row shows the checkbox (ring = off, filled = on).
        addRow("Instagram", mindful = true, picker = true)
        addRow("WhatsApp", mindful = false, picker = true)
        save("drawer_rows_mindful", container, 540, 1200)
    }

    @Test
    fun renderMuslimCenter() {
        val a = activity()
        val root = LayoutInflater.from(a).inflate(R.layout.fragment_muslim_center, null) as ViewGroup
        findText(root, R.id.nextPrayerName).text = "Maghrib"
        findText(root, R.id.nextPrayer).text = "18:08"
        findText(root, R.id.subuhName).text = "Fajr"
        findText(root, R.id.dzuhurName).text = "Dhuhr"
        findText(root, R.id.asharName).text = "Asr"
        findText(root, R.id.maghribName).text = "Maghrib"
        findText(root, R.id.isyaName).text = "Isha"
        findText(root, R.id.subuhTime).text = "04:39"
        findText(root, R.id.dzuhurTime).text = "11:56"
        findText(root, R.id.asharTime).text = "15:17"
        findText(root, R.id.maghribTime).text = "17:49"
        findText(root, R.id.isyaTime).text = "19:02"
        findText(root, R.id.location).text = "Jakarta, Indonesia"
        findText(root, R.id.morningDhikrCount).text = "24"
        findText(root, R.id.eveningDhikrCount).text = "24"
        findText(root, R.id.afterPrayerDhikrCount).text = "13"
        save("muslim_center", root, 540, 1200)
    }

    @Test
    fun renderMindfulDialog() {
        val a = activity()
        val root = LayoutInflater.from(a).inflate(R.layout.dialog_mindful_launch, null)
        // The real dialog sits on a dim shade over the home screen; stand-in here.
        root.setBackgroundColor(Color.rgb(120, 116, 110))
        findText(root, R.id.mindfulCountdown).text = "3"
        findText(root, R.id.mindfulTitle).text = "Instagram"
        save("mindful_dialog", root, 540, 900)
    }

    @Test
    fun renderSettings() {
        val a = activity()
        val root = LayoutInflater.from(a).inflate(R.layout.fragment_settings, null)
        // Settings scrolls over the wallpaper; stand-in mid-tone like home.
        root.setBackgroundColor(Color.rgb(196, 189, 176))
        root.findViewById<View>(R.id.settingsDim).visibility = View.GONE
        save("settings", root, 540, 1200)
    }
}
