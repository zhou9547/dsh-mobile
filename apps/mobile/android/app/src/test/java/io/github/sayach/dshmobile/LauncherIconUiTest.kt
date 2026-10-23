package io.github.sayach.dshmobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Android resource/rendering evidence in a cloud JVM, not a physical launcher's cache behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconUiTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun allEightChoicesShowTheirActualArtworkAndSelection() {
        val labels = arrayOf(R.string.icon_choice_whale_girl, R.string.icon_choice_official_whale,
            R.string.icon_choice_official_whale_dark, R.string.icon_choice_official_whale_teal,
            R.string.icon_choice_official_whale_mono, R.string.icon_choice_official_whale_black,
            R.string.icon_choice_official_whale_white, R.string.icon_choice_tianqing_fish)
            .map { context.getString(it) }.toTypedArray()
        val adapter = LauncherIconAdapter(context, labels)
        assertEquals(8, adapter.count)
        assertEquals("天青色瞪眼鱼", labels.last())
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.app_surface))
        }
        for (index in 0 until adapter.count) {
            val row = adapter.getView(index, null, rows) as ViewGroup
            assertNotNull((row.getChildAt(0) as ImageView).drawable)
            (row as Checkable).isChecked = index == adapter.count - 1
            assertEquals(index == adapter.count - 1, row.isChecked)
            rows.addView(row)
        }
        val density = context.resources.displayMetrics.density
        val width = (390 * density).toInt()
        val height = (650 * density).toInt()
        rows.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        rows.layout(0, 0, width, height)
        fun settle(view: View) {
            view.jumpDrawablesToCurrentState()
            if (view is ViewGroup) for (i in 0 until view.childCount) settle(view.getChildAt(i))
        }
        settle(rows)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rows.draw(Canvas(image))
        val directory = File(System.getProperty("user.dir"), "build/icon-ui-evidence").apply { mkdirs() }
        val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        File(directory, "built-in-icons-api${android.os.Build.VERSION.SDK_INT}-${if (night) "night" else "day"}.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(sdk = [35], qualifiers = "zh-rCN-night")
    fun chineseNightModeShowsTheNewNamedIcon() { allEightChoicesShowTheirActualArtworkAndSelection() }

    @Test fun newIconSwitchesTheRealLauncherEntryWithoutCreatingAShortcut() {
        val pm = context.packageManager
        fun component(icon: LauncherIconPolicy.Icon) = ComponentName(context,
            LauncherIconPolicy.aliasClassName(MainActivity::class.java.name.substringBeforeLast('.'), icon))
        LauncherIconPolicy.switchIcon("tianqing_fish", false,
            { pm.getComponentEnabledSetting(component(it)) }) { changes ->
            changes.forEach { pm.setComponentEnabledSetting(component(it.icon), it.state, PackageManager.DONT_KILL_APP) }
        }
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        @Suppress("DEPRECATION")
        val entries = pm.queryIntentActivities(launch, 0)
        assertEquals(listOf("io.github.sayach.dshmobile.LauncherTianqingFish"), entries.map { it.activityInfo.name })
        assertTrue(context.getSystemService(ShortcutManager::class.java).pinnedShortcuts.isEmpty())
    }

    @Test fun upgradeRetiresOnlyOurOldPhotoShortcutAndItsSavedImage() {
        val manager = context.getSystemService(ShortcutManager::class.java)
        fun shortcut(id: String) = ShortcutInfo.Builder(context, id).setShortLabel("Fixture")
            .setActivity(ComponentName(context, "io.github.sayach.dshmobile.LauncherWhaleGirl"))
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).build()
        val legacy = shortcut("custom-launcher-icon")
        manager.requestPinShortcut(legacy, null)
        manager.requestPinShortcut(shortcut("unrelated-shortcut"), null)
        val image = File(context.filesDir, "custom-launcher-icon.zip").apply { writeText("retired image fixture") }
        val pairedFixture = File(context.filesDir, "paired-record-fixture.json").apply { writeText("preserve") }
        LegacyIconShortcutCleanup.retire(context)
        assertFalse(image.exists())
        assertEquals("preserve", pairedFixture.readText())
        // The platform rejects re-pinning a disabled entry; unrelated entries stay available.
        assertThrows(IllegalArgumentException::class.java) { manager.requestPinShortcut(legacy, null) }
        assertTrue(manager.requestPinShortcut(shortcut("unrelated-shortcut"), null))
    }
}
